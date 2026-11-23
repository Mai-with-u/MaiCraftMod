// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 连通流体三维广度优先搜源测试：确认空桶点到流动水时只沿连通的同种流体回溯，
 * 绝不跨墙、跨空气或跨不同种流体跳到更近的另一片水体（例如隔墙农田水坑）。
 */
class ConnectedFluidSourceTest {

    @Test
    void 隔墙更近的水源不跨墙_沿连通流动水找到真实源格() {
        Map<BlockPos, UseSeams.ReadsWorld.Fluid> fluids = new HashMap<>();
        BlockPos start = new BlockPos(0, 64, 0);
        // 起点到东边 4 格是一条连通水流（含拐弯与向上爬升一格瀑布）。
        fluids.put(start, UseSeams.ReadsWorld.Fluid.FLOWING);
        fluids.put(new BlockPos(1, 64, 0), UseSeams.ReadsWorld.Fluid.FLOWING);
        fluids.put(new BlockPos(2, 64, 0), UseSeams.ReadsWorld.Fluid.FLOWING);
        fluids.put(new BlockPos(2, 65, 0), UseSeams.ReadsWorld.Fluid.FLOWING);
        BlockPos realSource = new BlockPos(3, 65, 0);
        fluids.put(realSource, UseSeams.ReadsWorld.Fluid.SOURCE);

        // 西边隔一格实心墙 (-1,64,0 是 NONE) 就有一个直线距离仅 2 格的农田水坑水源！
        BlockPos behindWall = new BlockPos(-2, 64, 0);
        fluids.put(behindWall, UseSeams.ReadsWorld.Fluid.SOURCE);

        Optional<BlockPos> found = ConnectedFluidSource.find(start, 8,
                cell -> fluids.getOrDefault(cell, UseSeams.ReadsWorld.Fluid.NONE));
        assertEquals(Optional.of(realSource), found, "必须沿连通水流回溯到真实源格，绝不跨墙跳向更近水坑");
    }

    @Test
    void 无连通源格时即使隔墙两格有水坑也返回空() {
        Map<BlockPos, UseSeams.ReadsWorld.Fluid> fluids = new HashMap<>();
        BlockPos start = new BlockPos(0, 64, 0);
        fluids.put(start, UseSeams.ReadsWorld.Fluid.FLOWING);
        fluids.put(new BlockPos(1, 64, 0), UseSeams.ReadsWorld.Fluid.FLOWING);
        // 隔一格空气/墙的不连通农田水坑。
        fluids.put(new BlockPos(0, 64, 2), UseSeams.ReadsWorld.Fluid.SOURCE);

        Optional<BlockPos> found = ConnectedFluidSource.find(start, 8,
                cell -> fluids.getOrDefault(cell, UseSeams.ReadsWorld.Fluid.NONE));
        assertTrue(found.isEmpty(), "没有连通同种源格时必须返回空，不跨墙偷舀不连通水体");
    }

    @Test
    void 超出搜索半径的连通源格不返回_起点即源格直接返回起点() {
        Map<BlockPos, UseSeams.ReadsWorld.Fluid> fluids = new HashMap<>();
        BlockPos start = new BlockPos(0, 64, 0);
        for (int x = 0; x <= 9; x++) {
            fluids.put(new BlockPos(x, 64, 0), x == 9 ? UseSeams.ReadsWorld.Fluid.SOURCE : UseSeams.ReadsWorld.Fluid.FLOWING);
        }
        assertTrue(ConnectedFluidSource.find(start, 8,
                cell -> fluids.getOrDefault(cell, UseSeams.ReadsWorld.Fluid.NONE)).isEmpty(),
                "距离 9 格超过半径 8 格不应返回");
        assertEquals(Optional.of(new BlockPos(9, 64, 0)),
                ConnectedFluidSource.find(start, 9, cell -> fluids.getOrDefault(cell, UseSeams.ReadsWorld.Fluid.NONE)));
        assertEquals(Optional.of(new BlockPos(9, 64, 0)),
                ConnectedFluidSource.find(new BlockPos(9, 64, 0), 8,
                        cell -> fluids.getOrDefault(cell, UseSeams.ReadsWorld.Fluid.NONE)));
    }
}
