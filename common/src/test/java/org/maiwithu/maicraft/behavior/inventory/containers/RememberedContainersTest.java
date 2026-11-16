// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.mcp.view.WorldMemoryView;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记得的容器读端：世界记忆里的容器按距离给候选，谁放的都给（归属不拦），被圈地挡住的不给。 */
class RememberedContainersTest {

    private static final String SELF = "00000000-0000-0000-0000-000000000001";
    private static final String STRANGER = "00000000-0000-0000-0000-000000000002";
    private static final WorldPosition NEAR = new WorldPosition(10, -60, 12, "minecraft:overworld");

    @TempDir
    Path temp;

    // 归属替身：只有"别人的箱子"那一格记着是别人放的，其余都算没记录的自家东西。
    private static ReadsBlockOwnership ownershipWithOtherAt(int x, int y, int z) {
        return new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int px, int py, int pz) {
                boolean other = px == x && py == y && pz == z;
                return Optional.of(new PlacedBy(other ? STRANGER : SELF));
            }
        };
    }

    private RememberedContainers containers(WorldMemory memory, WorldPosition standing, Protection protection) {
        return new RememberedContainers(memory, () -> standing, protection);
    }

    private WorldMemory memoryWithContainers() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        memory.rememberContainerSeen(new WorldPosition(10, -60, 12, "minecraft:overworld"),
                "minecraft:chest", Instant.now());
        memory.rememberContainerSeen(new WorldPosition(30, -60, 30, "minecraft:overworld"),
                "minecraft:barrel", Instant.now());
        return memory;
    }

    private Protection protectionOtherAt(int x, int y, int z) {
        return new Protection(ownershipWithOtherAt(x, y, z), List::of,
                name -> Optional.empty(), SELF);
    }

    /** 圈地替身：近处那只箱子圈进了"邻居的院子"。 */
    private Protection protectionFencedNear() {
        RememberedRegion yard = new RememberedRegion("邻居的院子", NEAR, 4);
        return new Protection((dimension, px, py, pz) -> Optional.empty(), () -> List.of(yard),
                name -> Optional.empty(), SELF);
    }

    @Test
    void 距离以内的记得容器按名字与位置给候选() {
        WorldMemory memory = memoryWithContainers();
        RememberedContainers containers = containers(memory, new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionOtherAt(-1, -1, -1));

        List<KnownContainer> nearby = containers.within(16);
        assertEquals(1, nearby.size());
        assertEquals(10, nearby.getFirst().at().x());
        assertEquals(-60, nearby.getFirst().at().y());
        assertTrue(nearby.getFirst().name().contains("chest"));
    }

    @Test
    void 距离之外的不给() {
        WorldMemory memory = memoryWithContainers();
        RememberedContainers containers = containers(memory, new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionOtherAt(-1, -1, -1));
        // 最近的记忆箱隔着不到三格，两格之内没有；更远的木桶在 27 格外。
        assertTrue(containers.within(2).isEmpty());
    }

    @Test
    void 别人放的也进候选() {
        WorldMemory memory = memoryWithContainers();
        // (10, -60, 12) 是别人放的：归属不拦，照样给候选。
        RememberedContainers containers = containers(memory, new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionOtherAt(10, -60, 12));
        assertEquals(2, containers.within(64).size());
    }

    @Test
    void 圈地里的不进候选() {
        WorldMemory memory = memoryWithContainers();
        // (10, -60, 12) 圈进了"邻居的院子"：不往里塞东西；30 格外那只照给。
        RememberedContainers containers = containers(memory, new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionFencedNear());
        List<KnownContainer> nearby = containers.within(64);
        assertEquals(1, nearby.size());
        assertEquals(30, nearby.getFirst().at().x());
    }

    @Test
    void 已加载区块里被拆掉的旧箱子即时淘汰_未加载区块保留() {
        WorldMemory memory = memoryWithContainers();
        WorldPosition unloadedBarrel = new WorldPosition(30, -60, 30, "minecraft:overworld");
        Map<WorldPosition, String> loadedCells = Map.of(NEAR, "minecraft:air");
        FacilityKinds kinds = new FacilityKinds(FakeBlockTags.vanilla());
        StandingContainers standing = new StandingContainers(memory,
                at -> Optional.ofNullable(loadedCells.get(at)), kinds::isContainer);
        RememberedContainers containers = new RememberedContainers(standing,
                () -> new WorldPosition(12, -60, 10, "minecraft:overworld"),
                protectionOtherAt(-1, -1, -1));

        List<KnownContainer> candidates = containers.within(64);
        assertEquals(1, candidates.size(), "已拆成空气的近处箱子不再选作腾包候选，未加载的远处木桶保留");
        assertEquals(unloadedBarrel, candidates.getFirst().at());
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, NEAR).isEmpty(), "已确认拆掉的容器记忆当场淘汰");
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, unloadedBarrel).isPresent(), "未加载区块不得误删");
    }

    @Test
    void 场景设施刷新与世界记忆视图同步淘汰已加载失效容器() {
        WorldMemory memory = memoryWithContainers();
        WorldPosition unloadedBarrel = new WorldPosition(30, -60, 30, "minecraft:overworld");
        FacilityKinds kinds = new FacilityKinds(FakeBlockTags.vanilla());
        Scene scene = new Scene(memory, kinds);
        scene.updateSelf(new SelfSight.Facts(12.5, -60, 10.5, 0f,
                20, 20, 300, 300, null, List.of(), List.of(), true, false));

        // 近处箱子所在格已加载但被换成了石头，感知刷新设施后立即从世界记忆淘汰。
        scene.updateFacilities(20L, Instant.now(), List.of(),
                at -> NEAR.equals(at) ? Optional.of("minecraft:stone") : Optional.empty());
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, NEAR).isEmpty());
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, unloadedBarrel).isPresent());

        // 远处木桶区块随后加载且确认已被敲空：世界记忆视图读取前同样淘汰失效容器。
        WorldMemoryView.pruneLoadedContainers(memory,
                at -> unloadedBarrel.equals(at) ? Optional.of("minecraft:air") : Optional.empty(), kinds);
        assertTrue(memory.recordAt(MemoryKind.CONTAINER, unloadedBarrel).isEmpty());
    }
}
