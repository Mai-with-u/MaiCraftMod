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
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
