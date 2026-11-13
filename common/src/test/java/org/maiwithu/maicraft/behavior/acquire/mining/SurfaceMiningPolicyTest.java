// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 地表取材与营地范围判断测试：营地箱子、工作台旁不在平地往下挖，侧面露头的坡面排在脚下平地之前，
 * 平地成批时先横向揭表层再往下挖，路过瞥见的野外箱子不算营地。
 */
class SurfaceMiningPolicyTest {

    @Test
    void campgroundCoversUsedChestWorkstationAndNamedCampground(@TempDir Path temp) {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
        PermissionCheck permission = new PermissionCheck(
                new Protection((d, x, y, z) -> Optional.empty(), memory, memory, "self",
                        SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        Campground camp = Campground.of(permission, memory);

        // 开过的营地箱子在 (-358, 65, 47)：8～9 格外的 (-361, 63, 39) 在营地范围内，20 格外不在。
        memory.rememberContainerOpened(new WorldPosition(-358, 65, 47, "minecraft:overworld"),
                "minecraft:chest", List.of("minecraft:cobblestone"), Instant.parse("2026-11-08T13:30:00Z"));
        assertTrue(camp.covers(new WorldPosition(-361, 63, 39, "minecraft:overworld")));
        assertFalse(camp.covers(new WorldPosition(-380, 63, 39, "minecraft:overworld")));

        // 路过瞥见但没打开过的野外箱子不算营地，免得在矿井或废墟里不敢挖。
        memory.rememberContainerSeen(new WorldPosition(100, 30, 100, "minecraft:overworld"),
                "minecraft:chest", Instant.parse("2026-11-08T13:31:00Z"));
        assertFalse(camp.covers(new WorldPosition(102, 30, 100, "minecraft:overworld")));

        // 记住的“营地”地标周围十格算营地。
        memory.remember("临时营地", new WorldPosition(50, 64, 50, "minecraft:overworld"));
        assertTrue(camp.covers(new WorldPosition(56, 64, 50, "minecraft:overworld")));
    }

    @Test
    void outsideCampFiltersFlatGroundButKeepsLogsAndExposedSlope() {
        Campground camp = pos -> Math.abs(pos.x()) <= 10 && Math.abs(pos.z()) <= 10;
        MinableSpot campFlatDirt = new MinableSpot(new BlockPos(2, 63, 2), "minecraft:dirt", "minecraft:dirt", false);
        MinableSpot campSlopeDirt = new MinableSpot(new BlockPos(5, 65, 2), "minecraft:dirt", "minecraft:dirt", true);
        MinableSpot campTreeLog = new MinableSpot(new BlockPos(3, 64, 3), "minecraft:oak_log", "minecraft:oak_log", false);
        MinableSpot outsideDirt = new MinableSpot(new BlockPos(15, 63, 0), "minecraft:dirt", "minecraft:dirt", false);

        List<MinableSpot> allowed = SurfaceMiningPolicy.outsideCampFlatGround(
                List.of(campFlatDirt, campSlopeDirt, campTreeLog, outsideDirt), "minecraft:overworld", camp);
        assertEquals(List.of(campSlopeDirt, campTreeLog, outsideDirt), allowed);
    }

    @Test
    void prioritizeExposedRanksNearbySlopeAheadOfFlatGroundUnderfoot() {
        WorldPosition self = new WorldPosition(0, 64, 0, "minecraft:overworld");
        MinableSpot underfootFlat = new MinableSpot(new BlockPos(1, 63, 0), "minecraft:dirt", "minecraft:dirt", false);
        MinableSpot slopeEightAway = new MinableSpot(new BlockPos(8, 65, 0), "minecraft:dirt", "minecraft:dirt", true);
        MinableSpot farSlope = new MinableSpot(new BlockPos(45, 65, 0), "minecraft:dirt", "minecraft:dirt", true);

        List<MinableSpot> ranked = SurfaceMiningPolicy.prioritizeExposed(
                List.of(underfootFlat, farSlope, slopeEightAway), self);
        assertEquals(slopeEightAway, ranked.get(0), "32 格内的坡面露头优先于脚边平地");
        assertEquals(underfootFlat, ranked.get(1), "太远的坡面不舍近求远");
        assertEquals(farSlope, ranked.get(2));
    }

    @Test
    void batchOfSpreadsHorizontallyAcrossTopLayerBeforeDiggingDeeper() {
        // 平地两层泥土（63 表层、62 下层）：要 3 块时应先把 63 表层三格揭走（只低一格），不先往 62 层抠两格竖坑。
        List<MinableSpot> flat = List.of(
                new MinableSpot(new BlockPos(0, 63, 0), "minecraft:dirt", "minecraft:dirt", false),
                new MinableSpot(new BlockPos(0, 62, 0), "minecraft:dirt", "minecraft:dirt", false),
                new MinableSpot(new BlockPos(1, 63, 0), "minecraft:dirt", "minecraft:dirt", false),
                new MinableSpot(new BlockPos(0, 63, 1), "minecraft:dirt", "minecraft:dirt", false));

        List<MinableSpot> batch = SurfaceMiningPolicy.batchOf(flat, 3);
        assertEquals(3, batch.size());
        assertTrue(batch.stream().allMatch(spot -> spot.pos().getY() == 63),
                "表层够数时全在表层平挖，不往下掏两格深坑：" + batch);
    }
}
