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
        // 场景 A：原版常见破坏性工具标签（#minecraft:axes / #minecraft:hoes / #minecraft:shovels）配纯坐标提前拦截。
        for (String tag : List.of("#minecraft:axes", "#minecraft:hoes", "#minecraft:shovels")) {
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
                    }, FarmlandIrrigation.WaterGrid.EMPTY);

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
