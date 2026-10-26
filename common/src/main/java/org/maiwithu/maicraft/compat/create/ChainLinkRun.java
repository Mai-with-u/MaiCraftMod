// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.InstallReport;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 连一条链的整个过程：拿锁链 → 先清掉上一回没连完留下的起点 → 点第一台 → 核对 → 点第二台；
 * 点过第一台以后没连成（被打断、点错、够不着），再清一次起点：清掉了记进变化，清不掉如实写进失败原因，
 * 告诉 LLM 手上的锁链还带着起点、下一次用锁链点链条输送机会先连到它。
 *
 * <p>起点只记在客户端 Create 的私有静态字段里，读不到；清法是手持锁链潜行对着空处真按一下使用键，
 * 清没清掉看 Create 在动作栏说没说"选区已清除"——没说就是手上本来没记着起点，不用清。
 * 连之前清不掉不拦着：第一下连上了残留的起点由核对那一步如实交回。任务被取消时来不及按，下一次连链条时先清。
 */
final class ChainLinkRun implements Action, InstallReport {

    /** 清起点那一下的结论。 */
    enum Cleared {
        /** Create 说了"选区已清除"：手上记着的起点清掉了。 */
        CLEARED,
        /** 按了没反应：手上本来没记着起点。 */
        NOTHING_TO_CLEAR,
        /** 没按成（身上没有锁链、四下都够得着东西、界面开着……）：起点还在不在说不清。 */
        COULD_NOT
    }

    private enum Stage { FETCH, CLEAR_BEFORE, LINK, CLEAR_AFTER, DONE }

    private static final String WHAT = "create:chain_conveyor";

    private final String label;
    private final BlockPos first;
    private final Supplier<Action> fetch;
    private final Supplier<Action> clearing;
    private final Function<Runnable, Action> linking;
    private final List<Change> changes = new ArrayList<>();
    private Stage stage = Stage.FETCH;
    private Action current;
    private boolean firstClicked;
    private Problem linkFailure;
    private ActionStatus ending;

    /**
     * @param first    第一台在哪：清不掉时告诉 LLM 起点多半记的是它
     * @param fetch    拿够锁链的动作
     * @param clearing 清一次起点的动作：手持锁链潜行对空处按使用键，Create 说清掉了算做完，没反应按游戏拒绝收场
     * @param linking  点第一台、核对、点第二台的动作；交给它的那一下要在第一台点上之后调，标记"点过第一台"
     */
    ChainLinkRun(String label, BlockPos first, Supplier<Action> fetch, Supplier<Action> clearing,
            Function<Runnable, Action> linking) {
        this.label = Objects.requireNonNull(label, "label");
        this.first = first.immutable();
        this.fetch = Objects.requireNonNull(fetch, "fetch");
        this.clearing = Objects.requireNonNull(clearing, "clearing");
        this.linking = Objects.requireNonNull(linking, "linking");
    }

    /** 清起点那一下的结论，按动作收场的样子认：做完是清掉了，游戏没反应是本来没有，别的是没按成。 */
    static Cleared cleared(ActionStatus status) {
        if (status instanceof ActionStatus.Done) return Cleared.CLEARED;
        if (status instanceof ActionStatus.Failed failed && failed.problem().kind() == Problem.Kind.REFUSED_BY_GAME) {
            return Cleared.NOTHING_TO_CLEAR;
        }
        return Cleared.COULD_NOT;
    }

    // 拿锁链 → 连之前清一次 → 连 → 没连成且点过第一台再清一次；每段做完再建下一段。
    @Override public ActionStatus tick(TickContext context) {
        if (stage == Stage.DONE) return ending;
        if (current == null) current = build(stage);
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        return switch (stage) {
            case FETCH -> status instanceof ActionStatus.Failed ? end(status) : go(Stage.CLEAR_BEFORE);
            case CLEAR_BEFORE -> {
                if (cleared(status) == Cleared.CLEARED) {
                    changes.add(new Change(Change.Kind.OTHER, WHAT, 1, "连之前清掉了上一回没连完、手上锁链还记着的接线起点"));
                }
                yield go(Stage.LINK);
            }
            case LINK -> !(status instanceof ActionStatus.Failed failed) ? end(ActionStatus.done())
                    : firstClicked ? clearAfter(failed.problem()) : end(status);
            case CLEAR_AFTER -> end(ActionStatus.failed(afterClearing(cleared(status), status)));
            case DONE -> ending;
        };
    }

    private Action build(Stage which) {
        return switch (which) {
            case FETCH -> fetch.get();
            case CLEAR_BEFORE, CLEAR_AFTER -> clearing.get();
            case LINK -> linking.apply(() -> firstClicked = true);
            case DONE -> throw new IllegalStateException("收场了还要建动作");
        };
    }

    private ActionStatus go(Stage next) {
        stage = next;
        return ActionStatus.progressed();
    }

    private ActionStatus end(ActionStatus how) {
        stage = Stage.DONE;
        ending = how;
        return how;
    }

    // 点过第一台没连成：Create 可能还记着它当起点，先清一次再按没连成收场。
    private ActionStatus clearAfter(Problem failure) {
        linkFailure = failure;
        return go(Stage.CLEAR_AFTER);
    }

    // 没连成的原因后面接上清起点的结论：清掉了记进变化；清不掉写清手上锁链还带着起点、下一次会怎样。
    private Problem afterClearing(Cleared result, ActionStatus status) {
        String tail = switch (result) {
            case CLEARED -> {
                changes.add(new Change(Change.Kind.OTHER, WHAT, 1, "没连成，清掉了手上锁链记着的接线起点"));
                yield "；已清掉手上锁链记着的接线起点，下回从头连";
            }
            case NOTHING_TO_CLEAR -> "；手上锁链没留下接线起点";
            case COULD_NOT -> "；清不掉手上锁链记着的接线起点（" + reason(status) + "）：手上的锁链还带着它"
                    + "（第一下点的是 " + first.toShortString() + "），下一次手持锁链点链条输送机会先连到它，"
                    + "再连链条时角色会先清一次";
        };
        return Problem.of(linkFailure.kind(), linkFailure.message() + tail, linkFailure.suggestion());
    }

    private static String reason(ActionStatus status) {
        return status instanceof ActionStatus.Failed failed ? failed.problem().message() : "没按成";
    }

    @Override public List<Change> changes() {
        return List.copyOf(changes);
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public boolean stopSafely() {
        return current != null && current.stopSafely();
    }

    @Override public boolean stoppedSafely() {
        return current == null || current.stoppedSafely();
    }

    @Override public void close() {
        if (current != null) current.close();
        current = null;
    }

    @Override public String describe() {
        String doing = switch (stage) {
            case FETCH -> "拿锁链";
            case CLEAR_BEFORE -> "先清掉上一回没连完留下的起点";
            case LINK -> current == null ? "连" : current.describe();
            case CLEAR_AFTER -> "没连成，清掉手上锁链记着的起点";
            case DONE -> "收场";
        };
        return label + "：" + doing;
    }
}
