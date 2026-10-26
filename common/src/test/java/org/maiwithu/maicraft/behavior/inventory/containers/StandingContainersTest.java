// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 记得的容器与现场核对：格子加载了、现场不是那个容器，这条记忆当场划掉，不再给路线引路；
 * 没加载的格子记忆先算数，到场再核对。
 */
class StandingContainersTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    /** 实机那条坐标早已不对的记得箱：地面上不存在这一格，这一格加载后是石头。 */
    private static final WorldPosition PHANTOM = new WorldPosition(104, -43, 19, null);
    private static final WorldPosition GOOD_CHEST = new WorldPosition(147, 63, 78, null);

    @TempDir
    Path temp;

    /** 现场替身：表里登记的格子给登记的方块，其余一律没加载。 */
    private static StandingContainers.ReadsCells cellsAt(Map<WorldPosition, String> loaded) {
        return at -> Optional.ofNullable(loaded.get(at));
    }

    private StandingContainers standing(WorldMemory memory, Map<WorldPosition, String> loaded) {
        return new StandingContainers(memory, cellsAt(loaded), "minecraft:chest"::equals);
    }

    private WorldMemory memoryWith(WorldPosition at, List<String> contents) {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        memory.rememberContainerOpened(at, "minecraft:chest", contents, NOW);
        return memory;
    }

    @Test
    void 现场对不上的记得箱_不再给出并当场划掉() {
        WorldMemory memory = memoryWith(PHANTOM, List.of("minecraft:wheat"));
        Map<WorldPosition, String> loaded = new HashMap<>();
        loaded.put(PHANTOM, "minecraft:stone");
        StandingContainers containers = standing(memory, loaded);

        assertTrue(containers.near(WorldPosition.here(100, 63, 20), 200).isEmpty(),
                "那一格现在是石头，不是箱子，不引路");
        assertTrue(containers.at(PHANTOM).isEmpty());
        // 事实记下：这条记忆划掉了，后面的问价、动手都不会再撞它。
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, PHANTOM).isEmpty(), "对不上的记忆要划掉");
    }

    @Test
    void 正常的记得箱_现场对得上照样给出() {
        WorldMemory memory = memoryWith(GOOD_CHEST, List.of("minecraft:wheat"));
        Map<WorldPosition, String> loaded = new HashMap<>();
        loaded.put(GOOD_CHEST, "minecraft:chest");
        StandingContainers containers = standing(memory, loaded);

        List<MemoryRecord> near =
                containers.near(WorldPosition.here(140, 63, 70), 64);
        assertEquals(1, near.size());
        assertEquals(GOOD_CHEST, near.getFirst().position());
        assertTrue(containers.at(GOOD_CHEST).isPresent());
    }

    @Test
    void 没加载的格子_记忆先算数到场再核对() {
        WorldMemory memory = memoryWith(PHANTOM, List.of("minecraft:wheat"));
        StandingContainers containers = standing(memory, Map.of());

        assertEquals(1, containers.near(WorldPosition.here(100, 63, 20), 200).size());
        assertTrue(containers.at(PHANTOM).isPresent());
    }

    @Test
    void 记忆里没记方块类型的_现场是任意容器就算数() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        memory.rememberContainerSeen(new WorldPosition(5, 64, 5, null), null, NOW);
        Map<WorldPosition, String> loaded = new HashMap<>();
        // 看见时只当是箱子，现场其实是木桶：都是容器，记忆先算数，开箱时以现场为准。
        loaded.put(new WorldPosition(5, 64, 5, null), "minecraft:barrel");
        StandingContainers containers = new StandingContainers(memory, cellsAt(loaded),
                blockId -> blockId.equals("minecraft:chest") || blockId.equals("minecraft:barrel"));

        assertTrue(containers.at(new WorldPosition(5, 64, 5, null)).isPresent());
    }

    @Test
    void 现场是别种容器的_记成箱子的那条划掉() {
        WorldMemory memory = memoryWith(GOOD_CHEST, List.of("minecraft:wheat"));
        Map<WorldPosition, String> loaded = new HashMap<>();
        loaded.put(GOOD_CHEST, "minecraft:barrel");
        StandingContainers containers = standing(memory, loaded);

        assertTrue(containers.at(GOOD_CHEST).isEmpty());
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, GOOD_CHEST).isEmpty(),
                "记成箱子、现场是木桶：这条记忆已错，划掉");
    }
}
