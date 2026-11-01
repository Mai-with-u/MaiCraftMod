// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 饿了身上没吃的：挨类问哪类口粮最近，拿最近那类；哪儿都没有、超了预算都如实说。 */
class FetchesFoodTest {

    /** 拿东西引擎的替身：每类口粮多远按表回答，动手就记下要的是哪类，下一刻做完。 */
    private static class FakeNeeds implements ItemNeeds {
        final Map<String, Double> distances;
        final List<String> asked = new ArrayList<>();
        boolean neverDone;

        FakeNeeds(Map<String, Double> distances) {
            this.distances = distances;
        }

        @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
            return Optional.ofNullable(distances.get(request.wanted().specifier()));
        }

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request.wanted().specifier());
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return neverDone ? ActionStatus.running() : ActionStatus.done();
                }

                @Override public String describe() {
                    return "替身去拿";
                }
            };
        }
    }

    private static TickContext at(long tick) {
        return new TickContext() {
            @Override public long gameTick() {
                return tick;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    @Test
    void 挑最近的那一类口粮去拿() {
        // 网络里没有面包，箱子里有熟肉（5 格），田里有蔬菜（30 格）：拿熟肉。
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/cooked_meat", 5d, "#c:foods/vegetable", 30d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#c:foods/cooked_meat"), needs.asked);
    }

    @Test
    void 这一类弄不成就换下一类() {
        // 问价说面包最近，去弄却没弄成：换熟肉，不就此饿着。
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 2d, "#c:foods/cooked_meat", 8d)) {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request.wanted().specifier());
                boolean bread = request.wanted().specifier().equals("#c:foods/bread");
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return bread ? ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "面包弄不到")) : ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "替身去拿";
                    }
                };
            }
        };
        FetchesFood fetch = new FetchesFood(needs, 1200);
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 5 && !(status instanceof ActionStatus.Done); i++) status = fetch.tick(at(i));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(List.of("#c:foods/bread", "#c:foods/cooked_meat"), needs.asked);
    }

    @Test
    void 叫得出名字的几类都没有_再找能吃的一大类() {
        // 面包、熟肉、熟鱼、蔬菜、水果都弄不到，箱子里只有模组食物：按"能吃的口粮"一大类去拿，不饿着。
        FakeNeeds needs = new FakeNeeds(Map.of("#maicraft:foods/edible", 12d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#maicraft:foods/edible"), needs.asked);
    }

    @Test
    void 近处能打猎就不绕远拿面包() {
        // 门口有羊（打猎报价 2 格），面包合成要走 20 格：直接打猎拿肉，不为熟食绕远。
        FakeNeeds needs = new FakeNeeds(Map.of("#maicraft:foods/edible", 2d, "#c:foods/bread", 20d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#maicraft:foods/edible"), needs.asked);
    }

    @Test
    void 按整条路最远走多远排_不按第一步的距离() {
        // 面包要烧炼：第一步"身边放熔炉"报 0 格，但缺的面团要去 239 格外的深箱子取；羊就在近处，打猎报 57 格。
        // 只看第一步面包反倒最近，实机就这样三轮直奔深箱子不猎 2 格外的羊；按估算里整条路最远走多远排，打猎在前。
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 0d, "#maicraft:foods/edible", 57d)) {
            @Override public GoalEstimate estimate(ItemRequest request, Permissions permissions) {
                String kind = request.wanted().specifier();
                Double farthest = switch (kind) {
                    case "#c:foods/bread" -> 239d;
                    case "#maicraft:foods/edible" -> 57d;
                    default -> null;
                };
                if (farthest == null) {
                    return new GoalEstimate(GoalEstimate.Effort.BLOCKED, kind, "没有来路", null, null, List.of(), false);
                }
                return new GoalEstimate(GoalEstimate.Effort.NEARBY, kind, "", farthest, 60, List.of(), false);
            }
        };
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#maicraft:foods/edible"), needs.asked);
    }

    @Test
    void 估算给不出最远多远时退回第一步的距离() {
        // 估不了（附近还没扫完）：没有整条路的数，先按来源自报的第一步距离排，不因为估不了就说哪儿都没有。
        assertEquals(Optional.of(12d), FetchesFood.walkingDistance(
                GoalEstimate.unknown("x", "附近还没看完"), Optional.of(12d)));
        // 东西在身上或身边几格：不用离开，按 0 格算，比哪条要走路的都近。
        assertEquals(Optional.of(0d), FetchesFood.walkingDistance(
                new GoalEstimate(GoalEstimate.Effort.READY, "x", "", null, 5, List.of(), false), Optional.of(12d)));
        // 弄不到：这一类不参与排序。
        assertEquals(Optional.empty(), FetchesFood.walkingDistance(
                new GoalEstimate(GoalEstimate.Effort.BLOCKED, "x", "没有来路", null, null, List.of(), false),
                Optional.of(12d)));
        // 估出了最远多远：以它为准，第一步的距离不看。
        assertEquals(Optional.of(239d), FetchesFood.walkingDistance(
                new GoalEstimate(GoalEstimate.Effort.NEARBY, "x", "", 239d, 60, List.of(), false), Optional.of(0d)));
    }

    @Test
    void 一样近时熟食优先_不先吃生肉() {
        FakeNeeds needs = new FakeNeeds(Map.of("#maicraft:foods/edible", 5d, "#c:foods/bread", 5d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#c:foods/bread"), needs.asked);
    }

    @Test
    void 只有远处的面包时还是去拿面包() {
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 20d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#c:foods/bread"), needs.asked);
    }

    @Test
    void 弄不成换下一类不算真实进展() {
        // 面包弄不成、换熟肉：换类别只是换个想法再试，身体没动，报告的状态不带进展，
        // 不然每换一类都给停滞守卫续命，站桩一晚上也判不出卡住。
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 2d, "#c:foods/cooked_meat", 8d)) {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request.wanted().specifier());
                boolean bread = request.wanted().specifier().equals("#c:foods/bread");
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return bread ? ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "面包弄不到"))
                                : ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "替身去拿";
                    }
                };
            }
        };
        FetchesFood fetch = new FetchesFood(needs, 1200);
        // 第一刻去拿面包，面包当场失败：换下一类的这一刻返回的状态不带进展。
        ActionStatus afterSwitch = fetch.tick(at(0));
        assertInstanceOf(ActionStatus.Running.class, afterSwitch);
        assertEquals(false, ((ActionStatus.Running) afterSwitch).progressed());
    }

    @Test
    void 哪儿都没有现成口粮如实说() {
        FetchesFood fetch = new FetchesFood(new FakeNeeds(Map.of()), 1200);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, fetch.tick(at(0)));
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
    }

    @Test
    void 超了预算收手() {
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 3d));
        needs.neverDone = true;
        FetchesFood fetch = new FetchesFood(needs, 100);
        assertInstanceOf(ActionStatus.Running.class, fetch.tick(at(0)));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, fetch.tick(at(101)));
        assertEquals(Problem.Kind.STUCK, failed.problem().kind());
    }

    @Test
    void 去拿的动作还没点开界面时被问停不停与叫停_照常转交() {
        // 实机 250：控制循环每刻先问"停不停"再推进，问完放行还会叫停、收尾；
        // 拿取动作在界面点开前手上是空的，这一问一停都要照常转交出去，吃饭临时任务才能走到终态收场，
        // 不能卡在打不开界面的一问上永远占着身体。
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 2d));
        needs.neverDone = true;
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Running.class, fetch.tick(at(0)));
        assertEquals(Interruptibility.WORKING, fetch.interruptibility());
        fetch.pause();
        fetch.close();
    }
}
