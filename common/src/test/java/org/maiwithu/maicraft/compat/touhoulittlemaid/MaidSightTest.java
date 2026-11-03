// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 女仆在观察里的样子：跟着与驻守、好感分档、在想与头上的话、只在是的时候给的几项。 */
class MaidSightTest {

    @Test
    void 跟着主人_只写模式() {
        JsonObject summary = MaidSight.summary(MaidFakes.maid(1, "小雪", List.of()));
        assertEquals("with_you", summary.getAsJsonObject("stay").get("mode").getAsString());
        assertFalse(summary.getAsJsonObject("stay").has("center"));
        assertEquals("day", summary.get("work_shift").getAsString());
        assertEquals("work", summary.get("shift_now").getAsString());
        assertEquals(1, summary.getAsJsonObject("affection").get("level").getAsInt());
        assertFalse(summary.has("thinking"), "不在想时不给");
        assertFalse(summary.has("begging"), "不乞食时不给");
    }

    @Test
    void 驻守_带圆心与半径() {
        MaidState s = MaidFakes.maid(1, "小雪", List.of());
        MaidState staying = new MaidState(1, s.name(), s.skinId(), s.skinName(), s.workId(), s.workName(), s.shift(),
                s.shiftNow(), true, new BlockPos(10, 64, -3), 12f, s.pickup(), s.rideable(), s.affection(),
                s.experience(), true, false, s.backpackId(), s.bubbles());
        JsonObject summary = MaidSight.summary(staying);
        JsonObject stay = summary.getAsJsonObject("stay");
        assertEquals("in_area", stay.get("mode").getAsString());
        assertEquals(-3, stay.getAsJsonArray("center").get(2).getAsInt());
        assertEquals(12, stay.get("radius").getAsInt());
        assertTrue(summary.get("begging").getAsBoolean());
    }

    @Test
    void 在想与头上的话() {
        MaidState state = MaidFakes.maid(1, "小雪", List.of(MaidFakes.text(5, "你好呀"),
                new MaidState.Bubble(6, MaidState.Bubble.Kind.THINKING, "正在查看背包"),
                new MaidState.Bubble(7, MaidState.Bubble.Kind.OTHER, null)));
        JsonObject summary = MaidSight.summary(state);
        assertEquals("正在查看背包", summary.getAsJsonObject("thinking").get("note").getAsString());
        assertEquals(1, summary.getAsJsonArray("bubbles").size(), "表情气泡不列");
        assertEquals("你好呀", summary.getAsJsonArray("bubbles").get(0).getAsString());
    }
}
