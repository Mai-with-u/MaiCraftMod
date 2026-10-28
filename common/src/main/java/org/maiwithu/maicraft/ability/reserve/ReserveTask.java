// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.reserve;

import java.util.List;

import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 预留任务：把这次的改动写进预留表，一刻内完成，不动角色。
 *
 * <p>每个结果都附上改完以后的整份预留和身上各有几件：LLM 不用另查一轮，就知道哪几样还缺、哪几样已经攒够。
 */
final class ReserveTask extends PhasedTask<ReserveTask.Phase> {

    /** 只有一个阶段：写完即结束。 */
    enum Phase { WRITE }

    private final ReserveInput input;
    private final ReservedItems reserved;
    /** 结果细节：tick 里定下，details 原样交出去。 */
    private ReserveDetails details;

    ReserveTask(ReserveInput input, ReservedItems reserved) {
        // 一刻内完成的小事，卡住判定只作兜底，不会真的等到。
        super("预留", Phase.WRITE, new ProgressTracker(20L, Long.MAX_VALUE));
        this.input = input;
        this.reserved = reserved;
    }

    /**
     * 结果细节。reserved 是改完以后的整份预留；cancelled 是这次撤掉的（写下时为空）。
     *
     * @param reserved  每一样：物品或标签、留够几件（全都留着时为空，all 为真）、身上现有几件
     * @param cancelled 这次撤掉的物品或标签
     */
    record ReserveDetails(List<ReservedNow> reserved, List<String> cancelled) implements ResultDetails {
    }

    /** 预留里的一样：留够几件（全都留着时 atLeast 为空、all 为真）、身上现有几件（标签按合计）。 */
    record ReservedNow(String item, Integer atLeast, Boolean all, int carried) {
    }

    @Override protected Action enter(Phase phase) {
        // 写预留是一刻内的小事，不需要动作。
        return null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (input.operation()) {
            case SET -> set();
            case CANCEL -> cancel(reserved.cancel(input.items()));
            case CANCEL_ALL -> cancel(reserved.cancelAll());
        };
    }

    // 写下：同一样再写就换成新的件数；结果说清现在一共预留了几样，角色自己做主时不动它们。
    private Next<Phase> set() {
        reserved.set(input.entries());
        details = new ReserveDetails(now(), List.of());
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "预留好了，现在一共预留 " + details.reserved().size() + " 样：角色自己做主时不动它们，"
                                + "下令的目标不够才用、用了从预留里扣")
                .details(details)
                .build());
    }

    // 撤掉：本来就没预留的那几样如实说没有，别的照撤。
    private Next<Phase> cancel(List<ReservedItems.Reservation> removed) {
        List<String> cancelled = removed.stream().map(ReservedItems.Reservation::item).toList();
        details = new ReserveDetails(now(), cancelled);
        List<String> absent = input.operation() == ReserveInput.Operation.CANCEL
                ? input.items().stream().filter(item -> !cancelled.contains(item)).toList() : List.of();
        String summary = cancelled.isEmpty() ? "没有撤掉什么：本来就没有这些预留"
                : "撤掉了 " + cancelled.size() + " 样预留，现在还预留 " + details.reserved().size() + " 样"
                        + (absent.isEmpty() ? "" : "；" + String.join("、", absent) + " 本来就没有预留");
        return Next.done(TaskResult.builder(TaskResult.Status.DONE, summary).details(details).build());
    }

    private List<ReservedNow> now() {
        return reserved.list().stream()
                .map(entry -> new ReservedNow(entry.item(), entry.atLeast(), entry.atLeast() == null ? true : null,
                        reserved.carriedFor(entry)))
                .toList();
    }

    @Override protected ResultDetails details() {
        return details == null ? ResultDetails.NONE : details;
    }
}
