// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从载具上下来：像玩家一样按住潜行（走按住手势那一份），直到真的不再坐在上面；
 * 按了两秒还下不来如实失败。船、骆驼、矿车、列车的座位都是这样下的。
 * 确认真的离开乘客位，不以潜行键按下为准。
 */
public final class GetsOffVehicle implements Action {

    /** 按住潜行最多这么多刻（玩家常识：按两秒下不来就不会一直按着等）。 */
    public static final int LIMIT_TICKS = 40;

    private final Action holding;
    private int ticks;

    public GetsOffVehicle(HoldsSneak sneaks) {
        this.holding = sneaks.sneaking(new Holding());
    }

    /** 下车的决定：不坐在上面就完，按的刻数没到就继续按，超了如实放弃。纯函数，离线可测。 */
    enum Off { DONE, HOLD, GIVE_UP }

    static Off decide(boolean passenger, int heldTicks) {
        if (!passenger) return Off.DONE;
        return heldTicks >= LIMIT_TICKS ? Off.GIVE_UP : Off.HOLD;
    }

    // 每刻：潜行键由按住手势按着，这里只看下没下来；下来了停住身体交还，超时如实失败。
    private final class Holding implements Action {
        @Override
        public ActionStatus tick(TickContext context) {
            LocalPlayer me = context.player().localPlayer();
            Off off = decide(me.isPassenger(), ticks);
            if (off == Off.DONE) {
                halt(context, me);
                return ActionStatus.done();
            }
            if (off == Off.GIVE_UP) {
                halt(context, me);
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "按住潜行两秒还没从载具上下来"));
            }
            ticks++;
            return ActionStatus.running();
        }

        @Override public void pause() { }

        @Override public void close() { }

        @Override public Interruptibility interruptibility() {
            // 潜行下车按一下就成，不算停不得；但正在按着时按正常干活对待。
            return Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "从载具上下来";
        }

        // 松开身体：前进、平移都停，人站住。
        private void halt(TickContext context, LocalPlayer self) {
            if (context.player().input() != null && self != null) {
                context.player().input().halt(self);
            }
        }
    }

    @Override public ActionStatus tick(TickContext context) {
        return holding.tick(context);
    }

    @Override public void pause() {
        holding.pause();
    }

    @Override public void close() {
        holding.close();
    }

    @Override public Interruptibility interruptibility() {
        return holding.interruptibility();
    }

    @Override public String describe() {
        return holding.describe();
    }
}
