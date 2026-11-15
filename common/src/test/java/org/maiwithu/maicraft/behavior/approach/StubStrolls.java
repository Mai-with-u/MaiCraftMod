// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 直走替身：默认给空，靠近照旧走寻路（既有测试的节拍不变）；打开开关后对发起的短途给按脚本吐状态的直走，
 * Done 时把世界替身的角色挪到目标格，模拟真的走了过去。
 */
final class StubStrolls implements StraightWalks {

    /** 发起过的直走目标（按站位底面中心记）。 */
    final List<Vec3> begun = new ArrayList<>();
    /** 每次发起时目标是否比出发高一格。 */
    final List<Boolean> climbs = new ArrayList<>();
    final Deque<ActionStatus> script = new ArrayDeque<>();
    /** 收掉（close）过的直走次数。 */
    int closes;
    /** 是否对短途给直走动作：默认给空。 */
    boolean strollable;
    private final StubWorld world;

    StubStrolls(StubWorld world, ActionStatus... steps) {
        this.world = world;
        for (ActionStatus status : steps) script.add(status);
    }

    @Override
    public Optional<Action> toward(BlockPos from, BlockPos to, ApproachWorldView view, ProtectedCells guarded) {
        if (!strollable) return Optional.empty();
        begun.add(Vec3.atBottomCenterOf(to));
        climbs.add(to.getY() > from.getY());
        return Optional.of(new StubStroll(to));
    }

    /** 一趟直走：按脚本推进；Done 时把角色挪到目标格并落地。 */
    private final class StubStroll implements Action {
        private final BlockPos target;

        StubStroll(BlockPos target) {
            this.target = target;
        }

        @Override public ActionStatus tick(TickContext context) {
            ActionStatus status = script.isEmpty() ? ActionStatus.done() : script.poll();
            if (status instanceof ActionStatus.Done) {
                world.feet = target;
                world.grounded = true;
            }
            return status;
        }

        @Override public void close() {
            closes++;
        }

        @Override public String describe() {
            return "直走替身";
        }
    }
}
