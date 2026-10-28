// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

import org.maiwithu.maicraft.kernel.child.ChildTaskRunner;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 目标的收尾：LLM 要暂停或取消、手上的任务此刻停下不安全（飞着、开着车）时，先叫它到安全的地方
 * （就近落地、刹停），照常推进到它停稳、自己结束或等满两分钟，再照原先要的暂停或取消。
 * 这里只记收尾到了哪一步、什么时候到头；真正的暂停与取消由目标推进器做。
 */
final class GoalWindDown {

    /** 收尾最多等多久（刻）：两分钟还没停稳就照原先要的停。 */
    static final long SAFE_STOP_TICKS = 20L * 120;

    /** 收尾之后要做的那件事。 */
    enum Ending { PAUSE, CANCEL }

    /** 先收尾再停的那件事（暂停或取消）；没在收尾为 null。 */
    private Ending ending;
    /** 这次收尾已经叫过手上的任务（中途被生存需求打断过要再叫一次）。 */
    private boolean asked;
    /** 收尾从哪一刻开始；还没推进过为 Long.MIN_VALUE。 */
    private long since = Long.MIN_VALUE;

    /** 此刻在收尾（先到安全的地方再暂停或取消）。 */
    boolean active() {
        return ending != null;
    }

    /** 收尾完要做的那件事；没在收尾为 null。 */
    Ending ending() {
        return ending;
    }

    // 手上的任务说它要先收尾才记下这件事；没有任务、任务说现在就能停都是现在就停。
    boolean begin(Ending kind, ChildTaskRunner child) {
        if (child == null || child.finished() || !child.stopSafely()) {
            return false;
        }
        ending = kind;
        asked = true;
        since = Long.MIN_VALUE;
        return true;
    }

    // 收尾等着暂停时 LLM 又要取消：改成取消，收尾照旧等手上的任务停稳。
    void cancelInstead() {
        ending = Ending.CANCEL;
    }

    // 不收尾了：收尾时 LLM 又要恢复，或已经照原先要的停下。
    void drop() {
        ending = null;
    }

    // 收尾中被生存需求打断：手上的任务松过手，回来要再叫它收尾一次。
    void interrupted() {
        asked = false;
    }

    /**
     * 收尾中的一刻：被打断过就再叫一次；手上的任务停稳了、自己结束了或收尾太久，就到头了——
     * 交回原先要的暂停或取消，并不再收尾；还没到头为 null。
     */
    Ending tick(TickContext context, ChildTaskRunner child) {
        if (since == Long.MIN_VALUE) {
            since = context.gameTick();
        }
        if (child != null && !child.finished() && !asked) {
            asked = true;
            if (!child.stopSafely()) {
                return finish();
            }
        }
        boolean over = child == null || child.finished() || child.stoppedSafely()
                || context.gameTick() - since >= SAFE_STOP_TICKS;
        return over ? finish() : null;
    }

    private Ending finish() {
        Ending kind = ending;
        ending = null;
        return kind;
    }
}
