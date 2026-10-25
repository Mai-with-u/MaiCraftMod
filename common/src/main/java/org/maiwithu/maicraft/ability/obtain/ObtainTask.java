// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.engine.StartsAcquisition;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 拿东西的任务：每样的 count 是"这次再多拿几件"，身上原有的不算——先逐样记下身上原有几件，
 * 再把整张单子交给拿到物品的引擎：问遍来源、按代价挑路、逐样重新清点，每样都多出自己要的件数才算拿够。
 * 一次要几样时引擎逐样推进；哪一样走在路上被开箱顺手拿到了，就不再为它单独去弄。
 * 引擎做完算拿到；引擎认输时，这次已经拿到几件照实结算——
 * 部分拿到是 partial 加还差几件，一件没拿到才是失败，两条路不混淆。
 */
final class ObtainTask extends PhasedTask<ObtainTask.Phase> {

    /** 任务的阶段：先清点，再交给引擎。 */
    enum Phase { COUNT, ACQUIRE }

    /** 连续一分钟没有真实进展算卡住；整件事最多做半小时（引擎内部的耐心另算）。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;
    private static final long MAX_TICKS = 20L * 60 * 30;

    private final StartsAcquisition acquisition;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final ObtainItems input;
    /** 任务开始时每样身上有几件；结算"这次拿到几件"的基准。 */
    private final Map<String, Integer> carriedAtStart = new LinkedHashMap<>();
    /** 开工时每样按物品分开的件数：要一类时算拿到的是哪几样。 */
    private final Map<String, Map<String, Integer>> byItemAtStart = new LinkedHashMap<>();
    /** 实际拿到东西的途径，按拿到先后排，一条不重复。 */
    private final Set<String> obtainedVia = new LinkedHashSet<>();

    ObtainTask(ObtainItems input, StartsAcquisition acquisition, BackpackView backpack,
            OffhandContents offhand, ReadsItemTags tags) {
        super("拿东西", Phase.COUNT, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.input = input;
        this.acquisition = acquisition;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
    }

    @Override protected Action enter(Phase phase) {
        // 清点是只读判断，不需要动作；引擎的动作在进入 ACQUIRE 时才发起，许可与 LLM 给的掉落线索随输入一起带过来。
        // 来源动作里点了但没能确认结果的交互、途中腾地方存了丢了什么，引擎直接记进本任务的结果。
        return phase == Phase.ACQUIRE
                ? acquisition.needSeveral(input.requests(), input.permissions(), input.scope(),
                        obtainedVia::add, records())
                : null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case COUNT -> countPhase();
            case ACQUIRE -> acquirePhase(context);
        };
    }

    // 先逐样记下身上原有几件：这次拿到几件按它算，原有的不算这次拿到的。
    private Next<Phase> countPhase() {
        int total = 0;
        int wanted = 0;
        for (ItemRequest request : input.requests()) {
            String key = request.wanted().specifier();
            carriedAtStart.put(key, CarriedItems.matching(backpack, offhand, request, tags));
            byItemAtStart.put(key, CarriedItems.matchingByItem(backpack, offhand, request.wanted(), tags));
            total += carriedAtStart.get(key);
            wanted += request.count();
        }
        return Next.go(Phase.ACQUIRE, "身上原有 " + total + " 个，再去各来源弄 " + wanted + " 个");
    }

    // 引擎逐刻推进：做完重新清点结算；认输时按这次拿到几件分别落到 partial 或失败。
    private Next<Phase> acquirePhase(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settle(null);
            case ActionStatus.Failed failed -> settle(failed.problem());
        };
    }

    private Next<Phase> settle(Problem failure) {
        List<String> parts = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int gainedInAll = 0;
        int carriedInAll = 0;
        for (ItemRequest request : input.requests()) {
            int carried = CarriedItems.matching(backpack, offhand, request, tags);
            int gained = carried - carriedAtStart.getOrDefault(request.wanted().specifier(), 0);
            gainedInAll += Math.max(0, gained);
            carriedInAll += carried;
            if (gained > 0) {
                // 已确认的入包不因结果不合预期就记成"不知道"：这次拿到几件如实记进 changes。
                recordChange(Change.of(Change.Kind.ITEM_GAINED, request.wanted().specifier(), gained));
                parts.add(gainedText(request, gained));
            }
            if (gained < request.count()) {
                missing.add("还差 " + (request.count() - Math.max(0, gained)) + " 个" + request.wanted().describe());
            }
        }
        if (failure == null) {
            String got = parts.isEmpty()
                    ? "拿到了：这次经 " + viaText()
                    : "拿到了：这次经 " + viaText() + " 拿到 " + (input.requests().size() == 1
                            ? parts.getFirst() + "，身上现在有 " + carriedInAll + " 个"
                            : String.join("、", parts));
            return Next.done(TaskResult.builder(TaskResult.Status.DONE, got).details(details()).build());
        }
        if (gainedInAll > 0) {
            String got = input.requests().size() == 1
                    ? "部分拿到：经 " + viaText() + " 拿到 " + parts.getFirst()
                    : "部分拿到：这次经 " + viaText() + " 拿到 " + String.join("、", parts);
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                    got + (input.requests().size() == 1 ? "，" : "；") + String.join("、", missing))
                    .problem(failure)
                    .remaining(List.copyOf(missing))
                    .details(details()).build());
        }
        return Next.fail(failure);
    }

    // 这一样拿到几件的写法：要的是一类（标签）时括号里说清拿到的是哪几样。
    private String gainedText(ItemRequest request, int gained) {
        return gained + " 个" + request.wanted().describe() + whichOnes(request);
    }

    /**
     * 卡住的收尾说明不带逐层嵌套的进行经过：进行中那句一层套一层（第几步、第几种做法），
     * 倒给 LLM 只会把真正的问题埋进括号堆里（实机里嵌了十一层）。结论一句；
     * 这一场试过哪些做法、各为什么不成，由拿东西的引擎摊平讲一次。
     */
    @Override protected Problem noProgressProblem(ProgressTracker.Status status) {
        if (!(status instanceof ProgressTracker.Status.Stuck stuck)) {
            return super.noProgressProblem(status);
        }
        StringBuilder message = new StringBuilder("推进了 ").append(stuck.idleTicks())
                .append(" 刻都没有新进展，做不下去了");
        String tried = acquisition.triedSoFar();
        if (!tried.isEmpty()) message.append("；这一场试过的：").append(tried);
        return Problem.of(Problem.Kind.STUCK, message.toString());
    }

    @Override protected ResultDetails details() {
        return new ObtainedVia(List.copyOf(obtainedVia));
    }

    // 要的是一类（标签）时说清拿到的是哪几样：要"能吃的"拿回来面包还是生牛肉，下一步怎么吃、要不要烤不一样。
    private String whichOnes(ItemRequest request) {
        if (!request.wanted().isTag()) return "";
        List<String> parts = new ArrayList<>();
        Map<String, Integer> before = byItemAtStart.getOrDefault(request.wanted().specifier(), Map.of());
        CarriedItems.matchingByItem(backpack, offhand, request.wanted(), tags).forEach((itemId, count) -> {
            int gained = count - before.getOrDefault(itemId, 0);
            if (gained > 0) parts.add(itemId + " " + gained + " 个");
        });
        return parts.isEmpty() ? "" : "（" + String.join("、", parts) + "）";
    }

    // 一条都没拿到时结果里不编途径：summary 说清是失败，细节给空列表。
    private String viaText() {
        return obtainedVia.isEmpty() ? "身上已有的" : String.join("、", obtainedVia);
    }
}
