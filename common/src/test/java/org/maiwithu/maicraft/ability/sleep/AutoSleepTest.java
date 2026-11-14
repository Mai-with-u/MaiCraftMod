// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.survival.night.AutoSleep;
import org.maiwithu.maicraft.behavior.survival.night.NightfallNeed;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 夜里自动去睡临时关闭与到期恢复回归测试（第 59 场 C8）：
 * 守夜关掉后带期限，到期自动恢复为开，不再整场永久关死。
 */
class AutoSleepTest {

    @Test
    void 临时关闭按游戏刻倒计时_到期自动恢复为开且只报一次() {
        AutoSleep autoSleep = new AutoSleep();
        assertTrue(autoSleep.enabled());
        assertTrue(autoSleep.remainingSeconds(100).isEmpty());

        long applied = autoSleep.disableForSeconds(100, AutoSleep.DEFAULT_OFF_SECONDS);
        assertEquals(1200L, applied);
        assertFalse(autoSleep.enabled());
        assertEquals(1200L, autoSleep.remainingSeconds(100).orElseThrow());
        assertEquals(1199L, autoSleep.remainingSeconds(120).orElseThrow());

        // 未到期前不恢复。
        assertFalse(autoSleep.tick(100 + 1200L * 20 - 1));
        assertFalse(autoSleep.enabled());
        assertEquals(1L, autoSleep.remainingSeconds(100 + 1200L * 20 - 1).orElseThrow());

        // 到期当刻自动恢复并返回 true 供上层发提醒；下一刻不再重复返回 true。
        assertTrue(autoSleep.tick(100 + 1200L * 20), "到期当刻自动恢复为开");
        assertTrue(autoSleep.enabled());
        assertTrue(autoSleep.remainingSeconds(100 + 1200L * 20).isEmpty());
        assertFalse(autoSleep.tick(100 + 1200L * 20 + 20), "恢复提醒只发一次");
    }

    @Test
    void 夜晚生存需求随临时关闭期限到点自动恢复找床() {
        AutoSleep autoSleep = new AutoSleep();
        autoSleep.disableForSeconds(0, 60);
        NightfallNeed need = new NightfallNeed(
                tick -> new NightfallNeed.Facts(true, false, ThreatAssessment.Verdict.WINNABLE, 20.0, 0),
                tick -> true,
                tick -> null,
                tick -> null,
                autoSleep);

        // 第 30 秒：仍在守夜期限内，不找床。
        assertNull(need.urgency(TickContext.of(600, null), null));
        // 第 60 秒（1200 刻）：期限已到，夜晚需求自动恢复找空当去睡。
        assertEquals(Urgency.LATER, need.urgency(TickContext.of(1200, null), null));
        assertTrue(autoSleep.enabled());
    }

    @Test
    void 开关结果细节序列化字段对齐协议约定() {
        TaskResult offResult = TaskResult.builder(TaskResult.Status.DONE, "已临时关掉")
                .details(new AutoSleepDetails(false, 1200L))
                .build();
        JsonObject offJson = ResultJson.result(offResult).getAsJsonObject("details");
        assertFalse(offJson.get("auto_sleep").getAsBoolean());
        assertEquals(1200L, offJson.get("auto_on_in_seconds").getAsLong());

        TaskResult onResult = TaskResult.builder(TaskResult.Status.DONE, "已开")
                .details(new AutoSleepDetails(true, null))
                .build();
        JsonObject onJson = ResultJson.result(onResult).getAsJsonObject("details");
        assertTrue(onJson.get("auto_sleep").getAsBoolean());
        assertFalse(onJson.has("auto_on_in_seconds"), "开启时不带剩余秒数");
    }
}
