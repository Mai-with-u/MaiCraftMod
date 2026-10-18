// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 一次要几样的执行：按单子逐样交给一次拿东西的动作去办，每样推进时把单子里其它还缺的
 * 带上当"顺路还要的"（开箱时一起拿）。已经拿够的跳过；一样弄不到记下原因接着弄下一样；
 * 全部办完时把还缺的摊平交回。已经拿到的照算，不因另一样失败就当成没拿。
 */
final class SeveralNeeds implements Action {

    /** 怎么为一样东西造一次拿东西的动作，并把其它还缺的带上当顺路还要的。 */
    @FunctionalInterface
    interface Starts {
        Action create(ItemRequest request, List<ItemRequest> alsoWanted);
    }

    private final List<ItemRequest> requests;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    private final Starts starts;
    /** 每样要拿到"身上原有加还缺的"那么多，按物品写法记；第一次推进时定下。 */
    private final Map<String, Integer> targets = new LinkedHashMap<>();
    /** 没弄到的那几样各一句结论。 */
    private final List<String> failures = new ArrayList<>();
    private Action current;
    private int index;
    private boolean started;

    SeveralNeeds(List<ItemRequest> requests, BackpackView backpack, OffhandContents offhand,
            ReadsItemTags tags, Starts starts) {
        this.requests = requests;
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
        this.starts = starts;
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (!started) {
            started = true;
            for (ItemRequest request : requests) {
                targets.putIfAbsent(request.wanted().specifier(),
                        CarriedItems.matching(backpack, offhand, request, tags) + request.count());
            }
        }
        if (current == null) {
            if (index >= requests.size()) return finish();
            ItemRequest request = requests.get(index);
            int remaining = remaining(request);
            if (remaining <= 0) {
                // 走在路上的某一样被顺手拿到了：这一样不再单独去弄。
                index++;
                return ActionStatus.progressed();
            }
            current = starts.create(request.just(remaining), othersThan(index));
        }
        ActionStatus status = current.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        if (status instanceof ActionStatus.Failed failed) {
            failures.add(requests.get(index).wanted().describe() + "：" + failed.problem().message());
        }
        current.close();
        current = null;
        index++;
        return ActionStatus.progressed();
    }

    // 这一样此刻还缺几件：目标减身上现有的，目标在第一次推进时按当时的身上定的。
    private int remaining(ItemRequest request) {
        Integer goal = targets.get(request.wanted().specifier());
        return goal == null ? 0 : Math.max(0, goal - CarriedItems.matching(backpack, offhand, request, tags));
    }

    // 除开正在弄的这样，其它几样这一刻还缺的：交给来源当"顺路还要的"。
    private List<ItemRequest> othersThan(int except) {
        List<ItemRequest> pending = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            if (i == except) continue;
            ItemRequest request = requests.get(i);
            int remaining = remaining(request);
            if (remaining > 0) pending.add(request.just(remaining));
        }
        return pending;
    }

    private ActionStatus finish() {
        List<String> missing = new ArrayList<>();
        for (ItemRequest request : requests) {
            int remaining = remaining(request);
            if (remaining > 0) missing.add("还差 " + remaining + " 个" + request.wanted().describe());
        }
        if (missing.isEmpty()) return ActionStatus.done();
        StringBuilder message = new StringBuilder("有几个没弄齐：").append(String.join("、", missing));
        for (String failure : failures) message.append("；").append(failure);
        return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, message.toString()));
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String describe() {
        StringBuilder what = new StringBuilder();
        for (ItemRequest request : requests) {
            if (!what.isEmpty()) what.append("、");
            what.append(request.wanted().describe());
        }
        return "一次要 " + what + (current == null ? "" : "：" + current.describe());
    }
}
