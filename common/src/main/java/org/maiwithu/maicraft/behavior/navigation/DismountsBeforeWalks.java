// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.travel.modes.GetsOffVehicle;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 先下车再走的走到：走路类目标（走近、用东西、出行走路一段）起步时角色还坐在载具上，
 * 乘客位原版不会挪步，先按共有的那份「下车」按住潜行——原版下车就是乘客按住潜行，
 * 服务端看到就把人请下车——真的不坐在上面了才把这一趟交给走到接着算路。
 * 下到水里或岸上都从落点接着走，落点如实；按了两秒还下不来这一趟走不了，如实失败。
 * 不坐在载具上时与普通走到完全一样，走近、用东西、出行各路起点不用各自管载具。
 */
public final class DismountsBeforeWalks implements WalkTo {

    private final WalkTo walks;
    private final HoldsSneak sneaks;
    /** 这一刻角色坐没坐在载具上：生产实现读本地玩家的乘客位，测试替身从外面给。 */
    private final Predicate<TickContext> riding;
    /** 下车动作怎么建：生产实现用共有的那份「下车」，测试替身换成动作替身。 */
    private final Function<HoldsSneak, Action> dismounts;

    /** @param walks  内部真正的走到（内嵌寻路）；先下车这一段包在它外面
     *  @param sneaks 按住潜行做一件事：下车用的是共有的那一份 */
    public DismountsBeforeWalks(WalkTo walks, HoldsSneak sneaks) {
        this(walks, DismountsBeforeWalks::playerRiding, GetsOffVehicle::new, sneaks);
    }

    /** 替身拼装口：乘客判断与下车动作都从外面给，离线测试直接喂结论。 */
    DismountsBeforeWalks(WalkTo walks, Predicate<TickContext> riding,
            Function<HoldsSneak, Action> dismounts, HoldsSneak sneaks) {
        this.walks = Objects.requireNonNull(walks, "walks");
        this.sneaks = Objects.requireNonNull(sneaks, "sneaks");
        this.riding = Objects.requireNonNull(riding, "riding");
        this.dismounts = Objects.requireNonNull(dismounts, "dismounts");
    }

    /** 这一刻坐在载具上没有：读本地玩家的乘客位；角色不在世界里当没坐。 */
    private static boolean playerRiding(TickContext tick) {
        LocalPlayer player = tick.player().localPlayer();
        return player != null && player.isPassenger();
    }

    @Override
    public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
        return new Run(walks.start(target, permit));
    }

    /** 包着走到运行的先下车那一段：乘客位上先下车，下来了原样推进里面的运行。 */
    private final class Run implements WalkRun {

        private final WalkRun inner;
        /** 正在做的下车动作；没在下车为 null。 */
        private Action dismount;

        Run(WalkRun inner) {
            this.inner = inner;
        }

        // 每刻：不在乘客位（或刚下到位）原样走；乘客位上先推下车一刻，下成了同一刻交走路，下不来如实失败。
        @Override
        public ActionStatus tick(TickContext tick) {
            if (!riding.test(tick)) {
                stopDismount();
                return inner.tick(tick);
            }
            if (dismount == null) {
                dismount = dismounts.apply(sneaks);
            }
            ActionStatus status = dismount.tick(tick);
            if (status instanceof ActionStatus.Done) {
                // 已经不坐在上面了：收掉下车动作，同一刻把走路交回去，从落点（水里或岸上）接着算路。
                stopDismount();
                return inner.tick(tick);
            }
            if (status instanceof ActionStatus.Failed failed) {
                // 按了两秒还坐在上面：这一趟起步不了，照下车的结论如实失败，不无限按下去。
                stopDismount();
                return ActionStatus.failed(failed.problem());
            }
            return status;
        }

        // 收掉手上的下车动作：潜行键跟着松开，别把按住的潜行留给接下来的走路。
        private void stopDismount() {
            if (dismount != null) {
                dismount.close();
                dismount = null;
            }
        }

        @Override
        public void pause() {
            // 被生存需求打断：下车先松手（潜行键不留在身上），里面的运行照常停。
            if (dismount != null) dismount.pause();
            inner.pause();
        }

        @Override
        public void close() {
            stopDismount();
            inner.close();
        }

        // 下车按一下就成，不算停不得；没在下车时按里面的运行说（悬空、正在跳时停不得）。
        @Override
        public Interruptibility interruptibility() {
            return dismount != null ? Interruptibility.WORKING : inner.interruptibility();
        }

        @Override
        public boolean stopSafely() {
            return dismount == null && inner.stopSafely();
        }

        @Override
        public boolean stoppedSafely() {
            return dismount == null && inner.stoppedSafely();
        }

        @Override public WalkReport report() { return inner.report(); }

        @Override public boolean resume() { return inner.resume(); }

        @Override public boolean stop() { return inner.stop(); }

        @Override public boolean retarget(GoalCompiler.Compiled target) { return inner.retarget(target); }

        @Override public void allowSprint(boolean allowed) { inner.allowSprint(allowed); }

        @Override
        public String describe() {
            return dismount != null ? "先从载具上下来再走" : inner.describe();
        }
    }
}
