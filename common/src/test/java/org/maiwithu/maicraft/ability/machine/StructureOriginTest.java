// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/** 结构局部坐标的原点：档案锚点、物理组装器、地块中心依次取；文字里地块里的格换成局部坐标，别的数不动。 */
class StructureOriginTest {

    private static final BlockPos ASSEMBLER = new BlockPos(20_000_008, 128, 20_000_008);
    private static final BlockPos CENTER = new BlockPos(20_000_000, 128, 20_000_000);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 只答组装器在哪与地块中心的工地替身。 */
    private record Site(Optional<BlockPos> assembler) implements StructureWorksites.Worksite {
        @Override public String structureId() { return "ship-1"; }
        @Override public boolean present() { return true; }
        @Override public boolean moving() { return false; }
        @Override public String name() { return ""; }
        @Override public BlockPos plotCenter() { return CENTER; }
        @Override public BlockPos min() { return CENTER; }
        @Override public BlockPos max() { return ASSEMBLER; }
        @Override public BlockPos plotCellAt(Vec3 world) { return BlockPos.containing(world); }
        @Override public ConstructionServices services(ConstructionServices onLand, Permissions permissions) { return onLand; }
        @Override public BringsPlayerClose close() { return (target, permissions) -> null; }
        @Override public BringsPlayerClose closeKeepingSeat() { return (target, permissions) -> null; }
        @Override public ReadsBlocks blocks() { return null; }
        @Override public void forgetScan() { }
    }

    @Test
    void 有档案用档案锚点_没有用组装器_再没有用地块中心() {
        MachineArchive archive = MachineArchive.freshOn("ship-1", "飞艇", "minecraft:overworld", ASSEMBLER.west(),
                new MachineBlueprint(List.of(PlannedCell.block(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState(), Set.of())),
                        List.of(), List.of(), List.of(), List.of()));
        StructureOrigin byArchive = StructureOrigin.of(new Site(Optional.of(ASSEMBLER)), Optional.of(archive));
        assertEquals(ASSEMBLER.west(), byArchive.plot());
        assertEquals("飞艇", byArchive.archive());
        assertEquals(StructureOrigin.ARCHIVE, byArchive.how());
        StructureOrigin byAssembler = StructureOrigin.of(new Site(Optional.of(ASSEMBLER)), Optional.empty());
        assertEquals(ASSEMBLER, byAssembler.plot());
        assertNull(byAssembler.archive());
        StructureOrigin byCenter = StructureOrigin.of(new Site(Optional.empty()), Optional.empty());
        assertEquals(CENTER, byCenter.plot());
        assertEquals(StructureOrigin.PLOT_CENTER, byCenter.how());
    }

    @Test
    void 文字里地块里的格换成局部坐标_世界里的格和件数不动() {
        StructureOrigin origin = new StructureOrigin(ASSEMBLER, StructureOrigin.ASSEMBLER, null);
        String text = "放 20000009, 128, 20000008；清障 20000008, 129, 20000007；世界里 100, 64, 100 放了 3 件";
        assertEquals("放 1, 0, 0；清障 0, 1, -1；世界里 100, 64, 100 放了 3 件", origin.localize(text, CENTER, ASSEMBLER));
        assertEquals(new BlockPos(-8, 0, -8), origin.local(CENTER));
    }
}
