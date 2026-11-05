// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 回头看的临时任务：转过去看着拍了角色的人 2.5 秒，他走动就跟着看；这期间他接着冲角色做肢体动作就再看 2.5 秒，
 * 最长 6 秒。看完结束，手上的活从原地接着做。他不见了（走远、下线）就提前结束。
 */
public final class LookBackTask extends PhasedTask<LookBackTask.Phase> {

    /** 只有一段：看着他。 */
    public enum Phase { LOOK }

    /** 看多久（刻）：2.5 秒。 */
    static final int LOOK_TICKS = 50;
    /** 最长看多久（刻）：6 秒。 */
    static final int MAX_TICKS = 120;

    private final PokesFelt pokes;
    private final PokesFelt.Poke poke;
    private final long since;
    private final FacePlayer face;

    LookBackTask(PokesFelt pokes, PokesFelt.Poke poke, long since) {
        super("回头看", Phase.LOOK, new ProgressTracker(MAX_TICKS + 40, MAX_TICKS + 40));
        this.pokes = pokes;
        this.poke = poke;
        this.since = since;
        this.face = new FacePlayer(poke.entityId());
    }

    @Override protected Action enter(Phase phase) {
        return face;
    }

    // 每刻转向他；他最近一下冲角色做肢体动作之后再看 2.5 秒，从开始看起不超过 6 秒。
    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        runAction(context);
        long now = context.gameTick();
        long until = lookUntil(since, pokes.lastActTowardMe(poke.who()));
        if (now - since > 0 && !face.stillThere()) {
            return Next.done(TaskResult.done("回头看玩家 " + poke.name() + "：他不见了"));
        }
        if (now >= until) {
            return Next.done(TaskResult.done("回头看了玩家 " + poke.name() + " " + (now - since) / 20.0 + " 秒"));
        }
        return Next.stay();
    }

    /** 看到哪一刻：他最近一下冲角色做动作之后再看 2.5 秒，从开始看起不超过 6 秒。纯函数。 */
    static long lookUntil(long since, long lastActTowardMe) {
        return Math.min(since + MAX_TICKS, Math.max(since, lastActTowardMe) + LOOK_TICKS);
    }

    @Override protected String describePhase(Phase value) {
        return "回头看着玩家 " + poke.name();
    }
}
