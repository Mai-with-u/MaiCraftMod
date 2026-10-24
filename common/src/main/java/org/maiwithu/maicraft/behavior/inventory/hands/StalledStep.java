// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

/**
 * 换手一步卡在提交前怎么办：开不出背包界面、轮不到出手机会、画面被占这类等待没有确认可等，
 * 卡满一步的期限就从头再打算一回（重新看东西在哪一格、重新挑格开界面），再卡死就认输。
 * 原先这类等待没有期限：实机停在崖边备船，背包界面一直没被认作就绪，十秒总预算被整段烧光，
 * 最后只报得出一句笼统的超时，落地救援整个没跑起来。
 */
final class StalledStep {

    /** 提交前最多白等这么久，与每步确认的期限（60 刻）同长。 */
    static final int WAIT_LIMIT_TICKS = 60;
    /** 从头再打算最多这么多次：次次都卡死是现场有问题，不是多等能自己好的。 */
    static final int MAX_RESTARTS = 1;

    /** 这一刻怎么办：接着等、从头再打算、认输。 */
    enum Verdict { KEEP_WAITING, START_OVER, GIVE_UP }

    private StalledStep() {}

    /**
     * 判定卡住的提交前等待该等、该重来还是该认输。
     *
     * @param waitedTicks 这一步已经卡在提交前多久
     * @param restarts    已经从头重来过几次
     */
    static Verdict decide(long waitedTicks, int restarts) {
        if (waitedTicks <= WAIT_LIMIT_TICKS) return Verdict.KEEP_WAITING;
        return restarts < MAX_RESTARTS ? Verdict.START_OVER : Verdict.GIVE_UP;
    }
}
