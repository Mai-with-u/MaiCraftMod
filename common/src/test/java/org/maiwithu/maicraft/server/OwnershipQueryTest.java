// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按区块问归属：只答角色附近的区块；格多时分页，每页装得进一封回信，照抄 after 接着问能把整块问齐。 */
class OwnershipQueryTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void 只答角色附近的区块() {
        assertTrue(OwnershipQuery.near(0, 0, 16, -16), "横竖都在十六个区块以内");
        assertFalse(OwnershipQuery.near(0, 0, 17, 0), "再远一个区块就不答");
        assertFalse(OwnershipQuery.near(100, 100, 100, 83));
        assertFalse(OwnershipQuery.near(0, 0, Integer.MAX_VALUE, 0), "极端坐标也不会算溢出");
    }

    @Test
    void 格多的区块分页答_每页装得进一封回信_接着问能问齐() {
        // 一个区块里盖满四十层、三个人轮流放：一封回信装不下，必须分页。
        var record = new BlockOwnershipRecord();
        UUID[] people = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        int placed = 0;
        for (int y = -60; y < -20; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    record.recordPlacement(OVERWORLD, new BlockPos(x + 1600, y, z - 3200), people[placed % 3], placed);
                    placed++;
                }
            }
        }
        Map<BlockPos, UUID> gathered = new HashMap<>();
        OptionalLong after = OptionalLong.empty();
        int pages = 0;
        while (true) {
            JsonObject page = OwnershipQuery.page(record, OVERWORLD, 100, -200, after);
            pages++;
            assertTrue(page.toString().length() < 50_000, "一页要装得进一封回信：" + page.toString().length());
            JsonArray owners = page.getAsJsonArray("owners");
            JsonArray cells = page.getAsJsonArray("cells");
            for (int i = 0; i < cells.size(); i += 4) {
                BlockPos position = new BlockPos(cells.get(i).getAsInt(), cells.get(i + 1).getAsInt(), cells.get(i + 2).getAsInt());
                UUID owner = UUID.fromString(owners.get(cells.get(i + 3).getAsInt()).getAsString());
                assertNull(gathered.put(position, owner), "同一格不在两页里重复给：" + position);
            }
            if (!page.get("more").getAsBoolean()) break;
            after = OptionalLong.of(page.get("after").getAsLong());
        }
        assertTrue(pages > 1, "这么多格应当分页");
        Map<BlockPos, UUID> expected = new HashMap<>();
        record.ownedInChunk(OVERWORLD, 100, -200).forEach((position, owner) -> expected.put(position, owner.playerId()));
        assertEquals(placed, expected.size());
        assertEquals(expected, gathered, "几页拼起来就是整块");
    }

    @Test
    void 记录还没登记时答一页空的() {
        JsonObject page = OwnershipQuery.page(null, OVERWORLD, 0, 0, OptionalLong.empty());
        assertEquals(0, page.getAsJsonArray("cells").size());
        assertFalse(page.get("more").getAsBoolean());
    }
}
