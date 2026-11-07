// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * 被困在自己家里时拆哪一格墙：朝外水平拆脚那层或头那层；拆了会带掉东西的最后才拆，再挑拆得少的，玻璃这类掉不回原物的最后才拆；
 * 不能拆的墙（床、家具、记住的区域里的）换一面；四面都不能拆就没有出路。地板、屋顶都在外壳里，寻路一格都不许挖。
 */
class HomeBreachTest {

    /** 平地上一间关死的小屋：屋里 3×3（x、z 从 0 到 2），地板在 63 层，墙从 64 到 66 层，屋顶在 66 层。 */
    private static final class Hut implements TrapCheck.Cells {
        final Set<BlockPos> walls = new HashSet<>();
        final Set<BlockPos> opened = new HashSet<>();

        Hut() {
            for (int x = -1; x <= 3; x++) {
                for (int z = -1; z <= 3; z++) {
                    boolean edge = x == -1 || x == 3 || z == -1 || z == 3;
                    for (int y = 64; y <= 66; y++) {
                        if (edge || y == 66) walls.add(new BlockPos(x, y, z));
                    }
                }
            }
        }

        boolean solid(int x, int y, int z) {
            BlockPos at = new BlockPos(x, y, z);
            if (opened.contains(at)) return false;
            return y <= 63 || walls.contains(at);
        }

        @Override public boolean open(int x, int y, int z) {
            return !solid(x, y, z);
        }

        @Override public boolean floor(int x, int y, int z) {
            return solid(x, y, z);
        }

        @Override public boolean water(int x, int y, int z) {
            return false;
        }

        @Override public boolean sky(int x, int y, int z) {
            return y > 66 || y > 63 && (x < -1 || x > 3 || z < -1 || z > 3);
        }

        @Override public boolean loaded(int x, int z) {
            return true;
        }
    }

    private static final BlockPos FEET = new BlockPos(1, 64, 1);

    @Test
    void 关死的小屋_朝外拆开脚那层和头那层两格出去_地板屋顶都在外壳里() {
        Hut hut = new Hut();
        HomeBreach breach = HomeBreach.find(hut, FEET, 3, cell -> true, cell -> false, cell -> false).orElseThrow();
        assertEquals(2, breach.cells().size(), breach.toString());
        BlockPos lower = breach.cells().get(0);
        assertEquals(64, lower.getY(), "先拆脚那层");
        assertEquals(lower.above(), breach.cells().get(1), "再拆头那层，同一列");
        assertTrue(hut.walls.contains(lower), "拆的是墙");
        assertEquals(lower, breach.stand().relative(breach.outward()), "从站的那一格朝外拆");
        assertTrue(breach.shell().contains(new BlockPos(1, 63, 1).asLong()), "地板不许挖");
        assertTrue(breach.shell().contains(new BlockPos(1, 66, 1).asLong()), "屋顶不许挖");
        assertFalse(breach.shell().contains(lower.asLong()), "要拆的格不在外壳里");
    }

    @Test
    void 头那层开着窗口_只拆脚那层一格() {
        Hut hut = new Hut();
        hut.opened.add(new BlockPos(3, 65, 1));
        HomeBreach breach = HomeBreach.find(hut, FEET, 3, cell -> true, cell -> false, cell -> false).orElseThrow();
        assertEquals(List.of(new BlockPos(3, 64, 1)), breach.cells());
        assertEquals(Direction.EAST, breach.outward());
    }

    @Test
    void 东墙边摆着床和家具不能拆_换一面墙() {
        Hut hut = new Hut();
        HomeBreach breach = HomeBreach.find(hut, FEET, 3, cell -> cell.getX() != 3, cell -> false, cell -> false)
                .orElseThrow();
        assertTrue(breach.cells().stream().noneMatch(cell -> cell.getX() == 3), breach.toString());
    }

    @Test
    void 玻璃墙最后才拆_有木墙先拆木墙() {
        Hut hut = new Hut();
        // 只有西墙是木板，其余三面是玻璃。
        HomeBreach breach = HomeBreach.find(hut, FEET, 3, cell -> true, cell -> cell.getX() != -1, cell -> false).orElseThrow();
        assertTrue(breach.cells().stream().allMatch(cell -> cell.getX() == -1), breach.toString());
    }

    @Test
    void 墙上挂着火把告示牌_上面压着沙子的墙最后才拆_哪怕要多拆一格() {
        Hut hut = new Hut();
        // 西墙头那层开着窗口，只拆脚那层一格就出得去，可那一格上挂着火把；别的墙都要拆两格。
        hut.opened.add(new BlockPos(-1, 65, 1));
        HomeBreach breach = HomeBreach.find(hut, FEET, 3, cell -> true, cell -> false,
                cell -> cell.equals(new BlockPos(-1, 64, 1))).orElseThrow();
        assertEquals(2, breach.cells().size(), "宁可多拆一格不带掉东西的：" + breach);
        assertTrue(breach.cells().stream().noneMatch(cell -> cell.equals(new BlockPos(-1, 64, 1))), breach.toString());
    }

    @Test
    void 每面墙都挂着东西_照样拆一面出去() {
        HomeBreach breach = HomeBreach.find(new Hut(), FEET, 3, cell -> true, cell -> false, cell -> true).orElseThrow();
        assertEquals(2, breach.cells().size(), breach.toString());
    }

    @Test
    void 交出屋里那一片_拆开的格和屋外都不在里面() {
        HomeBreach breach = HomeBreach.find(new Hut(), FEET, 3, cell -> true, cell -> false, cell -> false).orElseThrow();
        assertTrue(breach.pocket().contains(FEET.asLong()), "脚下这一格在屋里");
        assertTrue(breach.pocket().contains(breach.stand().asLong()), "迈出去的那一格在屋里");
        assertFalse(breach.pocket().contains(breach.cells().get(0).asLong()), "拆开的格不在屋里");
        assertFalse(breach.pocket().contains(breach.cells().get(0).relative(breach.outward()).asLong()), "墙外那一格不在屋里");
    }

    @Test
    void 四面都不能拆_没有出路() {
        assertEquals(Optional.empty(), HomeBreach.find(new Hut(), FEET, 3, cell -> false, cell -> false, cell -> false));
    }

    @Test
    void 没被困住_不找墙() {
        Hut hut = new Hut();
        hut.opened.add(new BlockPos(3, 64, 1));
        hut.opened.add(new BlockPos(3, 65, 1));
        assertEquals(Optional.empty(), HomeBreach.find(hut, FEET, 3, cell -> true, cell -> false, cell -> false));
    }
}
