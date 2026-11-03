// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.UseKeyPress;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 对准一只生物，再替模组发一次它自己的包：模组把"对着生物按某个键"做成客户端拦按键、自己发包时
 * （车万女仆对着女仆按聊天键打开对话框），角色模拟不了真实按键事件，就照真人的身体动作做——
 * 转头看着它、准星确实落在它身上、眼前没开界面，再经交互提交入口提交那一个包，按调用方给的条件等确认。
 *
 * <p>站位由调用方负责（先走到够得着、看得见它的地方）。对不准（被挡住、它跑远了、准星落在别的东西上）就如实
 * 失败、不提交，调用方换站位再来；确认窗到期没等到就是游戏没接这一下，按没生效交代。
 */
public final class AimAtEntityThenSubmit implements Action {

    /** 转头对准的耐心（刻）：它在动也追得上，超过就是被挡住或够不着。 */
    private static final int AIM_PATIENCE_TICKS = 40;
    /** 眼前开着界面时等它关上的耐心（刻）；界面一直开着就不提交。 */
    private static final int SCREEN_PATIENCE_TICKS = 40;

    /** 那只生物不在了（被移除、死了）。 */
    private final BooleanSupplier gone;
    /** 从现在的现场看，往它身上哪一点看。 */
    private final Function<FirstPersonScene, Vec3> aimPoint;
    /** 准星射到的是不是它。 */
    private final Predicate<HitResult> onTarget;
    private final Function<PlayerContext, FirstPersonScene> scenes;
    private final String doing;
    private final Runnable submission;
    private final InteractionConfirmation confirmation;
    private final int confirmTicks;
    /** 眼前是世界画面、没开任何界面。 */
    private final BooleanSupplier worldInFront;
    private int aiming;
    private int screenWaited;
    private PendingInteraction pending;
    private PlayerContext lastContext;

    /**
     * @param target       要对准的生物
     * @param doing        在做什么，写进提交记录与失败原因，例如"打开女仆的对话框"
     * @param submission   发那一个包；只在对准后的这一刻调用一次
     * @param confirmation 怎样算游戏接了这一下
     * @param confirmTicks 提交后最多等几刻确认
     */
    public static AimAtEntityThenSubmit toward(Entity target, String doing, Runnable submission,
            InteractionConfirmation confirmation, int confirmTicks, UseKeyPress keys) {
        Objects.requireNonNull(target, "target");
        return new AimAtEntityThenSubmit(() -> target.isRemoved() || !target.isAlive(),
                scene -> scene.visibleEntityPoint(target),
                sight -> sight instanceof EntityHitResult hit && hit.getEntity() == target,
                FirstPersonScene::of, doing, submission, confirmation, confirmTicks, keys::worldInFront);
    }

    /** 离线测试用：生物的三件事由替身回答。 */
    AimAtEntityThenSubmit(BooleanSupplier gone, Function<FirstPersonScene, Vec3> aimPoint,
            Predicate<HitResult> onTarget, Function<PlayerContext, FirstPersonScene> scenes, String doing,
            Runnable submission, InteractionConfirmation confirmation, int confirmTicks, BooleanSupplier worldInFront) {
        this.gone = Objects.requireNonNull(gone, "gone");
        this.aimPoint = Objects.requireNonNull(aimPoint, "aimPoint");
        this.onTarget = Objects.requireNonNull(onTarget, "onTarget");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.doing = Objects.requireNonNull(doing, "doing");
        this.submission = Objects.requireNonNull(submission, "submission");
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
        if (confirmTicks < 1) throw new IllegalArgumentException("确认至少等一刻：" + confirmTicks);
        this.confirmTicks = confirmTicks;
        this.worldInFront = Objects.requireNonNull(worldInFront, "worldInFront");
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (player == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，" + doing + "做不了", null));
        }
        lastContext = player;
        if (pending != null) return awaitConfirmation(player);
        if (gone.getAsBoolean()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "要对着的那只生物不在了，" + doing + "没做", null));
        }
        if (!player.isCurrent() || player.interactionSender() == null || !player.canInteractThisTick()) {
            return ActionStatus.running();
        }
        return aimAndSubmit(player);
    }

    // 转头看着它：没转到位、准星还没落在它身上就接着转（它在动也跟着转）；对准了、眼前没开界面才提交。
    private ActionStatus aimAndSubmit(PlayerContext player) {
        FirstPersonScene scene = scenes.apply(player);
        Vec3 aim = aimPoint.apply(scene);
        player.input().halt(player.localPlayer());
        player.input().lookAt(player.localPlayer(), aim);
        boolean settled = AimCheck.settled(scene.viewVector(), scene.eyePosition(), aim);
        if (!settled || !onTarget.test(scene.sightRay())) {
            if (++aiming > AIM_PATIENCE_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "对不准那只生物（被挡住或够不着），" + doing + "没做", "换个看得见它、够得着的地方"));
            }
            return ActionStatus.running();
        }
        if (!worldInFront.getAsBoolean()) {
            if (++screenWaited > SCREEN_PATIENCE_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "眼前一直开着界面，" + doing + "没做", null));
            }
            return ActionStatus.running();
        }
        PendingInteraction submitted = player.interactionSender().submitControlProtocol(player, doing, submission,
                confirmation, confirmTicks);
        // 本刻交互机会刚被占、或上一下还在等确认：下一刻再提交，不算提交过。
        if (submitted == null) return ActionStatus.running();
        pending = submitted;
        return ActionStatus.progressed();
    }

    // 等游戏回话：确认了算做成；确认窗到期没回就是游戏没接这一下，按没生效交代；说不清的如实说说不清。
    private ActionStatus awaitConfirmation(PlayerContext player) {
        if (!pending.terminal()) pending = player.interactionSender().poll(player, pending);
        return switch (pending.status()) {
            case PENDING -> ActionStatus.running();
            case CONFIRMED_APPLIED -> ActionStatus.done();
            case CONFIRMED_NOT_APPLIED -> ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    doing + "，游戏没有反应", null));
            default -> ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    doing + "，没能确认结果（" + pending.status() + "）", null));
        };
    }

    // 等游戏回话时停下会丢掉这一下的结论；转头的时候随时能停。
    @Override public Interruptibility interruptibility() {
        return pending != null && !pending.terminal() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public void pause() {
        halt();
    }

    // 收尾：提交了还没回话的那一下按不确定交还，不假装没提交过。
    @Override public void close() {
        if (pending != null && !pending.terminal() && lastContext != null && lastContext.interactionSender() != null) {
            lastContext.interactionSender().retireOneShotForTaskBoundary(lastContext, pending,
                    doing + "的动作结束前没能等到游戏回话");
        }
        halt();
    }

    private void halt() {
        if (lastContext != null && lastContext.isCurrent()) lastContext.input().halt(lastContext.localPlayer());
    }

    @Override public String describe() {
        return pending == null ? "对准那只生物，" + doing : "等" + doing + "的回话";
    }
}
