// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 对着打开的界面按要货单跑完一轮取货：逐样盘"这一刻还缺几件、下一笔从哪格拿几件"，
 * 手上的笔交给搬运执行 {@link TransferRun} 逐刻点、等结清；取不动的那一样这一趟先放下，
 * 别的接着试，全部盘完才请游戏关上界面，关上前把此刻的界面内容交给收场记忆。
 * 点出去了却没能确认的交互（纹丝不动、对不上账、界面中途没了、收尾时手上还有一笔）
 * 一句一条记在这里，来源用一条接缝把账原样交回。
 *
 * <p>各家来源只提供差异点，都长在构造时要的东西里：每样怎么认、这一刻还缺几件
 * （机器料箱留一半的限额在这里算）、关箱前记什么、界面关上后这一轮算完还是接着开下一只。
 * 只管界面认下来之后的一段：走到、点开、等第一份读数是打开动作的事；
 * 开多只包一只只换着开，也是来源自己的事，一轮只对一份打开的界面。
 */
public final class MenuTakeRound {

    /** 要货单里的一样：怎么认它、这一刻还缺几件；留半限额、取不动的说明这类差异点长在实现里。 */
    public interface Want {

        /** 记账与描述用的叫法，写全来源，例如"从家门口的箱子拿铁锭"。 */
        String label();

        /** 什么样的槽位算这一样；容器侧找格与背包侧核对都用它。 */
        Predicate<SlotSnapshot> matcher();

        /** 界面刚认下来的那一份读数：取货的起点与限额在这里定；每轮只叫一次。 */
        default void firstReading(MenuContent.Reading reading) {
        }

        /** 这一刻还缺几件；已经够（拿够了、留半限额到了、身上数够了）给不大于零的数。 */
        int stillNeeded(MenuContent.Reading reading);

        /** 这一样这一趟没拿成也不再点（箱里没有了、背包这一刻放不下、留半到了）：差异点在这里交代原因。 */
        default void gaveUp() {
        }

        /** 一笔对不上账之后还按不按同一份要货单接着盘：箱子按现场重新盘，说不清搬走多少的收场交给引擎清点。 */
        boolean settleAfterDiverge();
    }

    /** 一轮取货的收场：关箱前记什么、界面关上（或中途没了）之后这一轮怎么算完；差异点在这里。 */
    public interface Endings {

        /** 关箱前把界面此刻的内容交给各家的记忆；界面已经没了时拿到空读数，各家照自己的记法记或忘。 */
        void rememberBeforeClose(Optional<MenuContent.Reading> lastReading);

        /** 界面关上之后这一轮怎么算完：完成、失败，还是接着开下一只。 */
        ActionStatus finishAfterClosed(boolean tookAnything);

        /** 界面中途没了（点着一半被合上）这一轮怎么算完：拿走多少由引擎清点，这里不再点关闭。 */
        ActionStatus finishAfterMenuGone(boolean tookAnything);

        /** 关界面的动作没能确认关上时多交代的一句；没有就不交代。 */
        default Optional<String> closeUnconfirmedNote() {
            return Optional.empty();
        }
    }

    /** 一轮取货的先后：盘着搬、关界面、收场。 */
    private enum Stage { MOVE, CLOSE, DONE }

    private final OpenedMenu menu;
    private final List<Want> wants;
    private final Endings endings;
    /** 手上的一笔交给它逐刻点、等结清、出结论。 */
    private final TransferRun run;
    private Stage stage = Stage.MOVE;
    /** 正在点的那样在要货单里的第几项；没有在点的为 -1：交回"没能确认"时说得清是哪样。 */
    private int wantIndex = -1;
    /** 正在点的那一笔按计划要搬几件：交回"没能确认"时说得清是多大的笔。 */
    private int moveAmount;
    /** 每一样这一趟是不是已经盘完（拿够了、取不动了）：盘完的不再点。 */
    private final boolean[] settled;
    /** 第一份读数过手了没有：过了才让每样定起点。 */
    private boolean started;
    /** 这一轮是不是真的拿到过东西：拿到过就不拿"留了一半"之类的缘由当整场失败。 */
    private boolean tookAnything;
    /** 点出去了却没能确认结果的交互，一句一笔，经交回接缝交给引擎。 */
    private final List<String> unconfirmed = new ArrayList<>();
    /** 关界面的一笔：到关的阶段才建。 */
    private Action closing;
    /** 收场的结论只算一次：算完就记下，后面每刻都回同一条。 */
    private ActionStatus ending;

    public MenuTakeRound(OpenedMenu menu, List<? extends Want> wants, Endings endings) {
        this.menu = menu;
        this.wants = List.copyOf(new ArrayList<>(wants));
        this.endings = endings;
        this.run = new TransferRun(menu);
        this.settled = new boolean[this.wants.size()];
    }

    /**
     * 推进一刻：手上有笔先跑完；空手且读数在手就盘下一笔，盘不出要点的就记收场、关界面；
     * 读数没了（界面被合上）照界面没了收场，等读数、开界面的宽限由调用方把着，
     * 读数在手才叫进来。
     */
    public ActionStatus tick(TickContext tick, Optional<MenuContent.Reading> reading) {
        return switch (stage) {
            case MOVE -> move(reading);
            case CLOSE -> closeMenu(tick);
            case DONE -> ending;
        };
    }

    /** 手上还有一笔没出结论：调用方这刻把推进让给一轮取货，别急着盘下一笔或收场。 */
    public boolean movePending() {
        return !run.idle();
    }

    /** 界面进入关的阶段（正在关或已收场）：来源的描述照实说"关上"。 */
    public boolean atClosing() {
        return stage != Stage.MOVE;
    }

    /** 点出去了却没能确认结果的交互，一句一笔。 */
    public List<String> unconfirmedFacts() {
        return List.copyOf(unconfirmed);
    }

    /** 点出去了的一笔下还拿着东西时停不得：半途撒手会把拆出来的尾数交给关界面的流程。 */
    public boolean unsafeToStop() {
        return run.unsafeToStop();
    }

    /**
     * 中途撒手（总期限到、被收尾）：手上还有一笔的，点出去没等到结果的那几下记没能确认；
     * 一下都没发出去的不算，那只是没点成。
     */
    public void abandonAndNote(String circumstance) {
        if (run.idle()) {
            wantIndex = -1;
            return;
        }
        int submitted = run.abandon();
        if (submitted > 0) {
            unconfirmed.add(wants.get(wantIndex).label() + "：搬 " + moveAmount + " 件的这笔点了 " + submitted
                    + " 下，" + circumstance + "没能等到结算结果");
        }
        wantIndex = -1;
    }

    /** 中途撒手但不记账（来源自己决定怎么交代）：手上的一笔不再点了。 */
    public void abandonQuietly() {
        run.abandon();
        wantIndex = -1;
    }

    /** 收尾：还没收场的先撒手，界面请游戏关上，别悬着占着身体；关界面的一笔也一并收尾。 */
    public void close(boolean noteAbandonedMove) {
        if (closing != null) closing.close();
        if (stage != Stage.DONE) {
            if (noteAbandonedMove) {
                abandonAndNote("收尾时");
            } else {
                abandonQuietly();
            }
            menu.abandon();
        }
    }

    // 按量搬：手上的笔交给搬运执行逐刻点、等结清；空手时盘下一笔；读数没了照界面没了收场。
    private ActionStatus move(Optional<MenuContent.Reading> reading) {
        if (movePending()) return run.tickAction(this::onSettled);
        if (reading.isEmpty()) return menuGone();
        MenuContent.Reading open = reading.get();
        if (!started) {
            started = true;
            // 起点与限额在第一份读数上一次定死：拿走多少按它与当刻的差算，不在中途改口径。
            for (Want want : wants) want.firstReading(open);
        }
        if (planNext(open)) return run.tickAction(this::onSettled);
        return toClose(open);
    }

    // 盘下一笔：按要货单顺序找第一样还缺、且这一刻取得到的；取得到就提交给搬运执行，
    // 动手前的证据由它在第一下点出去之前冻结。
    // 取不动（计划给不出：箱里没有了、背包这一刻放不下）就这一趟先放下它，别的样接着试；全盘完才收场。
    private boolean planNext(MenuContent.Reading open) {
        for (int i = 0; i < wants.size(); i++) {
            if (settled[i]) continue;
            Want want = wants.get(i);
            int needed = want.stillNeeded(open);
            if (needed <= 0) {
                settled[i] = true;
                want.gaveUp();
                continue;
            }
            Optional<ContainerTakePlan.Move> next = ContainerTakePlan.next(open, want.matcher(), needed);
            if (next.isEmpty()) {
                settled[i] = true;
                want.gaveUp();
                continue;
            }
            wantIndex = i;
            moveAmount = next.get().amount();
            run.begin(new TransferRun.Move(next.get().clicks(),
                    new TransferRun.Check.IntoBackpack(want.matcher(), moveAmount), want.label()));
            return true;
        }
        return false;
    }

    // 一笔出了结论：结清接着盘下一笔；点出去了身上一直没多（多半背包放不下）记下没能确认，
    // 这一样这一趟先放下；对不上或光标没放下来的，这一笔搬走多少说不清，按各家的规矩接着盘或收场。
    private ActionStatus onSettled(TransferRun.MoveResult result) {
        if (result instanceof TransferRun.MoveResult.Confirmed(int amount)) {
            tookAnything = true;
        } else if (result instanceof TransferRun.MoveResult.NotMoved) {
            unconfirmed.add(wants.get(wantIndex).label() + "：搬 " + moveAmount
                    + " 件的那几下都点出去了，身上一直没多，没能确认结果");
            settled[wantIndex] = true;
        } else if (result instanceof TransferRun.MoveResult.Diverged) {
            tookAnything = true;
            unconfirmed.add(wants.get(wantIndex).label() + "：这一笔点完对不上账，搬走多少没能确认");
            if (wants.get(wantIndex).settleAfterDiverge()) settled[wantIndex] = true;
        } else if (result instanceof TransferRun.MoveResult.MenuGone(int submitted)) {
            if (submitted > 0) {
                unconfirmed.add(wants.get(wantIndex).label() + "：搬 " + moveAmount + " 件的这笔点了 " + submitted
                        + " 下，界面关上时没能等到结算结果");
            }
            return menuGone();
        }
        wantIndex = -1;
        return ActionStatus.progressed();
    }

    // 全部盘完：关箱前把此刻的界面内容交给收场记忆，再请游戏关上；关不上不冒充没开过，照常收场。
    private ActionStatus toClose(MenuContent.Reading reading) {
        endings.rememberBeforeClose(Optional.of(reading));
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 界面中途没了：收场记忆拿到空读数（各家照自己的记法记或忘），不再点关闭，按各家的收场算完。
    private ActionStatus menuGone() {
        endings.rememberBeforeClose(Optional.empty());
        stage = Stage.DONE;
        ending = endings.finishAfterMenuGone(tookAnything);
        return ending;
    }

    // 关界面：打开的一方负责真的关。
    private ActionStatus closeMenu(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        if (status instanceof ActionStatus.Failed) {
            endings.closeUnconfirmedNote().ifPresent(unconfirmed::add);
        }
        stage = Stage.DONE;
        ending = endings.finishAfterClosed(tookAnything);
        return ending;
    }
}
