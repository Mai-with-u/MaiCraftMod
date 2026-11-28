// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查门边轻微来回抖动不算前进，真正位移或已确认的操作才更新进度；旧记录到期后不再续时。
 */
class NavigationProgressTest {

    @Test
    void doorwayJitterDoesNotRenewProgress() {
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.99, 64, 0, 0);
        assertFalse(progress.recent(0, 20), "initial sampling is not progress");
        for (int tick = 1; tick <= 200; tick++) {
            progress.observe(tick % 2 == 0 ? 0.99 : 1.01, 64, 0, tick);
        }
        assertFalse(progress.recent(200, 20), "doorway block-boundary jitter must not renew a lease");
        assertEquals(200, progress.stalledTicks(200), "stalled ticks keep counting through the jitter");
    }

    @Test
    void displacementConfirmsAndThenExpires() {
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.99, 64, 0, 0);
        for (int tick = 1; tick <= 200; tick++) {
            progress.observe(tick % 2 == 0 ? 0.99 : 1.01, 64, 0, tick);
        }
        progress.observe(1.30, 64, 0, 201);
        assertTrue(progress.recent(201, 0), "physical displacement must count");
        assertFalse(progress.recent(222, 20), "progress must expire without new evidence");
    }

    @Test
    void confirmedNativeChangesCountAndClockRollbackCannotInventEvidence() {
        NavigationProgress progress = new NavigationProgress();
        progress.confirm(230);
        assertTrue(progress.recent(231, 2), "confirmed native changes must count");
        assertFalse(progress.recent(229, 20), "clock rollback cannot invent recent evidence");
    }

    @Test
    void restartAfterHumanControlDoesNotCountAsStall() {
        // 人按 F8 收回角色操作了几百刻再交回：从交回那一刻起算，不把那段算成"长时间没进展"。
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0, 64, 0, 10);
        progress.observe(3, 64, 0, 20);
        progress.restart(500);
        assertEquals(0, progress.stalledTicks(500));
        assertEquals(30, progress.stalledTicks(530));
    }

    @Test
    void smallRadiusPacingTriggersAfterSingleStepSelfHealWindow() {
        // 角色在两格间反复水平往返折返：每次位移超过四分之一格让 stalledTicks 不断清零，
        // 阈值设在 160 刻（晚于执行段 105–130 刻单步卡住切门/列障碍自愈窗口）之后，超 160 刻且水平走动超 4 格才判死。
        assertTrue(NavigationProgress.PACING_LIMIT_TICKS > 130, "折返上限必须晚于执行段单步自愈窗口");
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.5, 64, 0.5, 0, true);
        for (int tick = 1; tick <= NavigationProgress.PACING_LIMIT_TICKS; tick++) {
            double x = (tick / 5) % 2 == 0 ? 0.5 : 1.7;
            progress.observe(x, 64, 0.5, tick, true);
            assertFalse(progress.pacing(tick), "未满 160 刻前不抢跑切门与列障碍自愈");
        }
        progress.observe(0.5, 64, 0.5, NavigationProgress.PACING_LIMIT_TICKS + 1, true);
        assertTrue(progress.stalledTicks(NavigationProgress.PACING_LIMIT_TICKS + 1) < 20, "小幅往返让常规位移卡住不断刷新");
        assertTrue(progress.pacing(NavigationProgress.PACING_LIMIT_TICKS + 1), "水平小半径往返折返超 8 秒应触发止损");
    }

    @Test
    void verticalJumpingInPlaceDoesNotTriggerPacing() {
        // 门前或台阶/水岸边原地起跳重试：只产生竖直位移、水平 XZ 不动，绝不能被误判为水平折返抢跑切门自愈。
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.5, 64.0, 0.5, 0, true);
        for (int tick = 1; tick <= 220; tick++) {
            double y = (tick % 10) < 5 ? 64.0 : 65.25;
            progress.observe(0.5, y, 0.5, tick, true);
            assertFalse(progress.pacing(tick), "原地起跳不应累计水平折返路程，tick=" + tick);
        }
    }

    @Test
    void planningWithoutRouteClearsPacingWindow() {
        // 算路期（!routePresent）在水中漂移或受推挤来回晃动 180 刻，路线刚就绪第一刻必须从头起算，不得立刻误杀。
        NavigationProgress progress = new NavigationProgress();
        for (int tick = 0; tick <= 180; tick++) {
            double x = (tick / 5) % 2 == 0 ? 0.5 : 1.7;
            progress.observe(x, 64.0, 0.5, tick, false);
        }
        progress.observe(0.5, 64.0, 0.5, 181, true);
        assertFalse(progress.pacing(181), "算路期的晃动不应被带入持路行走的折返窗口");
    }

    @Test
    void normalLongWalkAndDetourDoNotTriggerPacing() {
        // 正常长途步行或绕大弯持续离开 2 格小圈刷新锚点，走 600 刻也不会误判折返。
        NavigationProgress progress = new NavigationProgress();
        for (int tick = 0; tick <= 600; tick++) {
            progress.observe(tick * 0.2, 64, Math.sin(tick * 0.05) * 3.0, tick, true);
            assertFalse(progress.pacing(tick), "正常推进与绕路不应误判原地折返，tick=" + tick);
        }
    }

    @Test
    void confirmedBlockWorkAndResetClearPacingWindow() {
        // 原地挖硬方块或切门确认原生操作、暂停续走以及动态换目标时，都应重置折返锚点避免误杀。
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.5, 64, 0.5, 0, true);
        for (int tick = 1; tick <= 150; tick++) {
            double x = (tick / 5) % 2 == 0 ? 0.5 : 1.6;
            progress.observe(x, 64, 0.5, tick, true);
        }
        progress.confirm(150);
        progress.observe(0.5, 64, 0.5, 151, true);
        assertFalse(progress.pacing(200), "确认原生交互后折返计时从头算");

        progress.resetPacing();
        assertFalse(progress.pacing(400), "换目标清掉折返锚点后不沿用旧锚点");
    }

    @Test
    void doorwayJitterWithoutEnoughTravelDoesNotTriggerPacing() {
        // 原地不动或微幅抖动累计路程不足 4 格，不误触发往返折返，留给常规卡住与切门逻辑处理。
        NavigationProgress progress = new NavigationProgress();
        progress.observe(0.99, 64, 0, 0, true);
        for (int tick = 1; tick <= 220; tick++) {
            progress.observe(tick % 2 == 0 ? 0.99 : 1.01, 64, 0, tick, true);
        }
        assertFalse(progress.pacing(220), "微小抖动不算往返折返");
    }
}
