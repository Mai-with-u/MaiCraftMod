// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简要档的活动兜底行：此刻没有物理任务推进时，拿最近一次工具调用顶上——
 * 模型只在推理、等事件的时段面板什么都不显示，主播看不出宿主还活着。
 */
class BriefRowsTest {

    private static final long NOW = 10_000_000L;

    private static List<Row> rows(PanelScene scene) {
        StatusSnapshot snapshot = scene.snapshot();
        FirstSeenTimes times = new FirstSeenTimes();
        times.observe(snapshot, NOW);
        return BriefRows.rows(new Moment(snapshot, times, NOW, ZoneOffset.UTC));
    }

    private static Optional<Row> activityRow(List<Row> rows) {
        return rows.stream().filter(row -> "此刻".equals(row.label()) && row.maxLines() == 1).findFirst();
    }

    @Test
    void anIdlePanelFallsBackToTheLatestToolCall() {
        // 手上没有物理任务推进、但宿主 20 秒前观察过周围：此刻行显示最近的活动与新鲜度。
        PanelScene scene = new PanelScene();
        scene.calls.add(new StatusSnapshot.ToolCall("observe", "{\"what\":\"scene\"}", NOW - 20_000,
                false, null, null, null));

        Optional<Row> activity = activityRow(rows(scene));

        assertTrue(activity.isPresent());
        assertEquals("观察·周围 · 20s 前", activity.get().value().getFirst().text());
        assertEquals(PanelColor.MINOR, activity.get().value().getFirst().color());
    }

    @Test
    void consecutiveCallsOfTheSameToolMergeWithACount() {
        // 宿主挂一次 events 等新事件就发一次调用，同一个工具连着的调用合成一行 ×N。
        PanelScene scene = new PanelScene();
        scene.calls.add(new StatusSnapshot.ToolCall("execute", "{\"ability\":\"maicraft:obtain\",\"items\":[]}",
                NOW - 60_000, false, null, null, null));
        scene.calls.add(new StatusSnapshot.ToolCall("events", "{\"wait_ms\":30000}", NOW - 5_000, true, null, null, null));
        scene.calls.add(new StatusSnapshot.ToolCall("events", "{\"wait_ms\":30000}", NOW - 3_000, true, null, null, null));

        Optional<Row> activity = activityRow(rows(scene));

        assertTrue(activity.isPresent());
        assertEquals("等事件 ×2 · 3s 前", activity.get().value().getFirst().text(), "新鲜度按最近那一次算");
        assertEquals(PanelColor.DOING, activity.get().value().getFirst().color(), "还在跑的调用用推进色");
    }

    @Test
    void anOldCallIsNotShownAsCurrentActivity() {
        // 超过 90 秒的旧活动不再显示：宿主多半已经断开或闲置，陈旧的活动行会让人误以为还在动。
        PanelScene scene = new PanelScene();
        scene.calls.add(new StatusSnapshot.ToolCall("observe", "{}", NOW - 91_000, false, null, null, null));

        assertTrue(activityRow(rows(scene)).isEmpty());
    }

    @Test
    void aFailedCallIsNotShownAndDoesNotMerge() {
        // 报错的调用不是正在发生的活动，不进简要档，也不并进同工具连续调用的 ×N。
        PanelScene scene = new PanelScene();
        scene.calls.add(new StatusSnapshot.ToolCall("observe", "{}", NOW - 8_000, false,
                "invalid_parameter", "id", "what=detail 时要给 id"));
        scene.calls.add(new StatusSnapshot.ToolCall("observe", "{\"what\":\"scene\"}", NOW - 6_000,
                false, null, null, null));

        Optional<Row> activity = activityRow(rows(scene));

        assertTrue(activity.isPresent());
        assertEquals("观察·周围 · 6s 前", activity.get().value().getFirst().text(), "只有成功的那一次，没有 ×2");
    }

    @Test
    void theTaskRowWinsWhenThereIsPhysicalProgress() {
        // 物理任务在推进时还是原来的"此刻"行，活动兜底根本不参与。
        PanelScene scene = new PanelScene().working("maicraft:obtain", "做一把铁镐", "采掘：挖下一格铁矿");
        scene.calls.add(new StatusSnapshot.ToolCall("observe", "{}", NOW - 1_000, false, null, null, null));

        List<Row> all = rows(scene);

        Optional<Row> now = all.stream().filter(row -> "此刻".equals(row.label())).findFirst();
        assertTrue(now.isPresent());
        assertTrue(now.get().value().getFirst().text().contains("采掘：挖下一格铁矿"), all.toString());
        assertFalse(now.get().value().getFirst().text().contains("观察"), all.toString());
    }

    @Test
    void observeViewsAndExecuteMapToPlainWords() {
        // observe 按 what 参数说在看哪一类，execute 显示能力名（去掉本模组前缀）。
        PanelScene self = new PanelScene();
        self.calls.add(new StatusSnapshot.ToolCall("observe", "{\"what\":\"self\"}", NOW - 1_000,
                false, null, null, null));
        assertEquals("观察·自己 · 1s 前", activityRow(rows(self)).orElseThrow().value().getFirst().text());

        PanelScene execute = new PanelScene();
        execute.calls.add(new StatusSnapshot.ToolCall("execute", "{\"ability\":\"maicraft:travel\"}",
                NOW - 1_000, false, null, null, null));
        assertEquals("执行 travel · 1s 前", activityRow(rows(execute)).orElseThrow().value().getFirst().text());
    }
}
