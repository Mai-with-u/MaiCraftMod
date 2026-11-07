// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 按住潜行的生产实现：每刻经角色的输入按下潜行再推进里面的动作，暂停与收尾时松开，
 * 不让潜行键留在按下的状态把之后走路也拖慢。
 */
public final class LiveHoldsSneak implements HoldsSneak {

    private final InputDriver inputs;
    private final Supplier<PlayerContext> context;

    public LiveHoldsSneak(InputDriver inputs, Supplier<PlayerContext> context) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public Action sneaking(Action inner) {
        return new Sneaking(Objects.requireNonNull(inner, "inner"), false);
    }

    @Override public Action sneakingInARow(Action inner) {
        return new Sneaking(Objects.requireNonNull(inner, "inner"), true);
    }

    /** 潜行落到角色身上最多等几刻：骑着东西、被别的状态压着按不下去时不干等，照常往下做。 */
    private static final int SETTLE_TICKS = 10;
    /** 一段连续点击里点完一下续按潜行几刻（约 0.5 秒），等下一下要不要接上。 */
    private static final int KEEP_TICKS = 10;

    /** 整个动作里每刻按着潜行；暂停与结束时松开。 */
    private final class Sneaking implements Action {
        private final Action inner;
        /** 点完先不起身：一段连续点击里的一下。 */
        private final boolean inARow;
        private int waited;

        Sneaking(Action inner, boolean inARow) {
            this.inner = inner;
            this.inARow = inARow;
        }

        @Override public ActionStatus tick(TickContext tick) {
            hold(true);
            // 潜行真落到角色身上（游戏这一刻已把"按下潜行"发给服务器）才往下做：同一刻刚按下就右键，
            // 服务器还当没潜行，手里的方块会被置物台、箱子截走——实机垫临时方块点在置物台顶上，七块平滑石头全放上了台。
            LocalPlayer self = tick.player().localPlayer();
            if (self != null && !self.isShiftKeyDown() && waited++ < SETTLE_TICKS) return ActionStatus.running();
            return inner.tick(tick);
        }

        @Override public void pause() {
            // 被生存需求打断时先松开潜行、也不再续按：插进来的临时任务要正常走路、正常点东西。
            hold(false);
            keepUntil(Long.MIN_VALUE);
            inner.pause();
        }

        @Override public void close() {
            // 连续点击里点完这一下先不起身：0.5 秒内下一下也要蹲着点就接着蹲，不在两下之间一蹲一起。
            PlayerContext current = context.get();
            if (inARow && current != null) {
                keepUntil(current.clientTick() + KEEP_TICKS);
            } else {
                hold(false);
            }
            inner.close();
        }

        @Override public Interruptibility interruptibility() {
            return inner.interruptibility();
        }

        @Override public String describe() {
            return "潜行着" + inner.describe();
        }

        private void hold(boolean on) {
            PlayerContext current = context.get();
            if (current != null && current.localPlayer() != null) inputs.sneak(current.localPlayer(), on);
        }

        private void keepUntil(long tick) {
            PlayerContext current = context.get();
            if (current != null && current.input().automationOwnsControls()) current.input().keepSneakingUntil(tick);
        }
    }
}
