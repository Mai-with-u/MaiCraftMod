// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.inventory.containers.FeedReport;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 水晶修复器（{@code extendedae:crystal_fixer}）设备委托：
 * 无界面直点交互，仅相对背面一面连 ME 网与受电，相对正面紧贴待修复母岩；
 * 状态读数同步槽 0 燃料与正前方母岩方块（未对准可修复母岩时仅提示不拦截）；
 * {@code machine_run} 声明 {@link FuelUse#fuel}，主手持燃料右键装入槽 0。
 */
final class CrystalFixerOps implements ExtendedAeDeviceOps {

    private final ExtendedAeCompat compat;

    CrystalFixerOps(ExtendedAeCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    // 水晶修复器仅相对背面一面可以连 ME 网与受电。
    @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        Direction back = level == null ? Direction.SOUTH : compat.crystalFixerAt(level, at)
                .map(ExtendedAeReads.CrystalFixerView::back).orElse(Direction.SOUTH);
        return Optional.of(List.of(new ExchangePoint(at, back, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                "水晶修复器背面：唯一可连 ME 网与受电的面")));
    }

    @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到水晶修复器"));
        Optional<ExtendedAeReads.CrystalFixerView> found = compat.crystalFixerAt(level, at);
        if (found.isEmpty()) return Optional.empty();
        Direction front = found.get().front() == null ? Direction.NORTH : found.get().front();
        BlockPos frontPos = at.relative(front);
        String facingBlock = level.isLoaded(frontPos)
                ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(frontPos).getBlock()).toString()
                : "unloaded";
        return Optional.of(buildState(found.get(), facingBlock));
    }

    /** 纯函数：将水晶修复器视图与正前方方块 ID 转为标准机器状态（只提示母岩是否匹配，不拦截加燃料）。 */
    static MachineState buildState(ExtendedAeReads.CrystalFixerView view, String facingBlockId) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("powered", view.powered());
        readings.put("front", view.front() == null ? "north" : view.front().getSerializedName());
        readings.put("power_side", view.back() == null ? "south" : view.back().getSerializedName());
        readings.put("facing_block", facingBlockId == null ? "minecraft:air" : facingBlockId);
        boolean facingBudding = isRepairableBudding(facingBlockId);
        readings.put("facing_repairable_budding", facingBudding);
        List<MachineState.Shown> shown = new ArrayList<>();
        if (view.fuelItemId().isEmpty() || view.fuelCount() <= 0) {
            readings.put("fuel", "empty");
            readings.put("fuel_count", 0);
        } else {
            readings.put("fuel", view.fuelItemId());
            readings.put("fuel_count", view.fuelCount());
            shown.add(new MachineState.Shown(view.fuelItemId(), view.fuelCount()));
        }
        MachineState.Activity activity = !view.powered() ? MachineState.Activity.OFFLINE
                : (facingBudding && view.fuelCount() > 0) ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        String note = facingBudding
                ? "正前方已对准可修复母岩；仅背面受电，修赛特斯母岩喂充能赛特斯水晶，修恩特罗母岩喂末影珍珠粉"
                : "提示：正前方当前方块为 " + facingBlockId + "（不是已知可修复母岩）；仅背面受电，仍可直接喂燃料";
        return new MachineState(activity, null, null, List.copyOf(shown), readings, note);
    }

    /** 判断正前方方块 ID 是否为赛特斯石英母岩或恩特罗母岩。 */
    static boolean isRepairableBudding(String blockId) {
        if (blockId == null || blockId.isBlank()) return false;
        return blockId.contains("budding_quartz") || blockId.contains("entro_budding")
                || blockId.equals("ae2:quartz_block");
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public FuelUse fuelUse(BlockPos at) {
                return FuelUse.fuel("修赛特斯母岩用 ae2:charged_certus_quartz_crystal，修恩特罗母岩用 #c:dusts/ender_pearl");
            }

            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                return Optional.of(new RefuelAction(at, item.item(), item.count(), permissions, compat, services));
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                return Optional.empty();
            }
        });
    }

    /** 主手持燃料右键水晶修复器（TileCrystalFixer.refuel 整叠装入槽 0）并按槽 0 增量汇报实情。 */
    static final class RefuelAction implements Action, FeedReport {

        private enum Stage { HOLD, APPROACH, USE, DONE }

        private final BlockPos at;
        private final String fuelSpec;
        private final int wantedCount;
        private final Permissions permissions;
        private final ExtendedAeCompat compat;
        private final PlayerServices services;
        private final List<String> notes = new ArrayList<>();

        private Stage stage = Stage.HOLD;
        private Action step;
        private int fuelBefore;
        private int delivered;

        RefuelAction(BlockPos at, String fuelSpec, int wantedCount, Permissions permissions,
                ExtendedAeCompat compat, PlayerServices services) {
            this.at = at.immutable();
            this.fuelSpec = Objects.requireNonNull(fuelSpec, "fuelSpec");
            this.wantedCount = Math.max(1, wantedCount);
            this.permissions = Objects.requireNonNull(permissions, "permissions");
            this.compat = Objects.requireNonNull(compat, "compat");
            this.services = Objects.requireNonNull(services, "services");
        }

        @Override public int delivered() { return delivered; }

        @Override public List<String> notes() { return List.copyOf(notes); }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.level() == null || player.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "角色不在世界里，无法喂水晶修复器"));
            }
            return switch (stage) {
                case HOLD -> holdFuel(tick);
                case APPROACH -> approach(tick);
                case USE -> useFixer(tick, player);
                case DONE -> ActionStatus.done();
            };
        }

        private ActionStatus holdFuel(TickContext tick) {
            if (step == null) {
                Optional<Action> hold = services.toMainhand().moveToMainhand(fuelSpec);
                if (hold.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                            "身上没有可用燃料 " + fuelSpec + "，无法装入水晶修复器"));
                }
                step = hold.get();
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (status instanceof ActionStatus.Failed) return status;
            stage = Stage.APPROACH;
            return ActionStatus.progressed();
        }

        private ActionStatus approach(TickContext tick) {
            if (step == null) step = services.approach().toward(ApproachTarget.ofBlock(at), permissions);
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (status instanceof ActionStatus.Failed) return status;
            stage = Stage.USE;
            return ActionStatus.progressed();
        }

        private ActionStatus useFixer(TickContext tick, PlayerContext player) {
            if (step == null) {
                fuelBefore = compat.crystalFixerAt(player.level(), at)
                        .map(ExtendedAeReads.CrystalFixerView::fuelCount).orElse(0);
                InteractionConfirmation added = ctx -> compat.crystalFixerAt(ctx.level(), at)
                        .map(v -> v.fuelCount() > fuelBefore
                                ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING)
                        .orElse(InteractionConfirmation.Verdict.PENDING);
                step = services.interactions().useBlock(at, added);
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            finishStep();
            if (status instanceof ActionStatus.Failed) return status;
            int fuelAfter = compat.crystalFixerAt(player.level(), at)
                    .map(ExtendedAeReads.CrystalFixerView::fuelCount).orElse(fuelBefore);
            delivered = Math.max(0, fuelAfter - fuelBefore);
            if (delivered == 0) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "右键水晶修复器后燃料槽未增加（槽满或该物品不是水晶修复器燃料）"));
            }
            if (delivered < wantedCount) {
                notes.add("水晶修复器槽 0 实际装入 " + delivered + " 件燃料（要求 " + wantedCount + " 件）");
            }
            stage = Stage.DONE;
            return ActionStatus.done();
        }

        private void finishStep() {
            if (step != null) {
                step.close();
                step = null;
            }
        }

        @Override public void pause() { if (step != null) step.pause(); }
        @Override public void close() { finishStep(); }
        @Override public Interruptibility interruptibility() {
            return step != null ? step.interruptibility() : Interruptibility.WORKING;
        }
        @Override public String describe() { return "向水晶修复器装入燃料 " + fuelSpec; }
    }
}
