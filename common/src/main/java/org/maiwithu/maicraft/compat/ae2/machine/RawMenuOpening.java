// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
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
 * 不带 36 格角色背包的 AE2 界面直连打开与关闭（例如合成 CPU 界面 ae2:craftingcpu）：
 * 腾空主手 → 走到方块跟前 → 右键点开并等界面编号变化 → 等目标菜单读端就绪；
 * 不走通用容器布局证明，避免因没有玩家背包格被布局校验拦截。
 */
final class RawMenuOpening implements Action {

    /** 等目标界面读端就绪的期限（刻）。F 游戏事实。 */
    private static final int READY_WAIT_TICKS = 40;

    private enum Stage { HAND, APPROACH, CLICK, WAIT_READY, READY }

    private final BlockPos at;
    private final Permissions permissions;
    private final PlayerServices services;
    private final BooleanSupplier menuReady;
    private final String label;

    private Stage stage = Stage.HAND;
    private Action step;
    private ClientMenuChannel channel;
    private int waited;

    RawMenuOpening(BlockPos at, Permissions permissions, PlayerServices services,
            BooleanSupplier menuReady, String label) {
        this.at = at.immutable();
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.services = Objects.requireNonNull(services, "services");
        this.menuReady = Objects.requireNonNull(menuReady, "menuReady");
        this.label = Objects.requireNonNull(label, "label");
    }

    /** 关上通过本打开器认领的界面（仅发一次关窗请求）。 */
    void requestClose() {
        if (channel != null && channel.stillOpen()) {
            channel.requestClose();
        }
    }

    @Override public ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case HAND -> clearHand(tick);
            case APPROACH -> approach(tick);
            case CLICK -> clickBlock(tick);
            case WAIT_READY -> waitReady();
            case READY -> ActionStatus.done();
        };
    }

    private ActionStatus clearHand(TickContext tick) {
        if (step == null) {
            Optional<Action> empty = services.toMainhand().emptyMainhand();
            if (empty.isEmpty()) {
                stage = Stage.APPROACH;
                return ActionStatus.progressed();
            }
            step = empty.get();
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed) return status;
        stage = Stage.APPROACH;
        return ActionStatus.progressed();
    }

    private ActionStatus approach(TickContext tick) {
        if (step == null) {
            step = services.approach().toward(ApproachTarget.ofBlock(at), permissions);
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "走不到 " + label + " 跟前：" + failed.problem().headline()));
        }
        stage = Stage.CLICK;
        return ActionStatus.progressed();
    }

    private ActionStatus clickBlock(TickContext tick) {
        if (step == null) {
            PlayerContext player = tick.player();
            int before = player != null && player.localPlayer() != null
                    ? player.localPlayer().containerMenu.containerId : 0;
            InteractionConfirmation confirm = InteractionConfirmation.expiresAsNotApplied(
                    InteractionConfirmation.menuChanged(before));
            step = services.interactions().useBlock(at, confirm);
        }
        ActionStatus status = step.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        finishStep();
        if (status instanceof ActionStatus.Failed failed) {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "点开 " + label + " 界面失败（可能尚未成型或未连网）：" + failed.problem().headline()));
        }
        channel = ClientMenuChannel.claimCurrent(services.context());
        if (channel == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, label + " 界面打开后立即关闭"));
        }
        stage = Stage.WAIT_READY;
        return ActionStatus.progressed();
    }

    private ActionStatus waitReady() {
        if (menuReady.getAsBoolean()) {
            stage = Stage.READY;
            return ActionStatus.done();
        }
        if (!channel.stillOpen()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, label + " 界面刚打开就被关上了"));
        }
        if (++waited <= READY_WAIT_TICKS) return ActionStatus.running();
        requestClose();
        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, label + " 界面已打开但一直没同步出目标状态"));
    }

    private void finishStep() {
        if (step != null) {
            step.close();
            step = null;
        }
    }

    @Override public void pause() {
        if (step != null) step.pause();
    }

    @Override public void close() {
        finishStep();
        requestClose();
    }

    @Override public Interruptibility interruptibility() {
        return step != null ? step.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "直连打开 " + label + " 界面";
    }
}
