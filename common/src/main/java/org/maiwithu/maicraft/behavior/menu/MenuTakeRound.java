// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 对着打开的界面按要货单跑完一轮取货：逐样盘"这一刻还缺几件、下一笔从哪格拿几件"，
 * 手上的笔交给一笔的执行逐刻点、等结清；取不动的那一样这一趟先放下，别的接着试，
 * 全部盘完才请游戏关上界面，关上前把此刻的界面内容交给收场记忆。
 * 点出去了却没能确认的交互（纹丝不动、对不上账、界面中途没了、收尾时手上还有一笔）
 * 一句一条记在这里，来源用一条接缝把账原样交回。
 *
 * <p>各家来源只提供差异点，都长在构造时要的东西里：每样怎么认、这一刻还缺几件、
 * 这一刻下一笔怎么出怎么发（机器料箱留一半的限额、网络里整组取的模组协议发法在这里换）、
 * 关界面前还要做的一步（把光标上的东西放回网络）、关箱前记什么、
 * 界面关上后这一轮算完还是接着开下一只、要不要不关界面就交给来源自己收。
 * 只管界面认下来之后的一段：走到、点开、等第一份读数是打开动作的事；
 * 开多只包一只只换着开，也是来源自己的事，一轮只对一份打开的界面。
 */
public final class MenuTakeRound {

    /** 要货单里的一样：怎么认它、这一刻还缺几件；留半限额、下一笔怎么出这类差异点长在实现里。 */
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

        /**
         * 这一刻这一样的下一步：默认按容器槽位盘，由流程挑下一格、点格子搬；
         * 两侧不是槽位（例如网络存货）或发法不是点格子的来源在这里换自己的盘法与发法。
         */
        default Step next(MenuContent.Reading reading, int needed, TickContext tick) {
            return Step.FromSlots.SLOTS;
        }

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

        /** 关界面前还要做的一步（把光标上的东西放好之类）：回"还在做"就下一刻再问；默认没有，直接去关。 */
        default ActionStatus beforeClose(TickContext tick, MenuContent.Reading reading) {
            return ActionStatus.done();
        }

        /** 全部盘完了却不由这一轮关界面（来源在交回前把自己的活收好，例如关掉终端重开）：回真就不记不关直接结束。 */
        default boolean finishWithoutClosing() {
            return false;
        }

        /** 界面关上之后这一轮怎么算完：完成、失败，还是接着开下一只。 */
        ActionStatus finishAfterClosed(boolean tookAnything);

        /** 界面中途没了（点着一半被合上）这一轮怎么算完：拿走多少由引擎清点，这里不再点关闭。 */
        ActionStatus finishAfterMenuGone(boolean tookAnything);

        /** 关界面的动作没能确认关上时多交代的一句；没有就不交代。 */
        default Optional<String> closeUnconfirmedNote() {
            return Optional.empty();
        }
    }

    /** 一轮里的下一步：出一笔、等一段没有笔可点的活、按容器槽位盘、还是这一趟放下。 */
    public sealed interface Step {

        /** 按容器槽位盘：流程用要货单的匹配谓词挑下一格，点格子交给搬运执行。 */
        enum FromSlots implements Step { SLOTS }

        /** 把这一笔交给流程逐刻推进，怎么发怎么核对长在执行里。 */
        record Do(MoveExecution move) implements Step {
        }

        /** 这一刻没有笔可点：来源在做或等着一段自己的活，把动作状态原样交回。 */
        record Wait(ActionStatus status) implements Step {
        }

        /** 这一样这一趟没拿成也不再点。 */
        enum GiveUp implements Step { GIVE_UP }

        /** 这一样这一趟没拿成也不再点的答案。 */
        static GiveUp giveUp() {
            return GiveUp.GIVE_UP;
        }
    }

    /**
     * 一笔的执行：这一笔怎么逐刻推进、怎么算没点完；点格子的走搬运执行，
     * 经模组协议之类别的发法由来源自己发、自己核对，结论与撒手的口径与搬运执行一致。
     */
    public interface MoveExecution {

        /** 这一笔按计划搬几件：没能确认的记账里说清是多大的笔。 */
        int plannedAmount();

        /** 这一刻推进这一笔：点出去算进展，等结算还在做，出了结论交给记账钩子接住。 */
        ActionStatus tick(TickContext tick, Function<TransferRun.MoveResult, ActionStatus> settled);

        /** 中途撒手：点出去没等到结果的有几下。 */
        int abandon();

        /** 这一笔没点完且光标上拿着东西：半途撒手会把拆出来的尾数交给关界面的流程。 */
        boolean unsafeToStop();
    }

    /** 点格子的笔：交给搬运执行逐刻点、按核对式等结清。 */
    public static MoveExecution clicks(TransferRun run, TransferRun.Move move, int plannedAmount) {
        run.begin(move);
        return new MoveExecution() {
            @Override public int plannedAmount() { return plannedAmount; }

            @Override public ActionStatus tick(TickContext tick,
                    Function<TransferRun.MoveResult, ActionStatus> settled) {
                return run.tickAction(settled::apply);
            }

            @Override public int abandon() { return run.abandon(); }

            @Override public boolean unsafeToStop() { return run.unsafeToStop(); }
        };
    }

    /** 一轮取货的先后：盘着搬、关界面前的一步、关界面、收场。 */
    private enum Stage { MOVE, PRECLOSE, CLOSE, DONE }

    private final OpenedMenu menu;
    private final List<Want> wants;
    private final Endings endings;
    /** 点格子的笔共用的搬运执行；别的发法在各自的执行里逐刻推进。 */
    private final TransferRun run;
    /** 正在点的一笔；没有在点的为 null。 */
    private MoveExecution stroke;
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
     * 推进一刻：手上有笔先跑完；空手且读数在手就盘下一笔，盘不出要点的就记收场、走关界面前的一步、关界面；
     * 读数没了（界面被合上）照界面没了收场，等读数、开界面的宽限由调用方把着，读数在手才叫进来。
     */
    public ActionStatus tick(TickContext tick, Optional<MenuContent.Reading> reading) {
        return switch (stage) {
            case MOVE -> move(tick, reading);
            case PRECLOSE -> preClose(tick);
            case CLOSE -> closeMenu(tick);
            case DONE -> ending;
        };
    }

    /** 手上还有一笔没出结论：调用方这刻把推进让给一轮取货，别急着盘下一笔或收场。 */
    public boolean movePending() {
        return stroke != null;
    }

    /** 界面进入收场的路上（关界面前的一步、正在关或已收场）：来源的描述照实说"关上"。 */
    public boolean atClosing() {
        return stage != Stage.MOVE;
    }

    /** 这一轮已经收场（结论已经给出）：再叫收尾就不用撒手了。 */
    public boolean done() {
        return stage == Stage.DONE;
    }

    /** 点出去了却没能确认结果的交互，一句一笔。 */
    public List<String> unconfirmedFacts() {
        return List.copyOf(unconfirmed);
    }

    /** 点出去了的一笔下还拿着东西时停不得：半途撒手会把拆出来的尾数交给关界面的流程。 */
    public boolean unsafeToStop() {
        return stroke != null && stroke.unsafeToStop();
    }

    /**
     * 中途撒手（总期限到、被收尾）：手上还有一笔的，点出去没等到结果的那几下记没能确认；
     * 一下都没发出去的不算，那只是没点成。
     */
    public void abandonAndNote(String circumstance) {
        if (stroke == null) {
            wantIndex = -1;
            return;
        }
        int submitted = stroke.abandon();
        stroke = null;
        if (submitted > 0) {
            unconfirmed.add(wants.get(wantIndex).label() + "：搬 " + moveAmount + " 件的这笔点了 " + submitted
                    + " 下，" + circumstance + "没能等到结算结果");
        }
        wantIndex = -1;
    }

    /** 中途撒手但不记账（来源自己决定怎么交代）：手上的一笔不再点了。 */
    public void abandonQuietly() {
        if (stroke != null) stroke.abandon();
        stroke = null;
        wantIndex = -1;
    }

    /**
     * 到点收场（总期限到、被叫停）：不再盘下一笔，手上的一笔照实记账，
     * 然后照常走关界面前的一步、关界面、出结论；界面已经没了就照界面没了收场。
     */
    public ActionStatus wrapUp(String circumstance) {
        abandonAndNote(circumstance);
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) return menuGone();
        return toClose(reading.get());
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

    // 按量搬：手上有笔先跑完；空手时按要货单盘下一笔；读数没了照界面没了收场。
    private ActionStatus move(TickContext tick, Optional<MenuContent.Reading> reading) {
        if (stroke != null) return stroke.tick(tick, this::onSettled);
        if (reading.isEmpty()) return menuGone();
        MenuContent.Reading open = reading.get();
        if (!started) {
            started = true;
            // 起点与限额在第一份读数上一次定死：拿走多少按它与当刻的差算，不在中途改口径。
            for (Want want : wants) want.firstReading(open);
        }
        for (int i = 0; i < wants.size(); i++) {
            if (settled[i]) continue;
            Want want = wants.get(i);
            int needed = want.stillNeeded(open);
            if (needed <= 0) {
                settleWithout(i, want);
                continue;
            }
            // 盘这一刻的下一步：等一段的把状态原样交回，下一刻再问；放下的别的样接着试，全盘完才收场。
            Step step = want.next(open, needed, tick);
            if (step instanceof Step.Wait(ActionStatus status)) return status;
            if (step instanceof Step.GiveUp) {
                settleWithout(i, want);
                continue;
            }
            wantIndex = i;
            if (step instanceof Step.FromSlots) {
                // 容器槽位的盘法：按匹配谓词挑下一格，点格子交给搬运执行，动手前的证据由它冻结。
                Optional<ContainerTakePlan.Move> next = ContainerTakePlan.next(open, want.matcher(), needed);
                if (next.isEmpty()) {
                    settleWithout(i, want);
                    continue;
                }
                moveAmount = next.get().amount();
                stroke = clicks(run, new TransferRun.Move(next.get().clicks(),
                        new TransferRun.Check.IntoBackpack(want.matcher(), moveAmount), want.label()), moveAmount);
            } else {
                Step.Do done = (Step.Do) step;
                moveAmount = done.move().plannedAmount();
                stroke = done.move();
            }
            return stroke.tick(tick, this::onSettled);
        }
        return toClose(open);
    }

    // 这一样这一趟盘完（拿够了、取不动了）：不再点，给来源一个交代原因的机会。
    private void settleWithout(int index, Want want) {
        settled[index] = true;
        want.gaveUp();
    }

    // 一笔出了结论：结清接着盘下一笔；点出去了身上一直没多（多半背包放不下）记下没能确认，
    // 这一样这一趟先放下；对不上或光标没放下来的，这一笔搬走多少说不清，按各家的规矩接着盘或收场。
    private ActionStatus onSettled(TransferRun.MoveResult result) {
        stroke = null;
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

    // 全部盘完：要来源自己收的先问一句（它把自己的活收好，这一轮不记不关）；照常的先记收场记忆，再走关界面前的一步。
    private ActionStatus toClose(MenuContent.Reading reading) {
        if (endings.finishWithoutClosing()) {
            stage = Stage.DONE;
            ending = endings.finishAfterClosed(tookAnything);
            return ending;
        }
        endings.rememberBeforeClose(Optional.of(reading));
        stage = Stage.PRECLOSE;
        return ActionStatus.progressed();
    }

    // 关界面前的一步（放好光标之类）：来源说还没做完就再等一刻；界面中途没了照界面没了收场。
    private ActionStatus preClose(TickContext tick) {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) return menuGone();
        ActionStatus status = endings.beforeClose(tick, reading.get());
        if (status instanceof ActionStatus.Running) return status;
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

    // 关界面：打开的一方负责关。关不上不冒充没开过，照常收场。
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
