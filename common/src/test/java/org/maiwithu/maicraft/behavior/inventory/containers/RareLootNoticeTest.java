// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 战利品容器稀罕发现通报测试：只在自然战利品容器与无放置记录容器里报 precious 级物品，
 * 按世界记忆里上回看到过的内容去重，自家、他人与归属未问到的箱子不发。
 */
class RareLootNoticeTest {

    @TempDir
    Path tempDir;

    private static Protection protection() {
        ReadsBlockOwnership ownership = new ReadsBlockOwnership() {
            @Override
            public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                if (x == 10) return Optional.of(new PlacedBy("self-uuid"));
                if (x == 20) return Optional.of(new PlacedBy("other-uuid"));
                return Optional.empty();
            }

            @Override
            public boolean known(String dimension, int x, int y, int z) {
                return x != 99;
            }
        };
        return new Protection(ownership, List::of, name -> Optional.empty(), "self-uuid",
                SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> "玩家甲");
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
    void 战利品与无记录箱子开出稀罕物发里程碑事件_世界记忆已见过的不再重报_自家与他人箱子不发() throws InterruptedException {
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.db")), "world-1");
        TaskEventLog log = new TaskEventLog();
        RareLootNotice notice = new RareLootNotice(protection(), id -> id.startsWith("lootr:"),
                memory, goalBoundSink(log, 1259L, "maicraft:use"));

        // 第一次用 use 打开 Lootr 战利品箱：看到了海洋之心和绿宝石，一件没拿（taken=0）。
        WorldPosition lootChest = new WorldPosition(-311, 59, 73, "minecraft:overworld");
        Set<String> firstSeen = notice.snapshotPreviouslySeen(lootChest);
        notice.report(lootChest, "lootr:lootr_chest", firstSeen, List.of(
                new RareLootNotice.SeenPrecious("minecraft:heart_of_the_sea", "海洋之心", 1, 0),
                new RareLootNotice.SeenPrecious("minecraft:emerald", "绿宝石", 7, 0)));
        memory.rememberContainerOpened(lootChest, "lootr:lootr_chest",
                List.of("minecraft:heart_of_the_sea", "minecraft:emerald"), Instant.now());

        // 第二次再打开同一只箱子把海洋之心拿走：因为上回世界记忆里已经看过这两样，不再发第二次 rare_loot_found。
        Set<String> secondSeen = notice.snapshotPreviouslySeen(lootChest);
        notice.report(lootChest, "lootr:lootr_chest", secondSeen, List.of(
                new RareLootNotice.SeenPrecious("minecraft:heart_of_the_sea", "海洋之心", 1, 1)));

        // 打开另一只无放置记录的原版箱子（x=30 -> no_record）并当场拿走钻石。
        WorldPosition dungeonChest = new WorldPosition(30, 40, 12, "minecraft:overworld");
        notice.report(dungeonChest, "minecraft:chest", notice.snapshotPreviouslySeen(dungeonChest), List.of(
                new RareLootNotice.SeenPrecious("minecraft:diamond", "钻石", 3, 3)));

        // 自家箱子（x=10）、别人放的箱子（x=20）、归属未问到的箱子（x=99）一律不发。
        for (int x : List.of(10, 20, 99)) {
            WorldPosition nonLoot = new WorldPosition(x, 64, 0, "minecraft:overworld");
            notice.report(nonLoot, "minecraft:chest", Set.of(), List.of(
                    new RareLootNotice.SeenPrecious("minecraft:diamond", "钻石", 5, 5)));
        }

        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(2, events.size());

        JsonObject firstEvent = ResultJson.event(events.get(0));
        assertEquals("rare_loot_found", firstEvent.get("kind").getAsString());
        assertEquals(1259L, firstEvent.get("goal_id").getAsLong());
        assertEquals("maicraft:use", firstEvent.get("ability").getAsString());
        assertTrue(firstEvent.get("message").getAsString().contains("还在箱里"));
        JsonObject firstFacts = firstEvent.getAsJsonObject("rare_loot");
        assertEquals("lootr:lootr_chest", firstFacts.get("block").getAsString());
        JsonArray items = firstFacts.getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("minecraft:heart_of_the_sea", items.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(0, items.get(0).getAsJsonObject().get("taken").getAsInt());

        JsonObject secondEvent = ResultJson.event(events.get(1));
        assertTrue(secondEvent.get("message").getAsString().contains("已经拿走"));
        JsonObject secondFacts = secondEvent.getAsJsonObject("rare_loot");
        assertEquals(3, secondFacts.getAsJsonArray("items").get(0).getAsJsonObject().get("taken").getAsInt());
    }
}
