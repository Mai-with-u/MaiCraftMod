// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.drops.ItemsTossed;
import org.maiwithu.maicraft.game.ReceivedChat;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.ChatEvent;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * events 工具回包序列化测试：确认 items_tossed 结构化事实沿 events(topic="self") 原样返回。
 */
class EventsToolTest {

    private static final String PLAYER_A_UUID = "00000000-0000-0000-0000-00000000000a";

    private static EventsTool newTool(TaskEventLog log) {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        GoalRunTable table = new GoalRunTable(registry,
                new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log),
                (name, position) -> { }, new ControlLoop(List.of()), new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() { return true; }
                    @Override public void requestControl() { }
                });
        ClientThread clientThread = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> action) {
                return action.apply(null);
            }
        };
        return new EventsTool(log, new ChatEventLog(), table, clientThread, () -> null);
    }

    @Test
    void 自流事件返回items_tossed结构化事实且字段名完整() {
        TaskEventLog log = new TaskEventLog();
        WorldPosition spot = new WorldPosition(18, 65, -14, "minecraft:overworld");
        ItemsTossed facts = new ItemsTossed("玩家甲", PLAYER_A_UUID, spot,
                List.of(new ItemsTossed.Item("minecraft:diamond", 4)));
        log.appendForGoal(TaskEvent.Kind.ITEMS_TOSSED, 77L, "maicraft:body_language",
                "朝 玩家甲 扔出了 4 个 minecraft:diamond", facts);

        JsonObject args = new JsonObject();
        args.addProperty("topic", "self");
        JsonObject reply = newTool(log).call(args);

        assertTrue(reply.get("ok").getAsBoolean());
        JsonArray events = reply.getAsJsonObject("data").getAsJsonArray("events");
        assertEquals(1, events.size());

        JsonObject event = events.get(0).getAsJsonObject();
        assertEquals("items_tossed", event.get("kind").getAsString());
        assertEquals(77L, event.get("goal_id").getAsLong());
        assertEquals("maicraft:body_language", event.get("ability").getAsString());
        assertEquals("朝 玩家甲 扔出了 4 个 minecraft:diamond", event.get("message").getAsString());

        JsonObject tossed = event.getAsJsonObject("items_tossed");
        assertEquals("玩家甲", tossed.get("to").getAsString());
        assertEquals(PLAYER_A_UUID, tossed.get("to_uuid").getAsString());
        JsonObject at = tossed.getAsJsonObject("at");
        assertEquals(18, at.get("x").getAsInt());
        assertEquals(65, at.get("y").getAsInt());
        assertEquals(-14, at.get("z").getAsInt());
        assertEquals("minecraft:overworld", at.get("dimension").getAsString());

        JsonArray items = tossed.getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals("minecraft:diamond", items.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(4, items.get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void 坐标为空时items_tossed省略at并保留其余字段() {
        TaskEventLog log = new TaskEventLog();
        ItemsTossed facts = new ItemsTossed("玩家甲", PLAYER_A_UUID, null,
                List.of(new ItemsTossed.Item("minecraft:bread", 2)));
        log.publish(TaskEvent.Kind.ITEMS_TOSSED, "朝 玩家甲 扔出了 2 个 minecraft:bread", facts);

        JsonObject reply = newTool(log).call(new JsonObject());

        assertTrue(reply.get("ok").getAsBoolean());
        JsonObject event = reply.getAsJsonObject("data").getAsJsonArray("events").get(0).getAsJsonObject();
        assertFalse(event.has("goal_id"));
        JsonObject tossed = event.getAsJsonObject("items_tossed");
        assertFalse(tossed.has("at"));
        assertEquals("玩家甲", tossed.get("to").getAsString());
        assertEquals(PLAYER_A_UUID, tossed.get("to_uuid").getAsString());
        assertEquals("minecraft:bread", tossed.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(2, tossed.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void 聊天事件仅在detail非空时序列化detail字段() {
        ReceivedChat withDetail = ReceivedChat.fromServer("Alex 获得了进度 [石头时代]", false)
                .orElseThrow()
                .with(List.of(), "chat.type.advancement.task", "用你的新镐挖掘圆石");
        JsonObject withDetailJson = EventsTool.chatEvent(new ChatEvent(1L, withDetail));
        assertEquals("用你的新镐挖掘圆石", withDetailJson.get("detail").getAsString());
        assertEquals("chat.type.advancement.task", withDetailJson.get("translation_key").getAsString());

        ReceivedChat withoutDetail = ReceivedChat.fromServer("服务器将在 5 分钟后保存", false).orElseThrow();
        JsonObject withoutDetailJson = EventsTool.chatEvent(new ChatEvent(2L, withoutDetail));
        assertFalse(withoutDetailJson.has("detail"));
    }
}
