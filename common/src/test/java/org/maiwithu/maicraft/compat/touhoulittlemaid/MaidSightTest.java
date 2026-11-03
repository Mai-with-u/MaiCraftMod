// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 女仆在观察里的样子：跟着与驻守、好感分档、在想与头上的话、只在是的时候给的几项；五子棋盘的轮次、最后一手与胜负；
 * 女仆墓碑上的名字与她的 UUID。
 */
class MaidSightTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 墓碑_从墓碑名的悬停信息读出女仆的UUID() {
        // 女仆死时车万女仆把她的显示名写上墓碑：原版显示名挂着悬停显示实体，插入文本是她的 UUID。
        UUID momo = UUID.fromString("5f2b9c1e-0d4a-4c1b-9e7f-1a2b3c4d5e6f");
        Component name = Component.literal("momo").withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
                        new HoverEvent.EntityTooltipInfo(EntityType.CAT, momo, Component.literal("momo"))))
                .withInsertion(momo.toString()));

        JsonObject summary = MaidSight.tombstone(name);

        assertEquals("momo", summary.get("maid_name").getAsString());
        assertEquals(momo.toString(), summary.get("maid_uuid").getAsString());
    }

    @Test
    void 墓碑_名字被改成没有样式的字时只给名字() {
        JsonObject summary = MaidSight.tombstone(Component.literal("momo"));

        assertEquals("momo", summary.get("maid_name").getAsString());
        assertFalse(summary.has("maid_uuid"), "读不出 UUID 不编一个");
    }

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

    @Test
    void 五子棋盘_还在下_写轮到谁与最后一手() {
        int[][] stones = MaidFakes.emptyStones();
        stones[7][7] = GomokuState.YOURS;
        JsonObject summary = MaidSight.gomoku(MaidFakes.board(stones, false, GomokuState.Ending.NOT_OVER, 1, 7, 7,
                GomokuState.YOURS));
        assertEquals("maid", summary.get("turn").getAsString());
        assertEquals(1, summary.get("moves").getAsInt());
        assertEquals("H8", summary.get("last_move").getAsString());
        assertTrue(summary.get("maid_seated").getAsBoolean());
        assertFalse(summary.has("winner"), "没结束不给");
    }

    @Test
    void 五子棋盘_她赢了_空棋盘没有最后一手() {
        JsonObject over = MaidSight.gomoku(MaidFakes.board(MaidFakes.emptyStones(), true, GomokuState.Ending.WON, 9,
                3, 3, GomokuState.HERS));
        assertEquals("over", over.get("turn").getAsString());
        assertEquals("maid", over.get("winner").getAsString());
        JsonObject fresh = MaidSight.gomoku(MaidFakes.board(MaidFakes.emptyStones(), true,
                GomokuState.Ending.NOT_OVER, 0, -1, -1, 0));
        assertFalse(fresh.has("last_move"));
        assertEquals(15, MaidSight.gomokuDetail(MaidFakes.board(MaidFakes.emptyStones(), true,
                GomokuState.Ending.NOT_OVER, 0, -1, -1, 0)).getAsJsonArray("board").size());
    }
}
