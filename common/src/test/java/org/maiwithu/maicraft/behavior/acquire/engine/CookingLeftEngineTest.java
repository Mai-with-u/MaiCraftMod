// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.cook.FurnacesLeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.LeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿东西途中装好一炉就走：只有 obtain 这一场（连同它备料的每一层）能走；走了就整场当场收场，
 * 不再换别的路，备料的里层留下的外层也跟着收场。来源都是替身，不碰游戏。
 */
class CookingLeftEngineTest {

    private static final String KELP = "minecraft:dried_kelp";
    private static final String BLOCK = "minecraft:dried_kelp_block";
    private static final ItemAcquisition.Scope OBTAIN = new ItemAcquisition.Scope(Set.of(), null, null, true);

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FurnacesLeftCooking left = new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE);
    /** 烧炼替身每次动手时看到的"能不能装炉就走"。 */
    private final List<Boolean> leavesSeen = new ArrayList<>();
    /** 箱子替身被选中动手了几回。 */
    private int chestBegun;

    private ItemAcquisition engine(ItemSource... sources) {
        return new ItemAcquisition(List.of(sources), backpack, new FakeOffhand(), new FakeTags(),
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH, left);
    }

    private static ActionStatus settle(Action action) {
        for (int tick = 1; tick <= 200; tick++) {
            ActionStatus status = action.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("两百刻还没定局");
    }

    /** 烧干海带：能装炉就走时装好就走（一件不进包），不能时当场烧好放进背包。 */
    private ItemSource smelting() {
        return new Source("烧炼", AcquireVia.SMELT, KELP, 1) {
            @Override Step act(ItemRequest request, SourceContext context) {
                leavesSeen.add(context.leavesLongCooking());
                return tick -> {
                    if (context.leavesLongCooking()) {
                        left.leave(new LeftCooking(new WorldPosition(12, 64, -30, null), WorkstationRecipe.Kind.SMELTING,
                                "minecraft:dried_kelp_from_smelting", KELP, "干海带", "minecraft:kelp", "海带", null,
                                64, 64, 167, tick.gameTick(), tick.gameTick() + 12800, null));
                    } else {
                        backpack.add(KELP, request.count());
                    }
                    return ActionStatus.done();
                };
            }
        };
    }

    /** 箱子里有现成的干海带，比烧贵：烧炼走不通才会轮到它。 */
    private ItemSource chest() {
        return new Source("箱子", AcquireVia.CONTAINER, KELP, 50) {
            @Override Step act(ItemRequest request, SourceContext context) {
                chestBegun++;
                return tick -> {
                    backpack.add(KELP, request.count());
                    return ActionStatus.done();
                };
            }
        };
    }

    @Test
    void obtainStopsWhenAFurnaceIsLeftAndTriesNoOtherWay() {
        ActionStatus status = settle(engine(smelting(), chest()).need(
                new ItemRequest(WantedItem.ofItem(KELP), 231, "按需要拿取"), Permissions.DEFAULT, OBTAIN, via -> {}, null));
        Problem problem = assertInstanceOf(ActionStatus.Failed.class, status).problem();
        assertEquals(Problem.Kind.WRONG_TIME, problem.kind());
        assertTrue(problem.message().startsWith("装好炉先走了：(12, 64, -30) 的熔炉里在烧 64 个海带"), problem.message());
        assertEquals(List.of(true), leavesSeen);
        assertEquals(0, chestBegun, "东西还在炉子里烧着，不换去箱子拿");
    }

    @Test
    void otherAbilitiesStillWaitByTheFurnace() {
        // 别的能力途中缺料去拿：不装炉就走，烧好就算拿到。
        ActionStatus status = settle(engine(smelting(), chest()).actionFor(
                new ItemRequest(WantedItem.ofItem(KELP), 8, "施工缺料"), Permissions.DEFAULT));
        assertEquals(ActionStatus.done(), status);
        assertEquals(List.of(false), leavesSeen);
        assertEquals(0, left.timesLeft());
    }

    @Test
    void preparingIngredientsInsideObtainAlsoStopsTheWholeRun() {
        // 要干海带块，先要九个干海带：里层烧干海带装好炉就走，外层不接着做块，整场当场收场。
        ItemAcquisition[] engine = new ItemAcquisition[1];
        boolean[] madeBlock = {false};
        ItemSource crafting = new Source("自己做", AcquireVia.CRAFT, BLOCK, 1) {
            @Override Step act(ItemRequest request, SourceContext context) {
                Action ingredients = engine[0].actionFor(new ItemRequest(WantedItem.ofItem(KELP), 9, "做干海带块"),
                        Permissions.DEFAULT);
                boolean[] ingredientsDone = {false};
                // 和真的备料一串一样：备完料这一刻先交回，下一刻才去做块。
                return tick -> {
                    if (!ingredientsDone[0]) {
                        ActionStatus inner = ingredients.tick(tick);
                        if (inner instanceof ActionStatus.Running) return inner;
                        ingredientsDone[0] = true;
                        return ActionStatus.progressed();
                    }
                    madeBlock[0] = true;
                    backpack.add(BLOCK, 1);
                    return ActionStatus.done();
                };
            }
        };
        engine[0] = engine(crafting, smelting());
        ActionStatus status = settle(engine[0].need(new ItemRequest(WantedItem.ofItem(BLOCK), 1, "按需要拿取"),
                Permissions.DEFAULT, OBTAIN, via -> {}, null));
        assertEquals(Problem.Kind.WRONG_TIME, assertInstanceOf(ActionStatus.Failed.class, status).problem().kind());
        assertEquals(List.of(true), leavesSeen, "备料的里层跟着最外层那一场能装炉就走");
        assertFalse(madeBlock[0]);
    }

    /** 来源动手的一刻：替身只写每刻做什么。 */
    private interface Step {
        ActionStatus tick(TickContext tick);
    }

    /** 只给一样东西的来源替身：报固定代价，动手交给 act。 */
    private abstract static class Source implements ItemSource {
        private final String name;
        private final AcquireVia via;
        private final String itemId;
        private final double distance;

        Source(String name, AcquireVia via, String itemId, double distance) {
            this.name = name;
            this.via = via;
            this.itemId = itemId;
            this.distance = distance;
        }

        abstract Step act(ItemRequest request, SourceContext context);

        @Override public String describe() {
            return name;
        }

        @Override public AcquireVia via() {
            return via;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            return itemId.equals(request.wanted().itemId())
                    ? new SourceQuote.Offer(name, request.count(), new AcquisitionCost(distance, 1), null)
                    : new SourceQuote.Unavailable(name, "不给" + request.wanted().describe());
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            Step action = act(request, context);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    return action.tick(tick);
                }

                @Override public String describe() {
                    return name;
                }
            });
        }
    }
}
