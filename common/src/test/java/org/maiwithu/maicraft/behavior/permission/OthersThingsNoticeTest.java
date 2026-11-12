// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 动了别人放的东西的事件通报：只对归属记录明确为其他玩家的非战利品容器与方块发出 others_things_touched，
 * 连拆同一个人的方块合成一条，挂在当前推进的目标与能力名下。
 */
class OthersThingsNoticeTest {

    private static final String SELF_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String PLAYER_A_UUID = "00000000-0000-0000-0000-00000000000a";
    private static final String PLAYER_B_UUID = "00000000-0000-0000-0000-00000000000b";

    private static Protection protection() {
        ReadsBlockOwnership ownership = new ReadsBlockOwnership() {
            @Override
            public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                if (x == 10) return Optional.of(new PlacedBy(PLAYER_A_UUID));
                if (x == 20) return Optional.of(new PlacedBy(PLAYER_B_UUID));
                if (x == 30) return Optional.of(new PlacedBy(SELF_UUID));
                return Optional.empty();
            }

            @Override
            public boolean known(String dimension, int x, int y, int z) {
                return x != 99;
            }
        };
        return new Protection(ownership, List::of, name -> Optional.empty(), SELF_UUID,
                SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE,
                uuid -> PLAYER_A_UUID.equals(uuid) ? "玩家甲" : "一位不在线的玩家");
    }

    private static TaskEventSink goalBoundSink(TaskEventLog log, long goalId, String ability) {
        return new TaskEventSink() {
            @Override
            public void publish(TaskEvent.Kind kind, String message) {
                log.publish(kind, message);
            }

            @Override
            public void publishInAction(TaskEvent.Kind kind, String message, EventFacts facts) {
                log.appendForGoal(kind, goalId, ability, message, facts);
            }
        };
    }

    @Test
    void 从别人放的箱子取货与存货各发一条结构化事件_自家与战利品容器不发() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        OthersThingsNotice notice = new OthersThingsNotice(protection(),
                id -> id.startsWith("lootr:"), goalBoundSink(log, 1414L, "maicraft:obtain"));

        WorldPosition othersChest = new WorldPosition(10, 65, 53, "minecraft:overworld");
        notice.containerTook(othersChest, "minecraft:chest", List.of(
                new OthersThingsNotice.MovedItem("minecraft:iron_ingot", "铁锭", 3),
                new OthersThingsNotice.MovedItem("minecraft:bread", "面包", 1)));

        // 自家箱子、自然战利品箱子、无记录箱子都不发。
        notice.containerTook(new WorldPosition(30, 65, 53, "minecraft:overworld"), "minecraft:chest",
                List.of(new OthersThingsNotice.MovedItem("minecraft:iron_ingot", "铁锭", 2)));
        notice.containerTook(othersChest, "lootr:lootr_chest",
                List.of(new OthersThingsNotice.MovedItem("minecraft:iron_ingot", "铁锭", 2)));
        notice.containerTook(new WorldPosition(40, 65, 53, "minecraft:overworld"), "minecraft:chest",
                List.of(new OthersThingsNotice.MovedItem("minecraft:iron_ingot", "铁锭", 2)));

        // 往不在线的玩家乙放的木桶里存东西。
        WorldPosition offlineBarrel = new WorldPosition(20, 65, 53, "minecraft:overworld");
        notice.containerStored(offlineBarrel, "minecraft:barrel",
                List.of(new OthersThingsNotice.MovedItem("minecraft:cobblestone", null, 32)));

        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(2, events.size());

        JsonObject tookJson = ResultJson.event(events.get(0));
        assertEquals("others_things_touched", tookJson.get("kind").getAsString());
        assertEquals(1414L, tookJson.get("goal_id").getAsLong());
        assertEquals("maicraft:obtain", tookJson.get("ability").getAsString());
        assertTrue(tookJson.get("message").getAsString().contains("玩家甲"));
        JsonObject tookBody = tookJson.getAsJsonObject("others_things");
        assertEquals("took", tookBody.get("action").getAsString());
        assertEquals("玩家甲", tookBody.get("owner").getAsString());
        assertEquals(PLAYER_A_UUID, tookBody.get("owner_uuid").getAsString());
        assertEquals("minecraft:chest", tookBody.get("block").getAsString());
        assertFalse(tookBody.has("count"));
        JsonArray items = tookBody.getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("minecraft:iron_ingot", items.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(3, items.get(0).getAsJsonObject().get("count").getAsInt());

        JsonObject storedBody = ResultJson.event(events.get(1)).getAsJsonObject("others_things");
        assertEquals("stored", storedBody.get("action").getAsString());
        assertEquals("一位不在线的玩家", storedBody.get("owner").getAsString());
        assertEquals(PLAYER_B_UUID, storedBody.get("owner_uuid").getAsString());
    }

    @Test
    void 连拆同一个人的方块合并成一条带count_换人或停手到期分开发_改动方块发changed() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        OthersThingsNotice notice = new OthersThingsNotice(protection(),
                id -> false, goalBoundSink(log, 99L, "maicraft:gather"));

        WorldPosition first = new WorldPosition(10, 64, 1, "minecraft:overworld");
        WorldPosition second = new WorldPosition(10, 64, 2, "minecraft:overworld");
        WorldPosition third = new WorldPosition(10, 64, 3, "minecraft:overworld");
        notice.blockBroken(notice.snapshotBlock(first, "minecraft:oak_planks").orElseThrow(), 100L);
        // 同一格重复报（例如连锁触发格与副格重叠）只算一格。
        notice.blockBroken(notice.snapshotBlock(first, "minecraft:oak_planks").orElseThrow(), 105L);
        notice.blockBroken(notice.snapshotBlock(second, "minecraft:oak_planks").orElseThrow(), 110L);
        notice.blockBroken(notice.snapshotBlock(third, "minecraft:oak_planks").orElseThrow(), 120L);

        // 还没停够窗口期时不提前刷屏。
        notice.flushDue(140L);
        assertEquals(0, log.read(null, 0, OptionalLong.empty(), 10, 0).events().size());

        // 换拆另一名玩家的方块时，先把前一名玩家的 3 格合发出去。
        WorldPosition otherPlayersBlock = new WorldPosition(20, 64, 1, "minecraft:overworld");
        notice.blockBroken(notice.snapshotBlock(otherPlayersBlock, "minecraft:stone_bricks").orElseThrow(), 145L);
        // 停手满窗口期后把这 1 格发出。
        notice.flushDue(145L + OthersThingsNotice.BREAK_BATCH_WINDOW_TICKS);

        // 改动别人放的泥土（耕地）。
        WorldPosition dirt = new WorldPosition(10, 64, 9, "minecraft:overworld");
        notice.blockChanged(notice.snapshotBlock(dirt, "minecraft:dirt").orElseThrow());

        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(3, events.size());

        JsonObject multiBreak = ResultJson.event(events.get(0)).getAsJsonObject("others_things");
        assertEquals("broke", multiBreak.get("action").getAsString());
        assertEquals(3, multiBreak.get("count").getAsInt());
        assertEquals(1, multiBreak.getAsJsonObject("at").get("z").getAsInt());
        assertFalse(multiBreak.has("items"));

        JsonObject singleBreak = ResultJson.event(events.get(1)).getAsJsonObject("others_things");
        assertEquals("broke", singleBreak.get("action").getAsString());
        assertFalse(singleBreak.has("count"), "单格拆掉时不另带 count");

        JsonObject changed = ResultJson.event(events.get(2)).getAsJsonObject("others_things");
        assertEquals("changed", changed.get("action").getAsString());
        assertEquals("minecraft:dirt", changed.get("block").getAsString());
        assertFalse(changed.has("items"));
    }
}
