// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.permission.FarmlandIrrigation;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.ReadsStructureProtection;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.permission.StructureProtection;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * use 能力的结构保护与流动水改舀保护重检测试：
 * 覆盖纯坐标直指（POINTED）拦截 vs 观察编号（b#）放行、非破坏性交互（开门/按按钮/开箱/往营火放食物/点营火）绝不拦截，
 * 以及空桶点流动水追溯源格后重跑圈地与结构保护检查。
 */
class UseStructureProtectionTest {

    private static final String DIM = "minecraft:overworld";
    private static final BlockPos LOG_WALL = new BlockPos(10, 64, 10);
    private static final BlockPos CAMPFIRE = new BlockPos(11, 64, 10);
    private static final BlockPos DOOR = new BlockPos(12, 64, 10);
    private static final BlockPos CHEST = new BlockPos(13, 64, 10);
    private static final BlockPos FLOWING_OUTSIDE = new BlockPos(20, 64, 20);
    private static final BlockPos PROTECTED_SOURCE = new BlockPos(21, 64, 20);
    private static final BlockPos SAFE_SOURCE = new BlockPos(22, 64, 20);

    @Test
    void 结构保护判断_只拦破坏与舀流体_开门开箱放食物与点营火一律放行() {
        WorldPosition wallPos = pos(LOG_WALL);
        WorldPosition firePos = pos(CAMPFIRE);
        WorldPosition doorPos = pos(DOOR);
        WorldPosition chestPos = pos(CHEST);
        WorldPosition waterPos = pos(PROTECTED_SOURCE);

        ReadsStructureProtection structures = new ReadsStructureProtection() {
            @Override public StructureProtection blockAt(WorldPosition p) {
                if (p.equals(wallPos) || p.equals(firePos) || p.equals(doorPos) || p.equals(chestPos)) {
                    return StructureProtection.building("森林木屋");
                }
                return StructureProtection.NONE;
            }
            @Override public StructureProtection fluidAt(WorldPosition p) {
                if (p.equals(waterPos)) return StructureProtection.machine("洗矿机");
                return blockAt(p);
            }
        };
        Protection guard = new Protection((d, x, y, z) -> Optional.empty(), List::of, name -> Optional.empty(),
                "self", SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id,
                structures, FarmlandIrrigation.WaterGrid.EMPTY);

        // 非破坏性普通交互：绝不拦截。
        assertTrue(LiveOthersThings.structureGuard(guard, null,
                ResolvedTarget.block(DOOR, "minecraft:oak_door", "门"), doorPos).isEmpty(), "空手开门放行");
        assertTrue(LiveOthersThings.structureGuard(guard, null,
                ResolvedTarget.block(CHEST, "minecraft:chest", "箱子"), chestPos).isEmpty(), "空手开箱放行");
        assertTrue(LiveOthersThings.structureGuard(guard, "minecraft:potato",
                ResolvedTarget.block(CAMPFIRE, "minecraft:campfire", "营火"), firePos).isEmpty(), "往营火放食物放行");
        assertTrue(LiveOthersThings.structureGuard(guard, "minecraft:flint_and_steel",
                ResolvedTarget.block(CAMPFIRE, "minecraft:campfire", "营火"), firePos).isEmpty(), "打火石点营火放行");

        // 破坏性/改形/舀取流体操作：拦截并说明所属结构。
        assertTrue(LiveOthersThings.structureGuard(guard, "minecraft:iron_axe",
                ResolvedTarget.block(LOG_WALL, "minecraft:oak_log", "原木墙"), wallPos)
                .orElse("").contains("森林木屋"), "拿斧给建筑原木墙去皮应拦截");
        assertTrue(LiveOthersThings.structureGuard(guard, "minecraft:flint_and_steel",
                ResolvedTarget.block(LOG_WALL, "minecraft:oak_log", "原木墙"), wallPos)
                .orElse("").contains("森林木屋"), "拿打火石点木屋原木墙应拦截");
        assertTrue(LiveOthersThings.structureGuard(guard, "minecraft:bucket",
                ResolvedTarget.block(PROTECTED_SOURCE, "minecraft:water", "水"), waterPos)
                .orElse("").contains("洗矿机"), "拿空桶舀机器水源应拦截");
    }

    @Test
    void 纯坐标直指受结构保护的破坏或舀取被拦截并引导用观察编号_用b号点名放行() {
        TestRig rig = new TestRig();
        rig.blocks.put(LOG_WALL, "minecraft:oak_log");
        rig.blocks.put(CAMPFIRE, "minecraft:campfire");
        rig.structureBlocks.put(LOG_WALL, StructureProtection.building("森林木屋"));
        rig.structureBlocks.put(CAMPFIRE, StructureProtection.building("森林木屋"));
        rig.seen.put("b1", new ResolvesSeen.Resolved(pos(LOG_WALL), "minecraft:oak_log", null));

        // 纯坐标拿斧头直指建筑原木墙：拦截并引导先 observe 用 b# 点名。
        TaskResult blocked = rig.run(new UseInput(new Target.Position(10, 64, 10, DIM), "minecraft:iron_axe",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, blocked.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, blocked.problem().kind());
        assertTrue(blocked.problem().message().contains("森林木屋"), blocked.problem().message());
        assertTrue(blocked.problem().suggestion().contains("observe") && blocked.problem().suggestion().contains("b#"),
                blocked.problem().suggestion());

        // 同坐标拿生土豆往建筑里的营火放食物：非破坏性交互，放行！
        TaskResult campfirePut = rig.run(new UseInput(new Target.Position(11, 64, 10, DIM), "minecraft:potato",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, campfirePut.status(), campfirePut.summary());

        // 换成观察编号 b1 点名建筑原木墙：点名即显式许可，放行！
        TaskResult named = rig.run(new UseInput(new Target.Seen("b1"), "minecraft:iron_axe",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, named.status(), named.summary());
    }

    @Test
    void 传工具标签配纯坐标拦截建筑破坏_自定义标签在备手后复核拦截_用b号点名放行() {
        // 场景 A：原版与通用常见破坏性工具标签配纯坐标提前拦截（含单复数对称形式）。
        for (String tag : List.of("#minecraft:axes", "#minecraft:hoes", "#minecraft:shovels",
                "#c:tools/axes", "#c:tools/hoes", "#c:tools/shovels")) {
            TestRig rig = new TestRig();
            rig.blocks.put(LOG_WALL, "minecraft:oak_log");
            rig.structureBlocks.put(LOG_WALL, StructureProtection.building("森林木屋"));
            rig.resolvedHandItem = "minecraft:iron_axe";

            TaskResult blocked = rig.run(new UseInput(new Target.Position(10, 64, 10, DIM), tag,
                    null, null, 1, 32, List.of(), Permissions.DEFAULT));
            assertEquals(TaskResult.Status.FAILED, blocked.status(), tag + " 配纯坐标应被拦截");
            assertEquals(Problem.Kind.NEED_APPROVAL, blocked.problem().kind());
            assertTrue(blocked.problem().message().contains("森林木屋"), blocked.problem().message());
            assertEquals(0, rig.interactedCount, tag + " 提前拦截后不应出手");
        }

        // 场景 B：任意自定义工具标签（如 #mod:custom_axes），备手前认不出手势，但备手拿到真斧头后在 handReady 二次复核拦截。
        TestRig customTagRig = new TestRig();
        customTagRig.blocks.put(LOG_WALL, "minecraft:oak_log");
        customTagRig.structureBlocks.put(LOG_WALL, StructureProtection.building("森林木屋"));
        customTagRig.resolvedHandItem = "minecraft:diamond_axe";

        TaskResult blockedCustom = customTagRig.run(new UseInput(new Target.Position(10, 64, 10, DIM),
                "#mod:custom_axes", null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, blockedCustom.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, blockedCustom.problem().kind());
        assertTrue(blockedCustom.problem().message().contains("森林木屋"), blockedCustom.problem().message());
        assertEquals(0, customTagRig.interactedCount, "备手后复核拦截绝不能靠近或出手");

        // 场景 C：同样传工具标签（含原版与自定义标签），改为用观察编号 b# 点名目标 -> 点名即许可，放行！
        for (String tag : List.of("#minecraft:axes", "#mod:custom_axes")) {
            TestRig namedRig = new TestRig();
            namedRig.blocks.put(LOG_WALL, "minecraft:oak_log");
            namedRig.structureBlocks.put(LOG_WALL, StructureProtection.building("森林木屋"));
            namedRig.seen.put("b1", new ResolvesSeen.Resolved(pos(LOG_WALL), "minecraft:oak_log", null));
            namedRig.resolvedHandItem = "minecraft:diamond_axe";

            TaskResult allowed = namedRig.run(new UseInput(new Target.Seen("b1"), tag,
                    null, null, 1, 32, List.of(), Permissions.DEFAULT));
            assertEquals(TaskResult.Status.DONE, allowed.status(), tag + " 用 b# 点名应放行：" + allowed.summary());
            assertEquals(1, namedRig.interactedCount);
        }
    }

    @Test
    void 空桶点流动水改舀源格时重检圈地与结构保护_受保护源格拒绝改舀_安全源格正常改舀() {
        // 场景 A：流动水在建筑外且用 b# 点名，但连通源格在机器/建筑结构保护区里 -> 改舀后重检必须拦截！
        TestRig structureRig = new TestRig();
        structureRig.blocks.put(FLOWING_OUTSIDE, "minecraft:water");
        structureRig.blocks.put(PROTECTED_SOURCE, "minecraft:water");
        structureRig.fluids.put(FLOWING_OUTSIDE, UseSeams.ReadsWorld.Fluid.FLOWING);
        structureRig.fluids.put(PROTECTED_SOURCE, UseSeams.ReadsWorld.Fluid.SOURCE);
        structureRig.nearestSourceResult = Optional.of(PROTECTED_SOURCE);
        structureRig.structureFluids.put(PROTECTED_SOURCE, StructureProtection.machine("鼓风洗矿机"));
        structureRig.seen.put("b9", new ResolvesSeen.Resolved(pos(FLOWING_OUTSIDE), "minecraft:water", null));

        TaskResult refusedStructure = structureRig.run(new UseInput(new Target.Seen("b9"), "minecraft:bucket",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, refusedStructure.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, refusedStructure.problem().kind());
        assertTrue(refusedStructure.problem().message().contains("鼓风洗矿机"), refusedStructure.problem().message());
        assertTrue(refusedStructure.problem().suggestion().contains("这格源格"), refusedStructure.problem().suggestion());
        assertEquals(0, structureRig.interactedCount, "被结构保护拦截后绝不能靠近或出手舀水");

        // 场景 B：追溯到的源格在圈地（RememberedRegion）里 -> 改舀后重检同样必须拦截！
        TestRig regionRig = new TestRig();
        regionRig.blocks.put(FLOWING_OUTSIDE, "minecraft:water");
        regionRig.blocks.put(PROTECTED_SOURCE, "minecraft:water");
        regionRig.fluids.put(FLOWING_OUTSIDE, UseSeams.ReadsWorld.Fluid.FLOWING);
        regionRig.fluids.put(PROTECTED_SOURCE, UseSeams.ReadsWorld.Fluid.SOURCE);
        regionRig.nearestSourceResult = Optional.of(PROTECTED_SOURCE);
        regionRig.regions = List.of(new RememberedRegion("邻居农场", pos(PROTECTED_SOURCE), 2));
        regionRig.seen.put("b9", new ResolvesSeen.Resolved(pos(FLOWING_OUTSIDE), "minecraft:water", null));

        TaskResult refusedRegion = regionRig.run(new UseInput(new Target.Seen("b9"), "minecraft:bucket",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, refusedRegion.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, refusedRegion.problem().kind());
        assertTrue(refusedRegion.problem().message().contains("邻居农场"), refusedRegion.problem().message());
        assertEquals(0, regionRig.interactedCount);

        // 场景 C：追溯到的源格是野外不受保护的水源 -> 正常改舀成功！
        TestRig safeRig = new TestRig();
        safeRig.blocks.put(FLOWING_OUTSIDE, "minecraft:water");
        safeRig.blocks.put(SAFE_SOURCE, "minecraft:water");
        safeRig.fluids.put(FLOWING_OUTSIDE, UseSeams.ReadsWorld.Fluid.FLOWING);
        safeRig.fluids.put(SAFE_SOURCE, UseSeams.ReadsWorld.Fluid.SOURCE);
        safeRig.nearestSourceResult = Optional.of(SAFE_SOURCE);

        TaskResult ok = safeRig.run(new UseInput(new Target.Position(20, 64, 20, DIM), "minecraft:bucket",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, ok.status(), ok.summary());
        assertEquals(SAFE_SOURCE, safeRig.lastInteractedCell);
    }

    @Test
    void 建筑内持锄头补耕泥土类方块默认放行_持锹或改原木仍拦截且拒绝文案含b号点名提示() {
        // 建筑内被踩坏的菜地：dirt / grass_block / coarse_dirt / dirt_path 持具体锄头或锄标签都默认放行。
        for (String soil : List.of("minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:dirt_path")) {
            TestRig hoeRig = new TestRig();
            hoeRig.blocks.put(LOG_WALL, soil);
            hoeRig.structureBlocks.put(LOG_WALL, StructureProtection.building("温室菜园"));

            TaskResult withHoe = hoeRig.run(new UseInput(new Target.Position(10, 64, 10, DIM), "minecraft:iron_hoe",
                    null, null, 1, 32, List.of(), Permissions.DEFAULT));
            assertEquals(TaskResult.Status.DONE, withHoe.status(), soil + " 持锄头补耕应放行：" + withHoe.summary());
            assertEquals(1, hoeRig.interactedCount);

            // 传 #minecraft:hoes 或自定义锄标签且备手拿到铁锄：guardStructureAfterHand 复核同样放行。
            TestRig tagRig = new TestRig();
            tagRig.blocks.put(LOG_WALL, soil);
            tagRig.structureBlocks.put(LOG_WALL, StructureProtection.building("温室菜园"));
            tagRig.resolvedHandItem = "minecraft:iron_hoe";

            TaskResult withCustomTag = tagRig.run(new UseInput(new Target.Position(10, 64, 10, DIM), "#mod:custom_hoes",
                    null, null, 1, 32, List.of(), Permissions.DEFAULT));
            assertEquals(TaskResult.Status.DONE, withCustomTag.status(), soil + " 备手拿到锄头复核应放行");

            // 复数标签 #c:tools/hoes 同样对称放行补耕。
            TestRig pluralHoeRig = new TestRig();
            pluralHoeRig.blocks.put(LOG_WALL, soil);
            pluralHoeRig.structureBlocks.put(LOG_WALL, StructureProtection.building("温室菜园"));
            pluralHoeRig.resolvedHandItem = "minecraft:iron_hoe";
            assertEquals(TaskResult.Status.DONE, pluralHoeRig.run(new UseInput(new Target.Position(10, 64, 10, DIM),
                    "#c:tools/hoes", null, null, 1, 32, List.of(), Permissions.DEFAULT)).status());
        }

        // 持锹对建筑内草方块压土径：不属于农田补耕，仍拦截，且 message 末尾含「若确需操作，请先 observe 用 b# 点名」。
        TestRig shovelRig = new TestRig();
        shovelRig.blocks.put(LOG_WALL, "minecraft:grass_block");
        shovelRig.structureBlocks.put(LOG_WALL, StructureProtection.building("温室菜园"));
        TaskResult shovelBlocked = shovelRig.run(new UseInput(new Target.Position(10, 64, 10, DIM), "minecraft:iron_shovel",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, shovelBlocked.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, shovelBlocked.problem().kind());
        assertTrue(shovelBlocked.problem().message().endsWith("若确需操作，请先 observe 用 b# 点名"),
                shovelBlocked.problem().message());

        // 备手后二次复核拦截（自定义斧标签拿真斧头对建筑原木墙）：message 末尾同样含引导提示。
        TestRig afterHandRig = new TestRig();
        afterHandRig.blocks.put(LOG_WALL, "minecraft:oak_log");
        afterHandRig.structureBlocks.put(LOG_WALL, StructureProtection.building("森林木屋"));
        afterHandRig.resolvedHandItem = "minecraft:diamond_axe";
        TaskResult afterHandBlocked = afterHandRig.run(new UseInput(new Target.Position(10, 64, 10, DIM), "#mod:custom_axes",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, afterHandBlocked.status());
        assertTrue(afterHandBlocked.problem().message().endsWith("若确需操作，请先 observe 用 b# 点名"),
                afterHandBlocked.problem().message());
    }

    @Test
    void 空桶点水车旁流动水自动改舀追溯到水车驱动水源时即使未建档也被拦截() {
        TestRig wheelRig = new TestRig();
        wheelRig.blocks.put(FLOWING_OUTSIDE, "minecraft:water");
        wheelRig.blocks.put(PROTECTED_SOURCE, "minecraft:water");
        wheelRig.fluids.put(FLOWING_OUTSIDE, UseSeams.ReadsWorld.Fluid.FLOWING);
        wheelRig.fluids.put(PROTECTED_SOURCE, UseSeams.ReadsWorld.Fluid.SOURCE);
        wheelRig.nearestSourceResult = Optional.of(PROTECTED_SOURCE);
        // 在 PROTECTED_SOURCE 正下方放一台 Z 轴小水车（未建任何机器档案）。
        BlockPos wheelBelow = PROTECTED_SOURCE.below();
        wheelRig.waterGrid = new FarmlandIrrigation.WaterGrid() {
            @Override public String dimension() { return DIM; }
            @Override public boolean isWaterSource(BlockPos pos) { return pos.equals(PROTECTED_SOURCE); }
            @Override public boolean isSolidFloor(BlockPos pos) { return false; }
            @Override public boolean isFarmland(BlockPos pos) { return false; }
            @Override public FarmlandIrrigation.WaterWheelAt waterWheelAt(BlockPos pos) {
                return pos.equals(wheelBelow)
                        ? FarmlandIrrigation.WaterWheelAt.small(Direction.Axis.Z)
                        : FarmlandIrrigation.WaterWheelAt.NONE;
            }
        };

        TaskResult refused = wheelRig.run(new UseInput(new Target.Position(20, 64, 20, DIM), "minecraft:bucket",
                null, null, 1, 32, List.of(), Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, refused.status());
        assertEquals(Problem.Kind.NEED_APPROVAL, refused.problem().kind());
        assertTrue(refused.problem().message().contains("水车"), refused.problem().message());
        assertEquals(0, wheelRig.interactedCount);
    }

    private static WorldPosition pos(BlockPos cell) {
        return new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), DIM);
    }

    /** 最小离线支架：直连真实 Protection + LiveOthersThings.structureGuard 验证完整 UseTask 与 UseTargets 链路。 */
    private static final class TestRig {
        final Map<BlockPos, String> blocks = new HashMap<>();
        final Map<BlockPos, UseSeams.ReadsWorld.Fluid> fluids = new HashMap<>();
        final Map<String, ResolvesSeen.Resolved> seen = new HashMap<>();
        final Map<BlockPos, StructureProtection> structureBlocks = new HashMap<>();
        final Map<BlockPos, StructureProtection> structureFluids = new HashMap<>();
        List<RememberedRegion> regions = List.of();
        FarmlandIrrigation.WaterGrid waterGrid = FarmlandIrrigation.WaterGrid.EMPTY;
        Optional<BlockPos> nearestSourceResult = Optional.empty();
        /** 备手后实际拿在主手上的具体物品 ID（用于模拟传工具标签后换上具体工具）。 */
        String resolvedHandItem;
        UseSeams.ReadsWorld.Held held;
        int interactedCount;
        BlockPos lastInteractedCell;

        TaskResult run(UseInput input) {
            String actualItem = resolvedHandItem != null ? resolvedHandItem : input.item();
            held = actualItem == null ? null : new UseSeams.ReadsWorld.Held(actualItem, 1);
            Protection protection = new Protection((d, x, y, z) -> Optional.empty(), () -> regions,
                    name -> Optional.empty(), "self", SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id,
                    new ReadsStructureProtection() {
                        @Override public StructureProtection blockAt(WorldPosition p) {
                            return structureBlocks.getOrDefault(new BlockPos(p.x(), p.y(), p.z()), StructureProtection.NONE);
                        }
                        @Override public StructureProtection fluidAt(WorldPosition p) {
                            BlockPos cell = new BlockPos(p.x(), p.y(), p.z());
                            return structureFluids.getOrDefault(cell, blockAt(p));
                        }
                    }, waterGrid);

            UseSeams.ReadsWorld world = new UseSeams.ReadsWorld() {
                @Override public String dimension() { return DIM; }
                @Override public BlockPos feet() { return BlockPos.ZERO; }
                @Override public boolean loaded(BlockPos cell) { return true; }
                @Override public Optional<String> blockId(BlockPos cell) { return Optional.ofNullable(blocks.get(cell)); }
                @Override public boolean blockIs(BlockPos cell, String blockOrTag) { return blockOrTag.equals(blocks.get(cell)); }
                @Override public Optional<BlockPos> ground(int x, int z) { return Optional.empty(); }
                @Override public Fluid fluid(BlockPos cell) { return fluids.getOrDefault(cell, Fluid.NONE); }
                @Override public Optional<BlockPos> nearestSource(BlockPos near, int radius) { return nearestSourceResult; }
                @Override public Sign sign(BlockPos cell) { return Sign.NOT_A_SIGN; }
                @Override public Optional<SeenEntity> entity(int entityId) { return Optional.empty(); }
                @Override public boolean riding(int entityId) { return false; }
                @Override public Optional<Held> heldItem() { return Optional.ofNullable(held); }
                @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign) {
                    return Optional.of(ApproachTarget.ofBlock(target.cell()));
                }
            };
            UseSeams.OthersThings others = new UseSeams.OthersThings() {
                @Override public boolean changesWorld(String heldItem, ResolvedTarget target) { return true; }
                @Override public boolean someoneElses(ResolvedTarget target, Set<String> landmarks) {
                    return protection.protectedBy(pos(target.cell()), target.typeId(), landmarks).protects();
                }
                @Override public String whyOthers(ResolvedTarget target, Set<String> landmarks) {
                    return protection.describe(protection.protectedBy(pos(target.cell()), target.typeId(), landmarks));
                }
                @Override public Optional<String> structureGuardReason(String heldItem, ResolvedTarget target) {
                    return LiveOthersThings.structureGuard(protection, heldItem, target, pos(target.cell()));
                }
            };
            UseSeams.BuildsInteraction interactions = (target, writesSign) -> {
                interactedCount++;
                lastInteractedCell = target == null ? null : target.cell();
                Action instant = new Action() {
                    @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                    @Override public String describe() { return "交互"; }
                };
                return new UseSeams.Built.Ready(instant, () -> InteractionResult.applied("生效"),
                        ItemUseAim.gestureOf(held == null ? null : held.itemId()), held, 0, null);
            };
            BringsPlayerClose close = (target, permissions) -> new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "靠近"; }
            };
            UseServices services = new UseServices(world, interactions, close, item -> new UseSeams.HandPlan.Ready(),
                    null, id -> Optional.ofNullable(seen.get(id)), null, null, null, null, null, null, null, others);
            UseTask task = new UseTask(input, services);
            for (int tick = 1; tick <= 60; tick++) {
                TickResult r = task.tick(TickContext.of(tick, null));
                if (r instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("任务 60 刻内未结束");
        }
    }
}
