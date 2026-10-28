// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 睡觉的结算只认实际睡着：临时休息代睡了这一觉时，目标按睡着收场，
 * 不拿醒来后的白天现场判出"现在睡不了"（真睡着了结果却说睡不了，与世界事实相反）。
 */
class SleepModuleSleptSettlementTest {

    private static TaskResult sleepResult(boolean enteredSleep, boolean nightSkipped) {
        return TaskResult.builder(TaskResult.Status.DONE, "睡觉任务")
                .details(new SleepDetails(enteredSleep, SleepDetails.BedSource.WORLD, null, nightSkipped, false))
                .build();
    }

    @Test
    void 临时任务真的睡着了就认出来() {
        TaskResult temp = sleepResult(true, true);
        assertTrue(SleepModule.sleptSettlement(List.of(temp)).isPresent());
        assertEquals(temp, SleepModule.sleptSettlement(List.of(temp)).get());
    }

    @Test
    void 没睡着的结果不算() {
        TaskResult daytime = TaskResult.builder(TaskResult.Status.PARTIAL, "现在睡不了，约 11 分钟后可睡")
                .problem(Problem.of(Problem.Kind.WRONG_TIME, "现在已经不是能睡的时间了", null))
                .details(new SleepDetails(false, SleepDetails.BedSource.WORLD, true, false, false))
                .build();
        assertTrue(SleepModule.sleptSettlement(List.of(daytime)).isEmpty());
    }

    @Test
    void 空的与无关的结果都算没睡过() {
        assertTrue(SleepModule.sleptSettlement(List.of()).isEmpty());
        TaskResult 备床 = TaskResult.builder(TaskResult.Status.DONE, "拿到了一张床").build();
        assertTrue(SleepModule.sleptSettlement(List.of(备床)).isEmpty());
    }

    @Test
    void 多条结果取最新睡着的那条() {
        TaskResult 早先 = sleepResult(true, true);
        TaskResult 后来 = sleepResult(true, false);
        assertEquals(后来, SleepModule.sleptSettlement(List.of(早先, 后来)).get());
    }

    @Test
    void 睡着了的收场结论带着跳夜事实() {
        TaskResult 结论 = SleepModule.sleptResult(sleepResult(true, true));
        assertEquals(TaskResult.Status.DONE, 结论.status());
        assertEquals("已经睡过一觉了，夜晚也跳过了", 结论.summary());
        assertTrue(((SleepDetails) 结论.details()).nightSkippedBySleep());
    }

    @Test
    void 睡着了但没跳夜的收场结论不带跳夜() {
        TaskResult 结论 = SleepModule.sleptResult(sleepResult(true, false));
        assertEquals("已经睡过一觉了", 结论.summary());
        assertFalse(((SleepDetails) 结论.details()).nightSkippedBySleep());
    }
}
