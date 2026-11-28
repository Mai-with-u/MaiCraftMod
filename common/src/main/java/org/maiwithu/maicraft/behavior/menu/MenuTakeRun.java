// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从界面取货的一整趟（外壳）：没开界面就开 → 界面认下来做一轮取货（{@link MenuTakeRound}）→
 * 被打断时关停 → 被收尾时先撒手再请游戏关上 → 按各家的来源交回结论。跨刻推进。
 * 总期限到点不在一个界面上耗死，各家在期限钩子里说清自己的期限与到点怎么收场。
 *
 * <p>各家来源只写差异点，都长在要覆写的钩子上：打开一段怎么走（走到点开是共用打开动作还是
 * 模组自己的打开与核对）、这一刻的读数从哪来、总期限多长、从哪一刻开始数、期限到了怎么收场、
 * 一轮返回后有没有交接（关掉重开）、被打断时除了关界面还要做什么、
 * 光标和取货之外还有没有停不得的活。结算的来源也是差异点：有的来源收场自己算账，
 * 有的按会话逐笔确认过的数交结论，都写在自己的收场钩子与要货单实现里，外壳不碰账。
 */
public abstract class MenuTakeRun implements Action {

    /** 打开一段每刻的走向：还在开、界面认下来了（外壳接着建一轮）、或这一趟直接收场。 */
    public sealed interface Opening {

        /** 还在开（走到、点开、等同步），把动作状态原样交回。 */
        record Proceed(ActionStatus status) implements Opening {
        }

        /** 界面认下来了：外壳记下这份界面、建一轮取货接着推。 */
        record MenuReady(OpenedMenu menu) implements Opening {
        }

        /** 这一趟直接收场（界面刚打开就被关上之类），把结论原样交回。 */
        record Finish(ActionStatus status) implements Opening {
        }
    }

    /** 界面认下来的那份：关停时请游戏关上的是它；各家自己的读数来源不一定是它。 */
    private OpenedMenu menu;
    /** 界面认下来之后的一轮取货；界面没开、或各家交接重开把它丢掉时为 null。 */
    protected MenuTakeRound round;
    /** 总期限已经数了几刻。 */
    private long waited;

    /** 界面认下来的那份；还没开成时为 null。 */
    protected final OpenedMenu menu() {
        return menu;
    }

    /** 丢掉此刻的一轮取货（交接重开、打断回来重走）：下一刻重新走打开那段。 */
    protected final void discardRound() {
        round = null;
    }

    @Override public final ActionStatus tick(TickContext tick) {
        ActionStatus resumed = resumeFromPause();
        if (resumed != null) return resumed;
        if (limitCounting() && ++waited > limitTicks()) return onTimeout(tick);
        if (round == null) {
            return switch (openSegment(tick)) {
                case Opening.Proceed(ActionStatus status) -> status;
                case Opening.MenuReady(OpenedMenu opened) -> {
                    menu = opened;
                    menuOpened();
                    round = newRound(opened);
                    yield ActionStatus.progressed();
                }
                case Opening.Finish(ActionStatus status) -> status;
            };
        }
        return roundReturned(round.tick(tick, currentReading()));
    }

    // 控制循环每刻都会来问停不停，界面上还有没结清的一笔时停不得：半途撒手会把拆出来的东西交给关界面的流程。
    // 界面还没点开时（round 只在界面认领后才建）没有在点的笔，按各家的空当回答，不能在这里抛。
    @Override public final Interruptibility interruptibility() {
        if ((round != null && round.unsafeToStop()) || extraUnsafeToStop()) {
            return Interruptibility.UNSAFE_TO_STOP;
        }
        return whenIdle();
    }

    /** 被生存需求打断：先让各家收手，开着的界面请游戏关上，回来时由调用方按身上的清点再决定。 */
    @Override public final void pause() {
        pausing();
    }

    /** 被收尾：打开那侧一并收尾；一轮还没收场的先撒手（点出去的那几下交回没能确认），再请游戏关上界面。 */
    @Override public final void close() {
        closeOpening();
        if (round != null) {
            round.close(true);
            roundClosed();
        }
    }

    // ---- 差异点：各家来源覆写这些钩子 ----

    /** 打开一段的这一刻：走到点开、核对与等同步各家不一样，界面认下来回 {@link Opening.MenuReady}。 */
    protected abstract Opening openSegment(TickContext tick);

    /** 界面认下来后的一轮取货：要哪几样、收场怎么记账，是各家的事。 */
    protected abstract MenuTakeRound newRound(OpenedMenu menu);

    /** 这一轮推界面读的数从哪来：共用的打开动作读认领的界面，模组的会话读它自己的绑定。 */
    protected abstract Optional<MenuContent.Reading> currentReading();

    /** 总期限（刻）：到点不在一个界面上耗死；各家按自己的活定长短。 */
    protected abstract long limitTicks();

    /** 这一刻总期限计不计数：默认从头数，从点开界面才开始数的在这里改。 */
    protected boolean limitCounting() {
        return true;
    }

    /** 总期限到了怎么收场：有的照实按超时失败，有的不再盘下一笔、照常放好光标关上再出结论。 */
    protected abstract ActionStatus onTimeout(TickContext tick);

    /** 被打断过、回来这一刻要先做的事（重新打开接着做之类）；没有就回 null，照常推进。 */
    protected ActionStatus resumeFromPause() {
        return null;
    }

    /** 一轮返回后的交接（这一轮没算完、丢掉重走之类）；默认原样交回。 */
    protected ActionStatus roundReturned(ActionStatus status) {
        return status;
    }

    /** 界面刚认下来要做的差异动作（记下背包起点之类）；默认没有。 */
    protected void menuOpened() {
    }

    /** 被打断时对打开那侧的收手：松开按键、停下走到。开着的界面由外壳请游戏关上。 */
    protected abstract void pauseOpening();

    /** 被打断的整套收手：默认停打开那侧、关掉开着的界面；打断时另有交接的在这里整段覆写。 */
    protected void pausing() {
        pauseOpening();
        if (menu != null) menu.abandon();
    }

    /** 被收尾时对打开那侧的收尾：替身接入时打开动作自己收，生产的连按键带交互机会一并还。 */
    protected abstract void closeOpening();

    /** 一轮撒手关上之后的记账（通报、结算交回）；默认没有。 */
    protected void roundClosed() {
    }

    /** 取货之外还有没有停不得的活（放光标还在等确认之类）；默认没有。 */
    protected boolean extraUnsafeToStop() {
        return false;
    }

    /** 手上没有取货的一笔时此刻算什么：默认正常干活，两件事之间有安全空当的在这里改。 */
    protected Interruptibility whenIdle() {
        return Interruptibility.WORKING;
    }
}
