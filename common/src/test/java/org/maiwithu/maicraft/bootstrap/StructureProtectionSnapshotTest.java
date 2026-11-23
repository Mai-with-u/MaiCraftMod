// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.permission.StructureProtection;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 结构保护区快照端到端测试：验证建筑非空气格、单格残档过滤、外框流体保护、退役解除，
 * 以及在役机器含流体源格保护与修订号刷新。
 */
class StructureProtectionSnapshotTest {

    private static final String DIM = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 建筑非空气格与外框流体受保护_单格残档与已退役建筑自动放行() {
        DocumentStore store = new DocumentStore(tempDir.resolve("world.sqlite"));
        BuildingArchives buildings = new BuildingArchives(store, "world-1");

        // 有效建筑（2 格以上非空气，围出 0..2 的外框，内部 (1,0,1) 是水池空位）。
        BuildingArchive house = new BuildingArchive("水池小院", DIM, new BlockPos(10, 64, 10), 0, null, List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(2, 0, 2), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.air(new BlockPos(1, 0, 1))), null, null);
        // 历史单格残档（非空气格 <= 1）：必须过滤掉，不形成结构保护。
        BuildingArchive singleCellStub = new BuildingArchive("单格残档", DIM, new BlockPos(50, 64, 50), 0, null, List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.air(new BlockPos(1, 0, 0))), null, null);
        assertTrue(buildings.save(house));
        assertTrue(buildings.save(singleCellStub));

        // 构造一个带流体源格的在役机器替身。
        BlockPos machineBlock = new BlockPos(30, 64, 30);
        BlockPos machineWater = new BlockPos(31, 64, 30);
        String[] machineRev = {"rev-1"};
        List<ArchivedMachines.ActiveMachine>[] activeMachines = new List[] {
                List.of(new ArchivedMachines.ActiveMachine("洗矿机", DIM, Set.of(machineBlock, machineWater)))
        };
        ArchivedMachines machines = new ArchivedMachines() {
            @Override public Optional<Machine> covering(String dimension, BlockPos cell) {
                return Optional.empty();
            }
            @Override public List<ActiveMachine> activeInWorld() { return activeMachines[0]; }
            @Override public String revision() { return machineRev[0]; }
        };

        StructureProtectionSnapshot snapshot = new StructureProtectionSnapshot(buildings, machines, 1_000L);

        // 建筑非空气格：方块与流体均保护。
        WorldPosition wall = new WorldPosition(10, 64, 10, DIM);
        assertEquals(StructureProtection.building("水池小院"), snapshot.blockAt(wall));
        assertEquals(StructureProtection.building("水池小院"), snapshot.fluidAt(wall));

        // 建筑外框内空气/水池位：方块不误拦，但流体舀取受保护。
        WorldPosition poolInside = new WorldPosition(11, 64, 11, DIM);
        assertFalse(snapshot.blockAt(poolInside).protects(), "建筑内部未声明非空气的格不拦普通方块判定");
        assertEquals(StructureProtection.building("水池小院"), snapshot.fluidAt(poolInside),
                "建筑外框内的水池/喷泉应受流体结构保护");

        // 历史单格残档：不保护。
        WorldPosition stubCell = new WorldPosition(50, 64, 50, DIM);
        assertFalse(snapshot.blockAt(stubCell).protects(), "非空气格 <= 1 的历史单格残档不形成保护");
        assertFalse(snapshot.fluidAt(stubCell).protects());

        // 在役机器含流体源格：方块与流体均保护。
        WorldPosition mBlock = new WorldPosition(30, 64, 30, DIM);
        WorldPosition mWater = new WorldPosition(31, 64, 30, DIM);
        assertEquals(StructureProtection.machine("洗矿机"), snapshot.blockAt(mBlock));
        assertEquals(StructureProtection.machine("洗矿机"), snapshot.fluidAt(mWater));

        // 建筑退役后刷新快照：立刻解除保护。
        assertTrue(buildings.save(house.markRemoved()));
        snapshot.refresh(1_000L + StructureProtectionSnapshot.REFRESH_MILLIS);
        assertFalse(snapshot.blockAt(wall).protects(), "退役后建筑结构保护解除");
        assertFalse(snapshot.fluidAt(poolInside).protects(), "退役后建筑外框流体保护解除");

        // 机器拆除后刷新快照：立刻解除保护。
        activeMachines[0] = List.of();
        machineRev[0] = "rev-2";
        snapshot.refresh(1_000L + StructureProtectionSnapshot.REFRESH_MILLIS * 2);
        assertFalse(snapshot.fluidAt(mWater).protects(), "拆除后机器结构保护解除");
    }
}
