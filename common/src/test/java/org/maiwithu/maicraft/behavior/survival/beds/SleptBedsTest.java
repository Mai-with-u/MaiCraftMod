// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.beds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/** 记忆里最近睡过的床：只认亲手用过的床，按记下的时刻挑最新；只是看见过的床、用过的工作台不算。 */
class SleptBedsTest {

    @TempDir
    Path temp;

    @Test
    void 挑最近睡过的那张_看见过的床和工作台不算() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "key");
        WorldPosition old = new WorldPosition(1, 64, 1, "minecraft:overworld");
        WorldPosition recent = new WorldPosition(50, 70, -20, "minecraft:overworld");
        memory.rememberWorkstationUsed(old, "minecraft:red_bed", Instant.ofEpochSecond(100));
        memory.rememberWorkstationUsed(recent, "minecraft:white_bed", Instant.ofEpochSecond(200));
        memory.workstationSeen(new WorldPosition(9, 64, 9, "minecraft:overworld"), "minecraft:blue_bed", Instant.ofEpochSecond(300));
        memory.rememberWorkstationUsed(new WorldPosition(5, 64, 5, "minecraft:overworld"), "minecraft:crafting_table",
                Instant.ofEpochSecond(400));
        assertEquals(Optional.of(recent), SleptBeds.latest(memory));
    }

    @Test
    void 认家只认真睡过的床_只放下没睡的不算_睡过再放也还算() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "key");
        WorldPosition slept = new WorldPosition(1, 64, 1, "minecraft:overworld");
        WorldPosition placedOnly = new WorldPosition(80, 70, 80, "minecraft:overworld");
        memory.rememberBedSleptIn(slept, "minecraft:red_bed", Instant.ofEpochSecond(100));
        // 路上随手放下的一张床，记得更晚：收回床时认得，认家时不算。
        memory.rememberWorkstationUsed(placedOnly, "minecraft:white_bed", Instant.ofEpochSecond(200));
        assertEquals(Optional.of(placedOnly), SleptBeds.latest(memory));
        assertEquals(Optional.of(slept), SleptBeds.latestReallySleptIn(memory));
        // 睡过的那张后来又被她放了一回（拆了重放）：合并后"睡过"那一项还在。
        memory.rememberWorkstationUsed(slept, "minecraft:red_bed", Instant.ofEpochSecond(300));
        assertEquals(Optional.of(slept), SleptBeds.latestReallySleptIn(memory));
    }

    @Test
    void 一张都没睡过为空() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "key");
        assertTrue(SleptBeds.latest(memory).isEmpty());
    }
}
