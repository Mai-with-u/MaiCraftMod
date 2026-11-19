// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code use} 对线缆方块上的 ME 终端面板对准 {@link ApproachTarget#ofBlockPart}，
 * 以及点开终端界面后读取真实网络存货而不写入假空箱子世界记忆的测试。
 */
class Ae2UseTaskTest {

    private static final BlockPos CABLE_CELL = new BlockPos(5, 64, -2);
    private static final AABB NORTH_PANEL = new AABB(5.125, 64.125, -2.0, 5.875, 64.875, -1.875);
    private static final String OVERWORLD = "minecraft:overworld";

    @TempDir
    Path temp;

    @Test
    void liveUseWorldTargetsTerminalPanelOnlyWhenEmptyHandedAndNotSneaking() {
        NetworkStorage storage = new PanelOnlyStorage();
        LiveUseWorld liveWorld = new LiveUseWorld(() -> null, () -> Optional.of(storage));
        ResolvedTarget cable = ResolvedTarget.block(CABLE_CELL, "ae2:cable_bus", "线缆").facing(Direction.NORTH);

        ApproachTarget panelTarget = liveWorld.approachTarget(cable, false, false).orElseThrow();
        assertEquals(NORTH_PANEL, panelTarget.bounds(), "空手且不潜行时按终端面板包围盒挑站位");

        ApproachTarget sneakTarget = liveWorld.approachTarget(cable, false, true).orElseThrow();
        assertEquals(ApproachTarget.ofBlockFace(CABLE_CELL, Direction.NORTH).bounds(), sneakTarget.bounds(),
                "潜行右键时不改走终端面板");
    }

    @Test
    void emptyHandUseOnCableBusTargetsTerminalPanelAndReadsNetworkStockWithoutFakeContainerMemory() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("use-ae2.sqlite")), "1".repeat(64));
        LiveUseWorld panelApproach = new LiveUseWorld(() -> null, () -> Optional.of(new PanelOnlyStorage()));
        StubCableWorld world = new StubCableWorld(panelApproach);
        CapturingClose close = new CapturingClose();
        StubCableInteractions interactions = new StubCableInteractions();
        StubTerminalMenus menus = new StubTerminalMenus(
                "minecraft:cobblestone ×2048、minecraft:redstone ×512（共 2 种物品）");

        UseServices services = new UseServices(world, interactions, close,
                item -> new UseSeams.HandPlan.Ready(), null, null, null, null, null, null, null,
                menus, memory, new UnprotectedOthers(), null, new FacilityKinds(FakeBlockTags.vanilla()), noSneak());
        UseInput input = new UseInput(
                new Target.Position(CABLE_CELL.getX(), CABLE_CELL.getY(), CABLE_CELL.getZ(), null),
                null, "ae2:cable_bus", null, 1, 16, List.of(), Permissions.DEFAULT, Direction.NORTH, 0, false);

        TaskResult result = runToCompletion(new UseTask(input, services));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(NORTH_PANEL, close.lastTarget.bounds(), "靠近阶段对准线缆上的终端面板而非整格");
        assertEquals(Boolean.FALSE, interactions.lastSneak);
        assertEquals(CABLE_CELL, menus.lastClickedCell);
        assertEquals(Direction.NORTH, menus.lastClickedFace);

        UseDetails details = assertInstanceOf(UseDetails.class, result.details());
        assertEquals("minecraft:cobblestone ×2048、minecraft:redstone ×512（共 2 种物品）", details.opened());
        assertNull(details.whose(), "AE2 终端不是方块箱子，不标容器归属");
        assertTrue(memory.allRecords().isEmpty(), "不往世界记忆容器表写入 ae2:cable_bus 空箱子假记录");
    }

    @Test
    void wirelessTerminalOpenedInAirWritesNetworkSummaryIntoOpenedDetails() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("wireless.sqlite")), "2".repeat(64));
        StubCableWorld world = new StubCableWorld(new LiveUseWorld(() -> null));
        CapturingClose close = new CapturingClose();
        StubCableInteractions interactions = new StubCableInteractions();
        StubTerminalMenus menus = new StubTerminalMenus("没连上 ME 网络（超出无线范围）");

        UseServices services = new UseServices(world, interactions, close,
                item -> new UseSeams.HandPlan.Ready(), null, null, null, null, null, null, null,
                menus, memory, new UnprotectedOthers(), null, new FacilityKinds(FakeBlockTags.vanilla()), noSneak());
        UseInput input = new UseInput(null, "ae2:wireless_terminal", null, null, 1, 16, List.of(),
                Permissions.DEFAULT, null, 0, false);

        TaskResult result = runToCompletion(new UseTask(input, services));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertNull(menus.lastClickedCell, "手持无线终端对着空气用时没有点击方块坐标");
        UseDetails details = assertInstanceOf(UseDetails.class, result.details());
        assertEquals("没连上 ME 网络（超出无线范围）", details.opened());
        assertEquals("air", details.usedOn());
        assertTrue(memory.allRecords().isEmpty());
    }

    @Test
    void sneakDisassemblyIntoBackpackReportsGainedItemsInsteadOfPlacedIntoProcessor() {
        // 复现空存储元件潜行右键驱动器被拆解成外壳 + 4K 组件进背包：严禁误报"放进去了，不确认会不会做出东西"。
        BlockPos driveCell = new BlockPos(-362, 65, 41);
        Map<String, Integer> backpack = new LinkedHashMap<>();
        backpack.put("ae2:item_storage_cell_4k", 1);
        UseSeams.ReadsWorld.Held[] hand = {new UseSeams.ReadsWorld.Held("ae2:item_storage_cell_4k", 1)};
        UseSeams.ReadsWorld world = new DriveBlockWorld(driveCell, hand, backpack);
        UseSeams.BuildsInteraction interactions = (target, writesSign) -> {
            InteractionResult applied = new InteractionResult(InteractionVerdict.APPLIED, "潜行右键生效");
            Action action = new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    // 模拟 AE2 空元件拆解：主手元件消失，背包新增外壳与 4K 存储组件。
                    hand[0] = null;
                    backpack.clear();
                    backpack.put("ae2:item_cell_housing", 1);
                    backpack.put("ae2:cell_component_4k", 1);
                    return ActionStatus.done();
                }
                @Override public String describe() { return "潜行右键驱动器"; }
            };
            return new UseSeams.Built.Ready(action, () -> applied, ItemUseAim.Gesture.USE_TARGET,
                    hand[0], 0, driveCell);
        };
        UseServices services = new UseServices(world, interactions, new CapturingClose(),
                item -> new UseSeams.HandPlan.Ready(), null, null, null, null, null, null, null,
                null, null, new UnprotectedOthers(), null, new FacilityKinds(FakeBlockTags.vanilla()), noSneak());
        UseInput input = new UseInput(
                new Target.Position(driveCell.getX(), driveCell.getY(), driveCell.getZ(), null),
                "ae2:item_storage_cell_4k", "ae2:drive", null, 1, 16, List.of(), Permissions.DEFAULT, null, 0, true);

        TaskResult result = runToCompletion(new UseTask(input, services));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertFalse(result.summary().contains("放进去了"), "拆解进背包绝不能谎报放进去了：" + result.summary());
        assertTrue(result.summary().contains("ae2:item_cell_housing")
                && result.summary().contains("ae2:cell_component_4k"), result.summary());
        UseDetails details = assertInstanceOf(UseDetails.class, result.details());
        assertNull(details.unrecognizedProcessor(), "拆解进背包不应标为未知加工容器");
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_GAINED
                && "ae2:item_cell_housing".equals(change.what())), result.changes().toString());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_GAINED
                && "ae2:cell_component_4k".equals(change.what())), result.changes().toString());
    }

    @Test
    void useWithStorageCellOnDriveInsertsIntoOpenedMenuAndListsSlots() {
        // 手持 4K 存储元件不潜行点开 AE2 驱动器界面：成功插进空槽、回报 10 槽元件明细，且不写入普通储物箱记忆。
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("drive-insert.sqlite")), "3".repeat(64));
        BlockPos driveCell = new BlockPos(-362, 65, 41);
        Map<String, Integer> backpack = new LinkedHashMap<>();
        backpack.put("ae2:item_storage_cell_4k", 1);
        UseSeams.ReadsWorld.Held[] hand = {new UseSeams.ReadsWorld.Held("ae2:item_storage_cell_4k", 1)};
        UseSeams.ReadsWorld world = new DriveBlockWorld(driveCell, hand, backpack);
        UseSeams.BuildsInteraction interactions = (target, writesSign) -> {
            InteractionResult applied = new InteractionResult(InteractionVerdict.APPLIED, "打开了驱动器界面");
            Action action = new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "右键驱动器"; }
            };
            return new UseSeams.Built.Ready(action, () -> applied, ItemUseAim.Gesture.USE_TARGET,
                    hand[0], 0, driveCell);
        };
        String[] receivedInsert = {null};
        UseSeams.LooksInMenus driveMenus = new UseSeams.LooksInMenus() {
            @Override public Optional<UseSeams.MenuLook> opened(int menuBefore) { return Optional.empty(); }
            @Override public Optional<UseSeams.MenuLook> opened(int menuBefore, BlockPos clickedCell,
                    Direction clickedFace, String insertItem) {
                receivedInsert[0] = insertItem;
                Map<String, Integer> slots = new LinkedHashMap<>();
                slots.put("ae2:item_storage_cell_1k", 1);
                slots.put("ae2:item_storage_cell_4k", 1);
                return Optional.of(new UseSeams.MenuLook() {
                    @Override public Optional<Map<String, Integer>> contents() { return Optional.of(slots); }
                    @Override public Insertion insertion() { return Insertion.INSERTED; }
                    @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                    @Override public String describe() { return "把元件放进驱动器并查看十槽"; }
                });
            }
        };
        UseServices services = new UseServices(world, interactions, new CapturingClose(),
                item -> new UseSeams.HandPlan.Ready(), null, null, null, null, null, null, null,
                driveMenus, memory, new UnprotectedOthers(), null, new FacilityKinds(FakeBlockTags.vanilla()), noSneak());
        UseInput input = new UseInput(
                new Target.Position(driveCell.getX(), driveCell.getY(), driveCell.getZ(), null),
                "ae2:item_storage_cell_4k", "ae2:drive", null, 1, 16, List.of(), Permissions.DEFAULT, null, 0, false);

        TaskResult result = runToCompletion(new UseTask(input, services));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals("ae2:item_storage_cell_4k", receivedInsert[0], "应把手中存储元件传给界面查看器尝试放入空槽");
        assertTrue(result.summary().contains("已把 ae2:item_storage_cell_4k 放进界面"), result.summary());
        assertTrue(result.summary().contains("ae2:item_storage_cell_1k ×1"), result.summary());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_STORED
                && "ae2:item_storage_cell_4k".equals(change.what())), result.changes().toString());
        assertTrue(memory.allRecords().isEmpty(), "驱动器不是通用储物箱，不写进容器表");
    }

    /** 驱动器测试替身世界：指定坐标是带方块实体的 ae2:drive，实时返回可变的手持物与背包计数。 */
    private static final class DriveBlockWorld implements UseSeams.ReadsWorld {
        private final BlockPos cell;
        private final Held[] hand;
        private final Map<String, Integer> backpack;

        DriveBlockWorld(BlockPos cell, Held[] hand, Map<String, Integer> backpack) {
            this.cell = cell;
            this.hand = hand;
            this.backpack = backpack;
        }

        @Override public String dimension() { return OVERWORLD; }
        @Override public BlockPos feet() { return cell.south(2); }
        @Override public boolean loaded(BlockPos pos) { return true; }
        @Override public Optional<String> blockId(BlockPos pos) {
            return pos.equals(cell) ? Optional.of("ae2:drive") : Optional.of("minecraft:air");
        }
        @Override public boolean blockIs(BlockPos pos, String blockOrTag) {
            return pos.equals(cell) && "ae2:drive".equals(blockOrTag);
        }
        @Override public boolean hasBlockEntity(BlockPos pos) { return pos.equals(cell); }
        @Override public Optional<BlockPos> ground(int x, int z) { return Optional.of(new BlockPos(x, 64, z)); }
        @Override public Fluid fluid(BlockPos pos) { return Fluid.NONE; }
        @Override public Optional<BlockPos> nearestSource(BlockPos near, int radius) { return Optional.empty(); }
        @Override public Sign sign(BlockPos pos) { return Sign.NOT_A_SIGN; }
        @Override public Optional<SeenEntity> entity(int entityId) { return Optional.empty(); }
        @Override public boolean riding(int entityId) { return false; }
        @Override public Optional<Held> heldItem() { return Optional.ofNullable(hand[0]); }
        @Override public Map<String, Integer> backpackCounts() { return Map.copyOf(backpack); }
        @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign) {
            return Optional.of(ApproachTarget.ofBlock(cell));
        }
    }

    private static TaskResult runToCompletion(UseTask task) {
        TickContext tick = new TickContext() {
            private long gameTick = 100;
            @Override public long gameTick() { return gameTick++; }
            @Override public PlayerContext player() { return null; }
        };
        task.start(tick);
        for (int i = 0; i < 40; i++) {
            TickResult step = task.tick(tick);
            if (step instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("UseTask 未在预期刻数内完成");
    }

    private static HoldsSneak noSneak() {
        return action -> action;
    }

    private static final class UnprotectedOthers implements UseSeams.OthersThings {
        @Override public boolean changesWorld(String heldItem, ResolvedTarget target) {
            return heldItem != null;
        }

        @Override public boolean someoneElses(ResolvedTarget target, Set<String> protectedLandmarks) {
            return false;
        }
    }

    private static final class PanelOnlyStorage implements NetworkStorage {
        @Override public boolean reachable(TerminalSearch search) { return false; }

        @Override public TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search,
                TaskRecords records) {
            return new TerminalPick.NotFound("测试不用");
        }

        @Override public Optional<AABB> terminalPanelAt(BlockPos cell, Direction preferredSide) {
            if (cell.equals(CABLE_CELL) && (preferredSide == null || preferredSide == Direction.NORTH)) {
                return Optional.of(NORTH_PANEL);
            }
            return Optional.empty();
        }
    }

    private static final class StubCableWorld implements UseSeams.ReadsWorld {
        private final LiveUseWorld panelDelegation;

        StubCableWorld(LiveUseWorld panelDelegation) {
            this.panelDelegation = panelDelegation;
        }

        @Override public String dimension() { return OVERWORLD; }
        @Override public BlockPos feet() { return BlockPos.ZERO; }
        @Override public boolean loaded(BlockPos cell) { return true; }
        @Override public Optional<String> blockId(BlockPos cell) {
            return cell.equals(CABLE_CELL) ? Optional.of("ae2:cable_bus") : Optional.of("minecraft:air");
        }
        @Override public boolean blockIs(BlockPos cell, String blockOrTag) {
            return cell.equals(CABLE_CELL) && "ae2:cable_bus".equals(blockOrTag);
        }
        @Override public Optional<BlockPos> ground(int x, int z) { return Optional.of(new BlockPos(x, 63, z)); }
        @Override public Fluid fluid(BlockPos cell) { return Fluid.NONE; }
        @Override public Optional<BlockPos> nearestSource(BlockPos near, int radius) { return Optional.empty(); }
        @Override public Sign sign(BlockPos cell) { return Sign.NOT_A_SIGN; }
        @Override public Optional<SeenEntity> entity(int entityId) { return Optional.empty(); }
        @Override public boolean riding(int entityId) { return false; }
        @Override public Optional<Held> heldItem() { return Optional.empty(); }

        @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign) {
            return panelDelegation.approachTarget(target, writesSign, false);
        }

        @Override public Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign,
                boolean sneak) {
            return panelDelegation.approachTarget(target, writesSign, sneak);
        }
    }

    private static final class CapturingClose implements BringsPlayerClose {
        ApproachTarget lastTarget;

        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            this.lastTarget = target;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "靠近线缆面板"; }
            };
        }
    }

    private static final class StubCableInteractions implements UseSeams.BuildsInteraction {
        Boolean lastSneak;

        @Override public UseSeams.Built build(ResolvedTarget target, boolean writesSign) {
            return build(target, writesSign, false);
        }

        @Override public UseSeams.Built build(ResolvedTarget target, boolean writesSign, boolean sneak) {
            this.lastSneak = sneak;
            InteractionResult applied = new InteractionResult(InteractionVerdict.APPLIED, "点开了终端界面");
            Action click = new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "点终端面板"; }
            };
            return new UseSeams.Built.Ready(click, () -> applied, ItemUseAim.Gesture.EMPTY_HAND, null, 0, CABLE_CELL);
        }
    }

    private static final class StubTerminalMenus implements UseSeams.LooksInMenus {
        private final String summary;
        BlockPos lastClickedCell;
        Direction lastClickedFace;

        StubTerminalMenus(String summary) {
            this.summary = summary;
        }

        @Override public Optional<UseSeams.MenuLook> opened(int menuBefore) {
            return opened(menuBefore, null, null);
        }

        @Override public Optional<UseSeams.MenuLook> opened(int menuBefore, BlockPos clickedCell,
                Direction clickedFace) {
            this.lastClickedCell = clickedCell;
            this.lastClickedFace = clickedFace;
            return Optional.of(new UseSeams.MenuLook() {
                @Override public Optional<Map<String, Integer>> contents() { return Optional.empty(); }
                @Override public boolean isNetworkTerminal() { return true; }
                @Override public Optional<String> networkTerminalSummary() { return Optional.of(summary); }
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
                @Override public String describe() { return "读终端存货"; }
            });
        }
    }
}
