// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 量路：平走与上下一格连通，许可决定往下跳几格，挨着岩浆、水里不算能走，过不去的记下挡着什么。 */
class WalkableAreaTest {

    private static WalkableArea measure(CaveFakes.Cave cave, BlockPos start, boolean mayDropDown) {
        WalkableArea area = new WalkableArea(cave, start, mayDropDown);
        while (!area.tick()) {
            // 分刻量，量完为止。
        }
        return area;
    }

    @Test
    void 平走与上下一格连通() {
        CaveFakes.Cave cave = new CaveFakes.Cave().tunnel(new BlockPos(0, 64, 0), new BlockPos(5, 64, 0));
        cave.tunnel(new BlockPos(6, 65, 0), new BlockPos(10, 65, 0));
        // 台阶口头顶多挖一格，跳得上去。
        cave.carved.add(new BlockPos(5, 66, 0));
        WalkableArea area = measure(cave, new BlockPos(0, 64, 0), false);
        assertTrue(area.reached().containsKey(new BlockPos(5, 64, 0)));
        assertTrue(area.reached().containsKey(new BlockPos(10, 65, 0)), "上一级台阶算连通");
    }

    @Test
    void 往下三格许可动土才算连通() {
        CaveFakes.Cave cave = new CaveFakes.Cave().tunnel(new BlockPos(0, 64, 0), new BlockPos(5, 64, 0));
        cave.tunnel(new BlockPos(6, 61, 0), new BlockPos(10, 61, 0));
        cave.carved.add(new BlockPos(6, 63, 0));
        cave.carved.add(new BlockPos(6, 64, 0));
        cave.carved.add(new BlockPos(6, 65, 0));
        assertTrue(measure(cave, new BlockPos(0, 64, 0), true).reached().containsKey(new BlockPos(10, 61, 0)),
                "natural：往下跳三格回来时垫得上去");
        WalkableArea strict = measure(cave, new BlockPos(0, 64, 0), false);
        assertFalse(strict.reached().containsKey(new BlockPos(10, 61, 0)), "不许动土只认上下一格");
        assertTrue(strict.blocked().stream().anyMatch(blocked -> blocked.reason() == WalkableArea.Reason.DROP
                && blocked.depth() == 3), "记成往下三格的落差：" + strict.blocked());
    }

    @Test
    void 挨着岩浆不算能走_记成过不去() {
        CaveFakes.Cave cave = new CaveFakes.Cave().tunnel(new BlockPos(0, 64, -1), new BlockPos(10, 64, 1));
        cave.flood(new BlockPos(6, 63, 0), CaveWorld.Liquid.LAVA);
        WalkableArea area = measure(cave, new BlockPos(0, 64, 0), true);
        assertFalse(area.reached().containsKey(new BlockPos(5, 64, 0)), "挨着岩浆一格不算能走");
        assertTrue(area.blocked().stream().anyMatch(blocked -> blocked.reason() == WalkableArea.Reason.LAVA));
    }

    @Test
    void 水里不算能走_记成过不去() {
        CaveFakes.Cave cave = new CaveFakes.Cave().tunnel(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0));
        cave.flood(new BlockPos(6, 64, 0), CaveWorld.Liquid.WATER);
        WalkableArea area = measure(cave, new BlockPos(0, 64, 0), true);
        assertFalse(area.reached().containsKey(new BlockPos(7, 64, 0)), "水里不算能走");
        assertEquals(WalkableArea.Reason.WATER, area.blocked().getFirst().reason());
    }
}
