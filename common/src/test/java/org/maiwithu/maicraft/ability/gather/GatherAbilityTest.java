// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.mining.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采集的能力决定：目标对象落实成位置（观察编号失效就以目标消失结束、坐标缺高度找柱列顶面），
 * 没有目标或不认的目标对象一次说清。现场用替身，不碰游戏。
 */
class GatherAbilityTest {

    private record FakeSeen(Map<String, GatherAbility.SeesTargets.SeenTarget> entries)
            implements GatherAbility.SeesTargets {
        @Override public Optional<GatherAbility.SeesTargets.SeenTarget> lookup(String id) {
            return Optional.ofNullable(entries.get(id));
        }
    }

    private record FakeWorld() implements ReadsSpot {
        @Override public Optional<String> blockTypeAt(WorldPosition at) {
            return Optional.of("minecraft:stone");
        }

        @Override public boolean isCrop(String type) {
            return false;
        }

        @Override public boolean matureCrop(WorldPosition at, String type) {
            return false;
        }

        @Override public OptionalInt surfaceY(int x, int z) {
            return OptionalInt.of(64);
        }
    }

    /** 决定阶段用不到动手的接缝：被调就报错，校验没拦住就会炸出来。 */
    private static DigsBlocks unusableDigs() {
        return target -> {
            throw new IllegalStateException("决定阶段不该动手");
        };
    }

    private static GatherAbility ability(GatherAbility.SeesTargets seen) {
        return ability(seen, emptyBackpack());
    }

    private static GatherAbility ability(GatherAbility.SeesTargets seen, BackpackView backpack) {
        ReadsToolRequirements noTools = new ReadsToolRequirements() {
            @Override public Optional<String> toolRequired(String blockTypeId) {
                return Optional.empty();
            }

            @Override public boolean sufficient(String toolItemId, String blockTypeId) {
                return true;
            }
        };
        return new GatherAbility(seen, new FakeWorld(),
                (target, permissions, records) -> {
                    throw new IllegalStateException("决定阶段不该靠近");
                },
                unusableDigs(), noTools, null, null, permission(), backpack, null, null);
    }

    private StepDecision decide(Target target) {
        return decide(target, new JsonObject());
    }

    private StepDecision decide(Target target, JsonObject params) {
        ParseResult parsed = ability(null).spec().paramSpecs().parse(params);
        if (parsed.params() == null) {
            throw new IllegalArgumentException("测试参数没过参数规格：" + parsed.errors());
        }
        Goal goal = new Goal("maicraft:gather", null, target, parsed.params(), null, List.of(), null);
        return decideWith(goal);
    }

    private StepDecision decideWith(Goal goal) {
        return ability(id -> Optional.empty()).decide(new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                return null;
            }
        });
    }

    @Test
    void 缺目标一次说清() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decideWith(new Goal("maicraft:gather", null, null,
                        ability(null).spec().paramSpecs().parse(new JsonObject()).params(), null, List.of(), null)));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
    }

    @Test
    void 失效的观察编号以目标消失结束() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decide(new Target.Seen("b7")));
        assertEquals(Problem.Kind.TARGET_GONE, finish.result().problem().kind());
        // 一句话结论只带问题的结论句，展开的原因只在问题里一份。
        assertEquals("没有开始采集：" + finish.result().problem().headline(), finish.result().summary());
    }

    @Test
    void 在册的观察编号落成任务输入() {
        var seen = new FakeSeen(Map.of("b7",
                new GatherAbility.SeesTargets.SeenTarget(WorldPosition.here(3, 64, 4), true)));
        Goal goal = new Goal("maicraft:gather", null, new Target.Seen("b7"),
                ability(null).spec().paramSpecs().parse(new JsonObject()).params(), null, List.of(), null);
        StepDecision decision = ability(seen).decide(stepOf(goal));
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decision);
        GatherSpot spot = assertInstanceOf(GatherSpot.class, run.input());
        assertEquals(3, spot.at().x());
        assertEquals(false, spot.drop(), "b 开头是方块，不是掉落物");
        assertTrue(spot.named(), "观察编号点名的目标，挖时点名即许可");
    }

    @Test
    void 点名的展示框落成打一下的任务输入() {
        var seen = new FakeSeen(Map.of("e4",
                new GatherAbility.SeesTargets.SeenTarget(WorldPosition.here(3, 70, 4), false, 42)));
        Goal goal = new Goal("maicraft:gather", null, new Target.Seen("e4"),
                ability(null).spec().paramSpecs().parse(new JsonObject()).params(), null, List.of(), null);
        GatherSpot spot = assertInstanceOf(GatherSpot.class,
                assertInstanceOf(StepDecision.Run.class, ability(seen).decide(stepOf(goal))).input());
        assertTrue(spot.frame(), "展示框打一下，不当掉落物捡");
        assertEquals(42, spot.itemFrame());
        assertTrue(spot.describe().contains("展示框"), spot.describe());
    }

    @Test
    void 点名非掉落物也非展示框的实体直接拒绝不当成掉落物捡() {
        // 生物、载具或强力胶这类非掉落物实体编号传给 gather 时当场按参数不适用拒绝，不走过去空等捡掉落物。
        var seen = new FakeSeen(Map.of("e6",
                new GatherAbility.SeesTargets.SeenTarget(WorldPosition.here(3, 64, 4), false, false, -1)));
        Goal goal = new Goal("maicraft:gather", null, new Target.Seen("e6"),
                ability(null).spec().paramSpecs().parse(new JsonObject()).params(), null, List.of(), null);
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, ability(seen).decide(stepOf(goal)));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("不是可挖的方块"), finish.result().problem().message());
    }

    @Test
    void 被自动拾取进包的掉落物按达成收场() {
        // 投喂现场：玩家把物品扔在脚边，原版自动拾取把实体吸进背包并移除；gather 再点名时
        // 目标物品已经在包里——按达成收场，不再把它错报成"不是掉落物"让人再扔一次。
        var seen = new FakeSeen(Map.of("e5", new GatherAbility.SeesTargets.SeenTarget(
                WorldPosition.here(0, 64, 0), false, false, -1, true, "minecraft:charcoal")));
        StepDecision decision = decideSeen("e5", null, seen, backpackWith("minecraft:charcoal", "1"));
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        assertEquals(TaskResult.Status.DONE, finish.result().status());
        assertTrue(finish.result().summary().contains("已经在身上"), finish.result().summary());
        assertTrue(finish.result().summary().contains("minecraft:charcoal"), finish.result().summary());
    }

    @Test
    void 进包确认优先认目标参数点名要的东西() {
        // LLM 点名 item 参数时按参数确认：登记时装的是煤、点名要的是铁——包里有铁才算达成，只有煤不算。
        var seen = new FakeSeen(Map.of("e5", new GatherAbility.SeesTargets.SeenTarget(
                WorldPosition.here(0, 64, 0), false, false, -1, true, "minecraft:charcoal")));
        StepDecision.Finish miss = assertInstanceOf(StepDecision.Finish.class,
                decideSeen("e5", "minecraft:iron_ingot", seen, backpackWith("minecraft:charcoal", "4")));
        assertEquals(Problem.Kind.TARGET_GONE, miss.result().problem().kind());
        StepDecision.Finish hit = assertInstanceOf(StepDecision.Finish.class,
                decideSeen("e5", "minecraft:iron_ingot", seen,
                        backpackWith("minecraft:charcoal", "4", "minecraft:iron_ingot", "2")));
        assertEquals(TaskResult.Status.DONE, hit.result().status());
        assertTrue(hit.result().summary().contains("minecraft:iron_ingot"), hit.result().summary());
    }

    @Test
    void 登记过的掉落物核对不到时报目标不在了() {
        // 登记时是掉落物、物品也没进包（被别人捡走、掉进岩浆）：按"目标不在了"如实收场，
        // 不再坍缩成"不是掉落物"的分类错误。
        var seen = new FakeSeen(Map.of("e5", new GatherAbility.SeesTargets.SeenTarget(
                WorldPosition.here(0, 64, 0), false, false, -1, true, "minecraft:charcoal")));
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decideSeen("e5", null, seen, emptyBackpack()));
        assertEquals(Problem.Kind.TARGET_GONE, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("可能已经被捡起"),
                finish.result().problem().message());
    }

    @Test
    void 还在地上的掉落物照常走拾取不因身上有同类货提前收场() {
        // 掉落物还在地上：就算包里早有同类物品，也照常走过去让原版结算拾取，不冒充目标达成。
        var seen = new FakeSeen(Map.of("e5", new GatherAbility.SeesTargets.SeenTarget(
                WorldPosition.here(2, 64, 3), false, true, -1)));
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class,
                decideSeen("e5", null, seen, backpackWith("minecraft:charcoal", "9")));
        assertInstanceOf(GatherSpot.class, run.input());
    }

    @Test
    void 给了负高度的坐标照常落成一格() {
        // 深板岩层、钻石层在 0 以下：负高度是合法的一格，不能当成"找不到顶面"拒掉。
        Target.Position deep = new Target.Position(10, -58, 10, null);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(deep));
        GatherSpot spot = assertInstanceOf(GatherSpot.class, run.input());
        assertEquals(-58, spot.at().y());
        assertFalse(spot.named(), "按坐标给的走完整的保护检查");
    }

    @Test
    void 缺高度的位置找柱列顶面() {
        Target.Position low = new Target.Position(10, null, 10, null);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(low));
        GatherSpot spot = assertInstanceOf(GatherSpot.class, run.input());
        assertEquals(64, spot.at().y());
    }

    @Test
    void 不认的目标对象一次说清() {
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                decide(new Target.Player("Steve")));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
        assertTrue(finish.result().problem().message().contains("seen"));
    }

    private StepContext stepOf(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                return null;
            }
        };
    }

    // 观察编号目标的一次决定：item 参数给了就带进目标，现场与背包用测试给的替身。
    private StepDecision decideSeen(String seenId, String itemParam, GatherAbility.SeesTargets seen,
            BackpackView backpack) {
        JsonObject params = new JsonObject();
        if (itemParam != null) params.addProperty("item", itemParam);
        ParseResult parsed = ability(null).spec().paramSpecs().parse(params);
        if (parsed.params() == null) {
            throw new IllegalArgumentException("测试参数没过参数规格：" + parsed.errors());
        }
        Goal goal = new Goal("maicraft:gather", null, new Target.Seen(seenId), parsed.params(), null, List.of(), null);
        return ability(seen, backpack).decide(stepOf(goal));
    }

    // 带几格物品的背包替身：参数按"物品 ID、件数"成对给，一格一堆。
    private static BackpackView backpackWith(String... itemIdAndCount) {
        if (itemIdAndCount.length % 2 != 0) {
            throw new IllegalArgumentException("物品 ID 和件数要成对给");
        }
        List<BackpackStack> stacks = new ArrayList<>();
        for (int i = 0; i < itemIdAndCount.length; i += 2) {
            stacks.add(new BackpackStack(itemIdAndCount[i], Integer.parseInt(itemIdAndCount[i + 1]),
                    64, false, false, false, true));
        }
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.copyOf(stacks);
            }

            @Override public int usedSlots() {
                return stacks.size();
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
    }

    private static PermissionCheck permission() {
        return new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), "self"),
                entityId -> Optional.empty());
    }

    private static BackpackView emptyBackpack() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return List.of();
            }

            @Override public int usedSlots() {
                return 0;
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
    }
}
