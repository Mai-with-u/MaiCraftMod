// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.night;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备武器的任务：夜里露天打不过、床又要出门找时，先用拿东西的引擎做一把手边做得出的剑或斧。
 * 只把武器弄到手；挥不挥、怎么打由自卫与战斗管。做完下一刻夜晚分流按新身板重新评估。
 */
final class PrepareWeaponTask extends PhasedTask<PrepareWeaponTask.Phase> {

    /** 任务的进度：只有弄武器一个阶段。 */
    enum Phase { OBTAIN }

    /** 两分钟没进展算卡住：和备床一样，合成与取物每步都有确认，两分钟没动静就是绕不出来了。C 玩家常识。 */
    static final long STUCK_AFTER_TICKS = 20L * 60 * 2;
    /** 最多备三分钟：武器是顺手的一件，超了就去备床。C 玩家常识。 */
    static final long MAX_TICKS = 20L * 60 * 3;

    private final ItemNeeds needs;
    private final ItemRequest weapon;
    private final Permissions permissions;

    PrepareWeaponTask(ItemNeeds needs, ItemRequest weapon, Permissions permissions) {
        super("备武器", Phase.OBTAIN, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.needs = Objects.requireNonNull(needs, "needs");
        this.weapon = Objects.requireNonNull(weapon, "weapon");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    @Override
    protected Action enter(Phase phase) {
        // 缺木板、木棍、工作台由引擎按同一套办法补齐；途中的变化记进这个临时任务的结果。
        return needs.actionFor(weapon, permissions, records());
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        // 弄不到：带着引擎给的原因结束，夜晚分流这一晚改去备床。
        if (status instanceof ActionStatus.Failed failed) return Next.fail(failed.problem());
        return Next.done(TaskResult.done("天黑了，先备好了一把 " + weapon.wanted().specifier()));
    }
}
