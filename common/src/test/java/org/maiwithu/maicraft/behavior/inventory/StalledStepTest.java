// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 换手卡在提交前的判定表：期限以内接着等；卡满一步的期限先从头再打算一回；再卡死就认输。
 * 实机 005 备船的教训：这一层原先没有期限，备手的十秒总预算被无期限的等待整段烧光，只剩一句笼统超时。
 */
class StalledStepTest {

    @Test
    void withinTheLimitItKeepsWaitingNoMatterHowManyRestartsHappened() {
        assertEquals(StalledStep.Verdict.KEEP_WAITING, StalledStep.decide(0, 0));
        assertEquals(StalledStep.Verdict.KEEP_WAITING, StalledStep.decide(StalledStep.WAIT_LIMIT_TICKS, 0));
        assertEquals(StalledStep.Verdict.KEEP_WAITING, StalledStep.decide(StalledStep.WAIT_LIMIT_TICKS, 3));
    }

    @Test
    void pastTheLimitItStartsOverOnceBeforeGivingUp() {
        assertEquals(StalledStep.Verdict.START_OVER, StalledStep.decide(StalledStep.WAIT_LIMIT_TICKS + 1, 0));
        assertEquals(StalledStep.Verdict.START_OVER, StalledStep.decide(600, 0));
    }

    @Test
    void aSecondStallPastTheLimitGivesUp() {
        assertEquals(StalledStep.Verdict.GIVE_UP, StalledStep.decide(StalledStep.WAIT_LIMIT_TICKS + 1, 1));
        assertEquals(StalledStep.Verdict.GIVE_UP, StalledStep.decide(600, 5));
    }
}
