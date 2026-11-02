// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 洞口判断：山坡上往里连着暗处的洞报洞口、浅凹不报、墙后看不见的不报、往下的洞报往下、相邻开口合一处；
 * 长而深的沟报峡谷、短坑不报、看不见不报。假世界是一块体素，天空光按游戏的规矩推出来：露天 15，往遮挡下面每格少 1。
 */
class CaveOpeningsTest {

    /** 体素假世界：地面实心到 63 层（地表 64），可以堆山、挖洞、挖沟；看得见默认都看得见，登记的格看不见。 */
    private static final class Voxels implements CaveOpenings.Ground {
        static final int MIN = -48;
        static final int MAX = 48;
        static final int BOTTOM = 20;
        static final int TOP = 100;
        private final boolean[][][] solid = new boolean[MAX - MIN + 1][TOP - BOTTOM + 1][MAX - MIN + 1];
        private int[][][] light;
        final Set<BlockPos> hidden = new HashSet<>();

        Voxels() {
            fill(MIN, BOTTOM, MIN, MAX, 63, MAX, true);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, boolean value) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        solid[x - MIN][y - BOTTOM][z - MIN] = value;
                    }
                }
            }
            light = null;
        }

        boolean solidAt(int x, int y, int z) {
            if (x < MIN || x > MAX || z < MIN || z > MAX || y < BOTTOM) return true;
            if (y > TOP) return false;
            return solid[x - MIN][y - BOTTOM][z - MIN];
        }

        @Override public boolean loaded(int x, int z) {
            return x >= MIN && x <= MAX && z >= MIN && z <= MAX;
        }

        @Override public int surfaceY(int x, int z) {
            for (int y = TOP; y >= BOTTOM; y--) {
                if (solidAt(x, y, z)) return y + 1;
            }
            return BOTTOM;
        }

        @Override public boolean roomy(int x, int y, int z) {
            return !solidAt(x, y, z);
        }

        @Override public boolean standable(int x, int y, int z) {
            return roomy(x, y, z) && roomy(x, y + 1, z) && solidAt(x, y - 1, z);
        }

        @Override public int skyLight(int x, int y, int z) {
            if (light == null) light = propagate();
            if (!loaded(x, z) || y < BOTTOM || y > TOP) return 15;
            return light[x - MIN][y - BOTTOM][z - MIN];
        }

        @Override public boolean visible(int x, int y, int z) {
            return !hidden.contains(new BlockPos(x, y, z));
        }

        // 天空光：每列地表以上的空气是 15，往没见天的空气里每走一格少 1。
        private int[][][] propagate() {
            int[][][] values = new int[MAX - MIN + 1][TOP - BOTTOM + 1][MAX - MIN + 1];
            Deque<int[]> pending = new ArrayDeque<>();
            for (int x = MIN; x <= MAX; x++) {
                for (int z = MIN; z <= MAX; z++) {
                    for (int y = surfaceY(x, z); y <= TOP; y++) {
                        values[x - MIN][y - BOTTOM][z - MIN] = 15;
                        pending.add(new int[] {x, y, z});
                    }
                }
            }
            int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!pending.isEmpty()) {
                int[] cell = pending.poll();
                int value = values[cell[0] - MIN][cell[1] - BOTTOM][cell[2] - MIN] - 1;
                if (value <= 0) continue;
                for (int[] step : steps) {
                    int x = cell[0] + step[0];
                    int y = cell[1] + step[1];
                    int z = cell[2] + step[2];
                    if (!loaded(x, z) || y < BOTTOM || y > TOP || solidAt(x, y, z)) continue;
                    if (values[x - MIN][y - BOTTOM][z - MIN] >= value) continue;
                    values[x - MIN][y - BOTTOM][z - MIN] = value;
                    pending.add(new int[] {x, y, z});
                }
            }
            return values;
        }
    }

    private final Voxels world = new Voxels();

    private List<CaveOpenings.Opening> sweep() {
        CaveOpenings.Sweep sweep = new CaveOpenings.Sweep(world, new BlockPos(0, 64, 0));
        for (int i = 0; i < 1000 && !sweep.tick(); i++) {
            // 分刻推进，一刻一小段，直到扫完一圈。
        }
        return sweep.openings();
    }

    // 东边一座山（x 10..30），山坡朝西；从山坡挖一条往里的洞。
    private void hillWithTunnel(int depth) {
        world.fill(10, 64, -10, 30, 80, 10, true);
        world.fill(10, 64, 0, 10 + depth - 1, 65, 1, false);
    }

    @Test
    void tunnelIntoHillsideIsCaveMouthAtItsStandableOpening() {
        hillWithTunnel(18);

        List<CaveOpenings.Opening> found = sweep();

        assertEquals(1, found.size(), "一处洞口");
        CaveOpenings.Opening mouth = found.get(0);
        assertEquals(CaveOpenings.Kind.CAVE_MOUTH, mouth.kind());
        assertEquals(10, mouth.at().getX(), "位置是开口那一列");
        assertEquals(64, mouth.at().getY(), "真实高度：开口能站的那一格");
        assertFalse(mouth.goesDown(), "横着进山");
    }

    @Test
    void shallowOverhangIsNotACave() {
        hillWithTunnel(3);

        assertTrue(sweep().isEmpty(), "凹进去三格，里面不够暗");
    }

    @Test
    void openingHiddenBehindAWallIsNotReported() {
        hillWithTunnel(18);
        for (int y = 64; y <= 65; y++) {
            for (int z = 0; z <= 1; z++) {
                world.hidden.add(new BlockPos(10, y, z));
            }
        }

        assertTrue(sweep().isEmpty(), "开口看不见就不报");
    }

    @Test
    void caveDroppingDownFromTheOpeningGoesDown() {
        world.fill(10, 64, -10, 30, 80, 10, true);
        // 开口进去两格就是一道竖井，往下到 40 层，再横着一条长洞。
        world.fill(10, 64, 0, 12, 65, 1, false);
        world.fill(12, 41, 0, 13, 63, 1, false);
        world.fill(12, 40, 0, 30, 41, 1, false);

        List<CaveOpenings.Opening> found = sweep();

        assertEquals(1, found.size());
        assertTrue(found.get(0).goesDown(), "暗处大多在开口以下");
    }

    @Test
    void twoOpeningsOfOneCaveMergeIntoOne() {
        hillWithTunnel(18);
        // 同一条洞在旁边 4 格又开了一个口。
        world.fill(10, 64, 4, 12, 65, 5, false);
        world.fill(12, 64, 1, 12, 65, 4, false);

        assertEquals(1, sweep().size(), "六格以内的开口是同一处");
    }

    @Test
    void longDeepTrenchIsRavineAndShortPitIsNot() {
        // 西边一道沟：3 格宽、21 格长、14 格深。
        world.fill(-30, 50, -10, -28, 63, 10, false);
        // 南边一个短坑：3 格宽、8 格长、14 格深。
        world.fill(-4, 50, 20, 3, 63, 22, false);

        List<CaveOpenings.Opening> found = sweep();

        assertEquals(1, found.size(), "只有长沟算峡谷");
        CaveOpenings.Opening ravine = found.get(0);
        assertEquals(CaveOpenings.Kind.RAVINE, ravine.kind());
        assertEquals(50, ravine.at().getY(), "位置是沟底");
        assertEquals(21, ravine.length());
        assertTrue(ravine.depth() >= 10);
        assertTrue(ravine.covers(-29, 0, 0), "沟里的列在它的范围里");
    }

    @Test
    void ravineOutOfSightIsNotReported() {
        world.fill(-30, 50, -10, -28, 63, 10, false);
        for (int x = -30; x <= -28; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = 50; y <= 63; y++) {
                    world.hidden.add(new BlockPos(x, y, z));
                }
            }
        }

        assertTrue(sweep().isEmpty(), "沟底沟里都看不见就不报");
    }
}
