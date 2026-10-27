// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/** 机器档案库：按世界与名字存取、拆除标记留住蓝图、读不出的档案按没有对待。 */
class MachineArchivesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private MachineBlueprint oneCell() {
        return new MachineBlueprint(
                List.of(PlannedCell.block(new BlockPos(3, 64, 3), Blocks.FURNACE.defaultBlockState(), Set.of())),
                List.of(new MachineBlueprint.Part(new BlockPos(3, 64, 3), Direction.NORTH, "ae2:terminal")),
                List.of(new MachineBlueprint.Segment("test:belt", List.of(new BlockPos(3, 65, 3)))),
                List.of(new MachineBlueprint.Setting(new BlockPos(3, 64, 3), "side.north", "output:items")),
                List.of(new MachineBlueprint.Process(new BlockPos(3, 64, 3), "test:plate")));
    }

    @Test
    void 存进再读回_蓝图与机器条目原样() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        MachineArchive archive = MachineArchive.fresh("压机", "minecraft:overworld", new BlockPos(3, 64, 3),
                oneCell(), "design-1");
        assertTrue(archives.save(archive));
        MachineArchive read = archives.find("压机").orElseThrow();
        assertEquals("压机", read.name());
        assertEquals("minecraft:overworld", read.dimension());
        assertEquals(new BlockPos(3, 64, 3), read.anchor());
        assertEquals(oneCell(), read.blueprint(), "逐格与四种机器条目读回原样");
        assertEquals("design-1", read.designId());
        assertFalse(read.removed());
    }

    @Test
    void 档案按世界分开_换世界读不到() {
        DocumentStore documents = new DocumentStore(tempDir.resolve("state.sqlite"));
        MachineArchives first = new MachineArchives(documents, "world1");
        MachineArchives second = new MachineArchives(documents, "world2");
        assertTrue(first.save(MachineArchive.fresh("压机", "minecraft:overworld", BlockPos.ZERO, oneCell(), null)));
        assertTrue(second.find("压机").isEmpty(), "另一个世界的档案互不可见");
        assertTrue(first.find("压机").isPresent());
    }

    @Test
    void 拆除标记与合并结果都留得住() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        MachineArchive archive = MachineArchive.fresh("压机", "minecraft:overworld", BlockPos.ZERO, oneCell(), null);
        assertTrue(archives.save(archive.markRemoved()));
        assertTrue(archives.find("压机").orElseThrow().removed(), "拆除只做标记，蓝图保留");
        MachineBlueprint merged = MachinePatch.merge(oneCell(), new MachineBlueprint(
                List.of(PlannedCell.block(new BlockPos(4, 64, 3), Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of()));
        MachineArchive updated = archives.find("压机").orElseThrow().withBlueprint(merged, null, null, null)
                .joinedTo(Map.of("me", "net1"));
        assertTrue(archives.save(updated));
        MachineArchive read = archives.find("压机").orElseThrow();
        assertEquals(2, read.blueprint().cells().size());
        assertEquals(Optional.of("net1"), Optional.ofNullable(read.networks().get("me")));
    }

    @Test
    void 按一格找档案_只认声明要放方块的格_拆了的与别的维度不算() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        MachineBlueprint ship = new MachineBlueprint(List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.air(new BlockPos(0, 1, 0))), List.of(), List.of(), List.of(), List.of());
        BlockPos anchor = new BlockPos(100, 70, 100);
        assertTrue(archives.save(MachineArchive.fresh("Airship", "minecraft:overworld", anchor, ship, null)));
        assertTrue(archives.save(MachineArchive.fresh("下界的", "minecraft:the_nether", anchor, ship, null)));

        ArchivedMachines.Machine found = archives.covering("minecraft:overworld", new BlockPos(101, 70, 100)).orElseThrow();
        assertEquals("Airship", found.name(), "名字按存的原样给，不是库里小写过的键");
        assertEquals(Set.of(new BlockPos(100, 70, 100), new BlockPos(101, 70, 100)), found.blocks(), "清空的格不算方块");
        assertTrue(archives.covering("minecraft:overworld", new BlockPos(100, 71, 100)).isEmpty(), "清空的格不算盖住");
        assertTrue(archives.covering("minecraft:the_end", new BlockPos(100, 70, 100)).isEmpty(), "维度对不上不算");

        assertTrue(archives.save(archives.find("Airship").orElseThrow().markRemoved()));
        assertTrue(archives.covering("minecraft:overworld", new BlockPos(100, 70, 100)).isEmpty(), "拆了的不算");
    }

    @Test
    void 组装后跟到结构上_锚点平移偏移不变_世界里不再算盖住() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        MachineBlueprint ship = new MachineBlueprint(List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        BlockPos anchor = new BlockPos(100, 70, 100);
        assertTrue(archives.save(MachineArchive.fresh("飞艇", "minecraft:overworld", anchor, ship, null)));
        BlockPos worldAssembler = new BlockPos(101, 70, 100);
        BlockPos plotAssembler = new BlockPos(20_000_008, 128, 20_000_008);

        ArchivedMachines.Moved moved = archives.moveOntoStructure("minecraft:overworld", worldAssembler, "ship-1",
                plotAssembler).orElseThrow();
        assertEquals("飞艇", moved.name());
        assertTrue(moved.saved());
        MachineArchive read = archives.find("飞艇").orElseThrow();
        assertEquals("ship-1", read.structure(), "读回时还记着在哪个结构上");
        assertEquals(new BlockPos(20_000_007, 128, 20_000_008), read.anchor(), "锚点和组装器挪得一样多");
        assertEquals(ship, read.blueprint(), "组装是纯平移，偏移一格不变");
        assertEquals(Optional.of("飞艇"), archives.onStructure("ship-1"));
        assertTrue(archives.covering("minecraft:overworld", worldAssembler).isEmpty(), "跟到结构上后世界里不再算盖住");
        assertTrue(archives.moveOntoStructure("minecraft:overworld", new BlockPos(0, 0, 0), "ship-2", plotAssembler)
                .isEmpty(), "组装器不在任何档案里不跟");
    }

    @Test
    void 拆回跟回世界_按组装器落点和转的整九十度换算_蓝图跟着转() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        BlockState facingNorth = Blocks.FURNACE.defaultBlockState()
                .setValue(FurnaceBlock.FACING, Direction.NORTH);
        MachineBlueprint ship = new MachineBlueprint(List.of(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 0, 0), facingNorth, Set.of("facing"))),
                List.of(new MachineBlueprint.Part(new BlockPos(1, 0, 0), Direction.NORTH, "test:part")),
                List.of(new MachineBlueprint.Segment("test:glue", List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)))),
                List.of(new MachineBlueprint.Setting(new BlockPos(1, 0, 0), "mode", "fast")), List.of());
        BlockPos plotAnchor = new BlockPos(20_000_007, 128, 20_000_008);
        BlockPos plotAssembler = new BlockPos(20_000_008, 128, 20_000_008);
        assertTrue(archives.save(MachineArchive.freshOn("ship-1", "飞艇", "minecraft:overworld", plotAnchor, ship)));
        BlockPos landed = new BlockPos(50, 64, 50);

        ArchivedMachines.Moved moved = archives.moveToWorld("ship-1", plotAssembler, landed, Rotation.CLOCKWISE_90)
                .orElseThrow();
        assertTrue(moved.saved());
        MachineArchive read = archives.find("飞艇").orElseThrow();
        assertNull(read.structure(), "跟回世界后不再记在结构上");
        // 锚点在组装器西边一格：顺时针转 90° 后到组装器北边一格。
        assertEquals(new BlockPos(50, 64, 49), read.anchor());
        assertEquals(new BlockPos(0, 0, 1), read.blueprint().cells().get(1).pos(), "东边一格转到南边一格");
        assertEquals(Direction.EAST, read.blueprint().cells().get(1).state().getValue(
                FurnaceBlock.FACING), "朝北的熔炉转成朝东");
        assertEquals(Direction.EAST, read.blueprint().parts().getFirst().side(), "部件的面跟着转");
        assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(0, 0, 1)),
                read.blueprint().installations().getFirst().offsets(), "安装段的格跟着转");
        assertEquals(new BlockPos(0, 0, 1), read.blueprint().settings().getFirst().offset(), "设置的格跟着转");
        assertTrue(archives.onStructure("ship-1").isEmpty());
        assertTrue(archives.covering("minecraft:overworld", landed.offset(0, 0, -1)).isPresent(), "回到世界里又能按格找到");
    }

    @Test
    void 没有这个名字_给空() {
        MachineArchives archives = new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1");
        assertTrue(archives.find("没有的机器").isEmpty());
    }
}
