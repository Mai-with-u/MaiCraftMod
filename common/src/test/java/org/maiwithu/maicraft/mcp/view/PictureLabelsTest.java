// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画面标注怎么摆：正上方优先，压到了换右左下再往上挪；要看的那样东西先摆、同档近的先；
 * 挤了不标并写明原因，最多标二十四个；整张图不标时都不标、原因留空；要看的外框里不压别的标签。
 */
class PictureLabelsTest {

    private static final int W = 1280;
    private static final int H = 720;

    private static PictureLabels.Candidate at(String id, PictureLabels.Rank rank, double x, double y, double distance) {
        return new PictureLabels.Candidate(id, rank, false, x, y, distance);
    }

    private static PictureLabels.Placement of(List<PictureLabels.Placement> placements, String id) {
        return placements.stream().filter(p -> p.candidate().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void 只有一个时摆在那一点正上方() {
        List<PictureLabels.Placement> placements = PictureLabels.place(
                List.of(at("e1", PictureLabels.Rank.ENTITY, 640, 360, 5)), W, H, null, false);
        PictureLabels.Rect tag = placements.get(0).tag();
        assertTrue(placements.get(0).labeled());
        assertEquals(640, tag.x() + tag.width() / 2, 0.01, "横向居中");
        assertTrue(tag.y() + tag.height() < 360, "在那一点上面");
    }

    @Test
    void 两个挨着的点_后一个换到别处不压住前一个() {
        List<PictureLabels.Placement> placements = PictureLabels.place(List.of(
                at("e1", PictureLabels.Rank.ENTITY, 640, 360, 5),
                at("e2", PictureLabels.Rank.ENTITY, 645, 362, 6)), W, H, null, false);
        PictureLabels.Rect first = of(placements, "e1").tag();
        PictureLabels.Rect second = of(placements, "e2").tag();
        assertTrue(of(placements, "e2").labeled());
        assertFalse(first.overlaps(second), "两个标签不叠");
    }

    @Test
    void 要看的那样东西先摆_同一档近的先() {
        List<PictureLabels.Placement> placements = PictureLabels.place(List.of(
                at("f9", PictureLabels.Rank.FACILITY, 100, 100, 2),
                at("e3", PictureLabels.Rank.PLAYER, 100, 100, 9),
                at("e1", PictureLabels.Rank.PLAYER, 100, 100, 3),
                new PictureLabels.Candidate("b2", PictureLabels.Rank.FEATURE, true, 100, 100, 20)), W, H, null, false);
        assertEquals(List.of("b2", "e1", "e3", "f9"), placements.stream().map(p -> p.candidate().id()).toList());
    }

    @Test
    void 挤得放不下的不标_写明太挤() {
        // 同一点摆八个：正上方、下、再往上挪三个摆得下（右、左两处压着正上方那个），剩下的放不下。
        List<PictureLabels.Candidate> crowd = new ArrayList<>();
        for (int i = 0; i < 8; i++) crowd.add(at("e" + i, PictureLabels.Rank.ENTITY, 640, 360, i));
        List<PictureLabels.Placement> placements = PictureLabels.place(crowd, W, H, null, false);
        List<PictureLabels.Rect> tags = placements.stream().filter(PictureLabels.Placement::labeled)
                .map(PictureLabels.Placement::tag).toList();
        assertEquals(5, tags.size());
        for (int i = 0; i < tags.size(); i++) {
            for (int j = i + 1; j < tags.size(); j++) assertFalse(tags.get(i).overlaps(tags.get(j)), "摆下的互不压");
        }
        assertEquals("太挤", of(placements, "e7").notLabeledBecause());
        assertEquals(8, placements.size(), "放不下的照样交回");
    }

    @Test
    void 图上最多标二十四个() {
        List<PictureLabels.Candidate> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(at("e" + i, PictureLabels.Rank.ENTITY, 60 + (i % 10) * 120, 120 + (i / 10) * 200, i));
        List<PictureLabels.Placement> placements = PictureLabels.place(many, W, H, null, false);
        assertEquals(PictureLabels.MAX_LABELS, placements.stream().filter(PictureLabels.Placement::labeled).count());
        assertEquals("画面上最多标 24 个", of(placements, "e29").notLabeledBecause());
    }

    @Test
    void 整张图不标时都不标_原因留空() {
        List<PictureLabels.Placement> placements = PictureLabels.place(List.of(
                at("e1", PictureLabels.Rank.ENTITY, 640, 360, 5)), W, H, null, true);
        assertFalse(placements.get(0).labeled());
        assertNull(placements.get(0).notLabeledBecause());
    }

    @Test
    void 贴着上边时换到别处() {
        List<PictureLabels.Placement> placements = PictureLabels.place(List.of(
                at("e1", PictureLabels.Rank.ENTITY, 640, 4, 5)), W, H, null, false);
        PictureLabels.Rect tag = placements.get(0).tag();
        assertTrue(tag.y() >= 0, "不出画面");
    }

    @Test
    void 别的标签不压进要看的那样东西的外框_外框占大半画面时不管() {
        PictureLabels.Rect target = new PictureLabels.Rect(500, 200, 300, 300);
        List<PictureLabels.Placement> placements = PictureLabels.place(List.of(
                at("e1", PictureLabels.Rank.ENTITY, 650, 400, 5)), W, H, target, false);
        PictureLabels.Placement placed = placements.get(0);
        assertTrue(!placed.labeled() || !placed.tag().overlaps(target), "不压进外框");
        PictureLabels.Rect huge = new PictureLabels.Rect(0, 0, W, H);
        assertTrue(PictureLabels.place(List.of(at("e1", PictureLabels.Rank.ENTITY, 650, 400, 5)), W, H, huge, false)
                .get(0).labeled(), "外框占满画面时照常标");
    }
}
