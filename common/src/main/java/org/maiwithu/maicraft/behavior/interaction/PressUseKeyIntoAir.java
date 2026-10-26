// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.List;
import java.util.Objects;

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
 * 对着空处真按一下使用键：先把准星转到够不着任何方块、实体的方向（先朝天，头顶有东西就换四个斜上方），
 * 镜头转到位、准星确实什么都没指着、眼前没开界面才按，按完照调用方给的条件等游戏确认。
 *
 * <p>只想让模组收到按键时用（Create 手持锁链潜行按右键清掉链条输送机接线记着的起点）：对着空处按，
 * 手里的东西不会被放下去，也不会用到别的东西上。四下都够得着东西就不按，如实说按不了。
 * 确认窗到期还没等到确认，就是按了游戏没反应（模组没接下这一下），按"没生效"交代。
 */
public final class PressUseKeyIntoAir implements Action {

    /** 按完等游戏回话的期限（刻）：模组接下按键时当场就回，半秒还没回就是没接下。 */
    private static final int CONFIRM_TIMEOUT_TICKS = 10;
    /** 每个方向转过去的耐心（刻）；转不过去换下一个方向。 */
    private static final int TURN_PATIENCE_TICKS = 40;
    /** 眼前开着界面时等它关上的耐心（刻）；界面一直开着就按不了。 */
    private static final int SCREEN_PATIENCE_TICKS = 40;
    /** 往哪看：先正上方，再朝东南西北四个斜上方（仰角约 60 度），离眼睛三格的点。 */
    private static final List<Vec3> DIRECTIONS = List.of(new Vec3(0, 3, 0), new Vec3(1.5, 2.6, 0),
            new Vec3(-1.5, 2.6, 0), new Vec3(0, 2.6, 1.5), new Vec3(0, 2.6, -1.5));

    private final InteractionConfirmation confirmation;
    private final UseKeyPress keys;
    private int direction;
    private int turning;
    private int screenWaited;
    private PendingInteraction pending;
    private PlayerContext lastContext;

    public PressUseKeyIntoAir(InteractionConfirmation confirmation, UseKeyPress keys) {
        this.confirmation = Objects.requireNonNull(confirmation, "confirmation");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (player == null || player.localPlayer() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，按不了使用键", null));
        }
        lastContext = player;
        if (!player.isCurrent() || player.interactionSender() == null || !player.canInteractThisTick()) {
            return ActionStatus.running();
        }
        if (pending != null) return awaitConfirmation(player);
        if (direction >= DIRECTIONS.size()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "头顶和四个斜上方都够得着方块或别的东西，按使用键会用到它们，没按", "挪到头顶空旷的地方再试"));
        }
        return aimAndPress(player);
    }

    // 转向这一个方向：没转到位接着等，转不过去或准星指着东西换下一个方向；都对了、眼前没开界面才按。
    private ActionStatus aimAndPress(PlayerContext player) {
        FirstPersonScene scene = FirstPersonScene.of(player);
        Vec3 aim = scene.eyePosition().add(DIRECTIONS.get(direction));
        player.input().halt(player.localPlayer());
        player.input().lookAt(player.localPlayer(), aim);
        if (!AimCheck.settled(scene.viewVector(), scene.eyePosition(), aim)) {
            if (++turning > TURN_PATIENCE_TICKS) nextDirection();
            return ActionStatus.running();
        }
        if (scene.sightRay().getType() != HitResult.Type.MISS || !keys.aimsAtNothing()) {
            nextDirection();
            return ActionStatus.progressed();
        }
        if (!keys.worldInFront()) {
            if (++screenWaited > SCREEN_PATIENCE_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "眼前一直开着界面，按不了使用键", null));
            }
            return ActionStatus.running();
        }
        PendingInteraction submitted = player.interactionSender().submitControlProtocol(player, "按一下使用键", keys::press,
                confirmation, CONFIRM_TIMEOUT_TICKS);
        // 本刻交互机会刚被占、或上一下还在等确认：下一刻再按，不算按过。
        if (submitted == null) return ActionStatus.running();
        pending = submitted;
        return ActionStatus.progressed();
    }

    private void nextDirection() {
        direction++;
        turning = 0;
    }

    // 等游戏回话：确认了算按成；确认窗到期没回就是模组没接下这一下，按没生效交代；说不清的如实说说不清。
    private ActionStatus awaitConfirmation(PlayerContext player) {
        if (!pending.terminal()) pending = player.interactionSender().poll(player, pending);
        return switch (pending.status()) {
            case PENDING -> ActionStatus.running();
            case CONFIRMED_APPLIED -> ActionStatus.done();
            case CONFIRMED_NOT_APPLIED -> ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "按了一下使用键，游戏没有反应", null));
            default -> ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    "按了一下使用键，没能确认结果（" + pending.status() + "）", null));
        };
    }

    // 等游戏回话时停下会丢掉这一下的结论；转头的时候随时能停。
    @Override public Interruptibility interruptibility() {
        return pending != null && !pending.terminal() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public void pause() {
        halt();
    }

    // 收尾：按了还没回话的那一下按不确定交还，不假装没按过。
    @Override public void close() {
        if (pending != null && !pending.terminal() && lastContext != null && lastContext.interactionSender() != null) {
            lastContext.interactionSender().retireOneShotForTaskBoundary(lastContext, pending,
                    "按使用键的动作结束前没能等到游戏回话");
        }
        halt();
    }

    private void halt() {
        if (lastContext != null && lastContext.isCurrent()) lastContext.input().halt(lastContext.localPlayer());
    }

    @Override public String describe() {
        return pending == null ? "对着空处按使用键：转向第 " + (direction + 1) + " 个方向" : "等按使用键的回话";
    }
}
