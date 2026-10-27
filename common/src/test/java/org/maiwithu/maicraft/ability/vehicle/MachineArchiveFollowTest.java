// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;

/**
 * 机器档案跟着结构走：组装时把组装器两头的格递给机器能力、跟上了说一句；拆回按拆前的朝向算转了几个 90°，
 * 读不到朝向或找不到组装器落在哪就不动档案、照实说留在结构上；结构上没记档案不提。
 */
class MachineArchiveFollowTest {

    private static final BlockPos WORLD = new BlockPos(10, 64, 10);
    private static final BlockPos PLOT = new BlockPos(20_000_008, 128, 20_000_008);

    /** 机器档案替身：记下被要求怎么挪，按给定的档案名答。 */
    private static final class Archives implements ArchivedMachines {
        final List<String> calls = new ArrayList<>();
        String onStructure;
        Rotation turned;

        @Override public Optional<Machine> covering(String dimension, BlockPos cell) {
            return Optional.empty();
        }

        @Override public Optional<String> onStructure(String structureId) {
            return Optional.ofNullable(onStructure);
        }

        @Override public Optional<Moved> moveOntoStructure(String dimension, BlockPos worldAssembler, String structureId,
                BlockPos plotAssembler) {
            calls.add("onto " + structureId + " " + worldAssembler.toShortString() + " -> " + plotAssembler.toShortString());
            return Optional.of(new Moved("飞艇", true));
        }

        @Override public Optional<Moved> moveToWorld(String structureId, BlockPos plotAssembler, BlockPos worldAssembler,
                Rotation turn) {
            calls.add("world " + structureId);
            turned = turn;
            return onStructure == null ? Optional.empty() : Optional.of(new Moved(onStructure, true));
        }
    }

    private static VehicleAssembler.Conversion converted(BlockPos assembler, BlockPos plot) {
        return new VehicleAssembler.Conversion(false, true, "ship-1", 12, "", assembler, Map.of(), plot);
    }

    @Test
    void 组装_把组装器两头递过去_跟上了写一句() {
        Archives archives = new Archives();
        VehicleAssembleDetails.MachineArchiveMove move = new MachineArchiveFollow(archives, () -> "minecraft:overworld")
                .afterAssemble(converted(WORLD, PLOT));
        assertEquals(VehicleAssembleDetails.ON_STRUCTURE, move.state());
        assertEquals("飞艇", move.name());
        assertEquals(List.of("onto ship-1 " + WORLD.toShortString() + " -> " + PLOT.toShortString()), archives.calls);
    }

    @Test
    void 组装_不知道组装器进了地块哪一格_不动档案() {
        Archives archives = new Archives();
        assertNull(new MachineArchiveFollow(archives, () -> "minecraft:overworld").afterAssemble(converted(WORLD, null)));
        assertTrue(archives.calls.isEmpty());
    }

    @Test
    void 拆回_按拆前朝向算转了几个九十度() {
        Archives archives = new Archives();
        archives.onStructure = "飞艇";
        VehicleAssembleDetails.MachineArchiveMove move = new MachineArchiveFollow(archives, () -> "minecraft:overworld")
                .afterDisassemble("ship-1", 92.0, converted(WORLD, PLOT));
        assertEquals(VehicleAssembleDetails.IN_WORLD, move.state());
        assertEquals(Rotation.CLOCKWISE_90, archives.turned, "车头朝西是顺时针转了一下");
        assertEquals(Rotation.CLOCKWISE_180, MachineArchiveFollow.turnOf(178));
        assertEquals(Rotation.COUNTERCLOCKWISE_90, MachineArchiveFollow.turnOf(271));
        assertEquals(Rotation.NONE, MachineArchiveFollow.turnOf(359));
    }

    @Test
    void 拆回_读不到朝向_不动档案照实说留在结构上() {
        Archives archives = new Archives();
        archives.onStructure = "飞艇";
        VehicleAssembleDetails.MachineArchiveMove move = new MachineArchiveFollow(archives, () -> "minecraft:overworld")
                .afterDisassemble("ship-1", null, converted(WORLD, PLOT));
        assertEquals(VehicleAssembleDetails.KEPT, move.state());
        assertTrue(move.note().contains("没读到结构朝哪"), move.note());
        assertTrue(archives.calls.isEmpty(), "换算不了就不挪");
    }

    @Test
    void 拆回_结构上没记档案_不提() {
        Archives archives = new Archives();
        assertNull(new MachineArchiveFollow(archives, () -> "minecraft:overworld")
                .afterDisassemble("ship-1", 0.0, converted(WORLD, PLOT)));
        assertNull(new MachineArchiveFollow(archives, () -> "minecraft:overworld")
                .afterDisassemble("ship-1", null, converted(null, PLOT)));
    }
}
