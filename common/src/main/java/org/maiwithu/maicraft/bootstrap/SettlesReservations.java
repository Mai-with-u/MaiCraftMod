// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 下令的目标结束时给预留结账：这个目标动了几件预留的，就从预留里扣几件，并在那几条变化上记清 from_reserved。
 * 只结一步一步做的那些目标自己的账；按顺序做事的整个目标由它的每一步各自结过，不再结一遍。其余原样交给真正的存储。
 */
final class SettlesReservations implements GoalRunStore {

    private final GoalRunStore delegate;
    private final ReservedItems reserved;

    SettlesReservations(GoalRunStore delegate, ReservedItems reserved) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.reserved = Objects.requireNonNull(reserved, "reserved");
    }

    @Override public long nextId() {
        return delegate.nextId();
    }

    @Override public void save(GoalRun run) {
        delegate.save(run);
    }

    @Override public List<GoalRun> unfinished() {
        return delegate.unfinished();
    }

    @Override public TaskResult settling(GoalRun run, TaskResult result) {
        return delegate.settling(run, settle(run, result));
    }

    // 有步骤的目标（按顺序做事）不结：它的变化就是各步的变化，每一步结束时已经结过。
    private TaskResult settle(GoalRun run, TaskResult result) {
        if (!run.goal().steps().isEmpty()) return result;
        int[] fromReserved = reserved.usedByOrder(result.changes());
        boolean any = false;
        List<Change> marked = new ArrayList<>(result.changes().size());
        for (int i = 0; i < fromReserved.length; i++) {
            Change change = result.changes().get(i);
            marked.add(fromReserved[i] > 0 ? change.withFromReserved(fromReserved[i]) : change);
            any |= fromReserved[i] > 0;
        }
        return any ? result.withChanges(marked) : result;
    }
}
