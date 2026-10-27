// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.GoalEstimates;
import org.maiwithu.maicraft.kernel.event.ReadsReadySteps;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * sequence 做完一步时，后面还没开始的步骤里此刻不用走路就能做的那几步：按各能力的估算口估，读当前的角色。
 * 不等附近扫完，说不准的不列；角色不在世界里时给空。在客户端线程上调（步骤结束存盘就在那里）。
 */
final class ReadySequenceSteps implements ReadsReadySteps {

    private final GoalEstimates estimates;
    private final Supplier<PlayerContext> player;

    ReadySequenceSteps(AbilityRegistry abilities, Supplier<PlayerContext> player) {
        this.estimates = new GoalEstimates(abilities);
        this.player = player;
    }

    @Override public List<TaskEvent.ReadyStep> readyAfter(Goal sequence, int finished) {
        PlayerContext current = player.get();
        if (current == null || current.level() == null) return List.of();
        TickContext tick = TickContext.of(current.level().getGameTime(), current);
        return estimates.readyAfter(sequence, finished, tick).stream()
                .map(step -> new TaskEvent.ReadyStep(step.index() + 1, step.goal().ability(), step.goal().purpose(),
                        step.estimate().how()))
                .toList();
    }
}
