// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.interaction.StepsInOrder;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 石英扳手与网络工具的拆解实现：主手持有满足 #c:tools/wrench 的赛特斯石英扳手、
 * 下界石英扳手或网络工具时，潜行右键可将 AE2 设备整块拆回背包不落地。
 *
 * <p>显式排除两种陨石箱子（ae2:sky_stone_chest、ae2:smooth_sky_stone_chest），因为游戏内其
 * disassembleWithWrench 恒返回失败，必须退回普通镐挖掘；对线缆格（ae2:cable_bus）按
 * CableBusBlockEntity 选区规则逐一命中面部件、中心线缆与伪装板拆空整格。
 */
public final class Ae2Wrench implements DismantleTool {

    public static final String CERTUS_WRENCH = "ae2:certus_quartz_wrench";
    public static final String NETHER_WRENCH = "ae2:nether_quartz_wrench";
    public static final String NETWORK_TOOL = "ae2:network_tool";
    /** 陨石箱子重写 disassembleWithWrench 恒返回 FAIL，不可用扳手拆解。F 游戏事实。 */
    public static final Set<String> EXCLUDED_BLOCKS = Set.of(
            "ae2:sky_stone_chest",
            "ae2:smooth_sky_stone_chest");

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;

    public Ae2Wrench(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public Optional<Item> toolFor(BlockState state) {
        if (state == null || isExcludedBlock(state)) return Optional.empty();
        if (!compat.call("判断方块能否用 AE2 扳手拆解", () -> reads.canDisassembleBlock(state))) {
            return Optional.empty();
        }
        return carriedTool().map(ItemStack::getItem);
    }

    @Override public Optional<Action> dismantle(BlockPos cell) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null || !level.isLoaded(cell)) return Optional.empty();
        BlockState before = level.getBlockState(cell);
        if (isExcludedBlock(before)) return Optional.empty();
        if (!compat.call("判断方块能否用 AE2 扳手拆解", () -> reads.canDisassembleBlock(before))) {
            return Optional.empty();
        }
        Optional<ItemStack> tool = carriedTool();
        if (tool.isEmpty()) return Optional.empty();
        String toolId = idOf(tool.get());
        boolean cableBus = compat.call("判断方块是否为 AE2 线缆格", () -> reads.isCableBus(before));
        if (cableBus) {
            return Optional.of(new CableBusDismantleAction(cell.immutable(), toolId));
        }
        // 普通 AE2 设备方块：扳手换到主手 -> 潜行右键拆下整块，待该格变空气或仅剩原流体确认完成。
        return Optional.of(new StepsInOrder("用 AE2 扳手拆下 " + cell.toShortString(), List.of(
                new StepsInOrder.Step("把拆解工具换到主手",
                        () -> holdingDisassembleTool() ? null : services.toMainhand().moveToMainhand(toolId).orElse(null)),
                new StepsInOrder.Step("潜行右键整块拆解",
                        () -> services.sneak().sneaking(services.interactions().useBlock(cell,
                                InteractionConfirmation.blockBecomesAir(cell, before)))))));
    }

    /** 判断指定方块是否属于显式排除的陨石箱子（纯函数，离线单测可直接验证）。 */
    public static boolean isExcludedBlock(BlockState state) {
        if (state == null) return true;
        return EXCLUDED_BLOCKS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    /** 决定线缆格多目标拆解的先后顺序：先拆各面部件，再拆中心线缆（拆中心线缆会顺带卸下所有伪装板），最后清残留伪装板。 */
    static Optional<Ae2ToolReads.CableTarget> pickNextCableTarget(List<Ae2ToolReads.CableTarget> targets) {
        if (targets == null || targets.isEmpty()) return Optional.empty();
        return targets.stream()
                .min(Comparator.comparingInt(target -> switch (target.kind()) {
                    case PART -> 0;
                    case CABLE -> 1;
                    case FACADE -> 2;
                }));
    }

    // 优先选石英扳手（避免占用网络工具箱），其次任意满足拆解条件的工具（含网络工具）。
    private Optional<ItemStack> carriedTool() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return Optional.empty();
        Inventory inventory = current.localPlayer().getInventory();
        ItemStack fallback = ItemStack.EMPTY;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !isDisassembleTool(stack)) continue;
            String id = idOf(stack);
            if (id.equals(CERTUS_WRENCH) || id.equals(NETHER_WRENCH)) return Optional.of(stack);
            if (fallback.isEmpty()) fallback = stack;
        }
        return fallback.isEmpty() ? Optional.empty() : Optional.of(fallback);
    }

    private boolean holdingDisassembleTool() {
        PlayerContext current = services.context().get();
        return current != null && current.localPlayer() != null
                && isDisassembleTool(current.localPlayer().getMainHandItem());
    }

    private boolean isDisassembleTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String id = idOf(stack);
        if (id.equals(CERTUS_WRENCH) || id.equals(NETHER_WRENCH) || id.equals(NETWORK_TOOL)) return true;
        return compat.call("判断物品能否用 AE2 扳手拆解", () -> reads.canDisassembleTool(stack));
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * 线缆格逐项拆解动作：先换拆解工具到主手，再按选区碰撞盒逐一潜行右键拆下面部件、中心线缆与伪装板，
     * 每次拆完重新读线缆格状态，直到整格变空或无可拆目标。
     */
    private final class CableBusDismantleAction implements Action {
        private static final int MAX_STEPS = 16;

        private final BlockPos cell;
        private final String toolId;
        private Action currentStep;
        private int dismantledSteps;

        CableBusDismantleAction(BlockPos cell, String toolId) {
            this.cell = cell;
            this.toolId = toolId;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.level() == null || player.localPlayer() == null) {
                return ActionStatus.running();
            }
            if (currentStep != null) {
                ActionStatus status = currentStep.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                currentStep.close();
                currentStep = null;
                if (status instanceof ActionStatus.Failed failed) return failed;
                return ActionStatus.progressed();
            }
            ClientLevel level = player.level();
            if (!level.isLoaded(cell)) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "线缆所在区块未加载"));
            }
            BlockState state = level.getBlockState(cell);
            if (state.isAir() || !compat.call("判断方块是否为 AE2 线缆格", () -> reads.isCableBus(state))) {
                return ActionStatus.done();
            }
            if (!holdingDisassembleTool()) {
                currentStep = services.toMainhand().moveToMainhand(toolId).orElse(null);
                if (currentStep == null) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "身上没有可用的 AE2 拆解扳手"));
                }
                return ActionStatus.progressed();
            }
            if (++dismantledSteps > MAX_STEPS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "线缆格拆解次数超限仍未清空"));
            }
            List<Ae2ToolReads.CableTarget> targets = compat.call("读取线缆格可拆部件",
                    () -> reads.cableDisassembleTargets(level, cell));
            Optional<Ae2ToolReads.CableTarget> next = pickNextCableTarget(targets);
            if (next.isEmpty()) {
                // 线缆格未暴露单独碰撞盒时回退到整格潜行右键，按变空确认。
                currentStep = services.sneak().sneaking(services.interactions().useBlock(cell,
                        InteractionConfirmation.blockBecomesAir(cell, state)));
                return ActionStatus.progressed();
            }
            Ae2ToolReads.CableTarget target = next.get();
            InteractionConfirmation confirmation = partRemovedConfirmation(cell, state, target.kind(), target.side());
            currentStep = services.sneak().sneaking(services.interactions().useBlockPart(cell, target.box(),
                    confirmation));
            return ActionStatus.progressed();
        }

        // 命中部件/中心线缆/伪装板后，只要该具体目标消失或整格已成空气即确认本次拆解生效。
        private InteractionConfirmation partRemovedConfirmation(BlockPos pos, BlockState before,
                Ae2ToolReads.TargetKind kind, Direction side) {
            InteractionConfirmation air = InteractionConfirmation.blockBecomesAir(pos, before);
            return ctx -> {
                if (air.observe(ctx) == InteractionConfirmation.Verdict.APPLIED) {
                    return InteractionConfirmation.Verdict.APPLIED;
                }
                if (ctx.level() == null || !ctx.level().isLoaded(pos)) {
                    return InteractionConfirmation.Verdict.PENDING;
                }
                boolean stillThere = compat.call("核对线缆格目标还在不在",
                        () -> reads.cableTargetPresent(ctx.level(), pos, kind, side));
                return stillThere ? InteractionConfirmation.Verdict.PENDING : InteractionConfirmation.Verdict.APPLIED;
            };
        }

        @Override public void pause() {
            if (currentStep != null) currentStep.pause();
        }

        @Override public void close() {
            if (currentStep != null) {
                currentStep.close();
                currentStep = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            return Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "用 AE2 扳手逐项拆解线缆格 " + cell.toShortString();
        }
    }
}
