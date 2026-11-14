// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * 营地地面坑识别（纯判断）：找出营地范围内深度不低于两格的地面竖坑与爆炸弹坑，并给出安全的回填格序。
 *
 * <p>苦力怕炸出的碗状弹坑边缘往往先低一格、中心低两到三格；天然斜坡则顺坡一路排到底。
 * 用局部洼地溢流高度区分"四面围合积得住水的地面坑"与"顺坡流走的斜坡、河岸"：
 * 斜坡、水坑、灌溉沟、人工楼梯井与室内地板都作为排水边界排走，只有被天然地面围住、最深处低两到六格、
 * 水平面积不超过二十八列的地面洼地才认作可顺手回填的坑。
 */
public final class CampPits {

    /** 默认在角色四周多大水平半径内看营地地面坑（格）。 */
    public static final int SCAN_RADIUS = 20;
    /** 报出与回填的最浅深度（格）：只低一格的台阶迈步或跳一下就过，不算掉进去难出来的坑。 */
    public static final int MIN_DEPTH = 2;
    /** 顺手填坑的最深上限（格）：更深的是竖井或天然裂谷，不是几块泥土顺手填平的地面坑。 */
    public static final int MAX_DEPTH = 6;
    /** 一处坑最多占几列：覆盖单格竖坑到苦力怕大弹坑，排除天然盆地与大型工程基坑。 */
    public static final int MAX_CLUSTER_COLUMNS = 28;
    /** 整处坑实心体积不超过这么多格时从底往上全部填实；更大的弹坑从坑壁往里把坑口顶层封平。 */
    static final int SOLID_FILL_BUDGET = 24;
    private static final int DRAIN_LEVEL = -100_000;
    private static final int[][] FOUR_DIRS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private CampPits() {}

    /** 一列的地面事实：要么是天然地面及顶格高度，要么是水体、设施、楼梯或未加载等排水边界。 */
    public record Column(boolean drains, int topY) {
        public static Column drain() { return new Column(true, DRAIN_LEVEL); }
        public static Column naturalGround(int topY) { return new Column(false, topY); }
    }

    /** 只读世界地面接口：生产读客户端区块与营地范围，离线测试喂网格。 */
    public interface Ground {
        boolean anyCampNearby(int centerX, int centerY, int centerZ, int radius);
        boolean inCamp(int x, int y, int z);
        boolean insideStructure(int x, int y, int z);
        Column columnAt(int x, int z, int refY);
    }

    /**
     * 认出的一处地面坑。
     *
     * @param center    坑口平齐高度处的水平中心格
     * @param rimY      四周坑口平齐的地面顶格高度（填平后人站在 rimY + 1）
     * @param depth     最深处比 rimY 低几格（>= 2）
     * @param cells     洼地占了多少列
     * @param fillCells 回填要放方块的格子，按从下往上、贴着坑壁从外往里排好
     */
    public record Pit(BlockPos center, int rimY, int depth, int cells, List<BlockPos> fillCells) {
        public Pit {
            center = Objects.requireNonNull(center, "center").immutable();
            fillCells = List.copyOf(fillCells);
        }
    }

    /**
     * 在给定中心与半径内找出地面坑；requireCamp 为真时只留坑口中心落在营地范围内的坑，
     * 为假时（调用方点名某一处坐标填坑）只要是合规的天然地面坑都给出。按离中心由近到远排序。
     */
    public static List<Pit> find(BlockPos origin, int radius, boolean requireCamp, Ground ground) {
        int r = Math.max(3, radius);
        if (requireCamp && !ground.anyCampNearby(origin.getX(), origin.getY(), origin.getZ(), r)) {
            return List.of();
        }
        int size = r * 2 + 1;
        int minX = origin.getX() - r;
        int minZ = origin.getZ() - r;
        int[][] topY = new int[size][size];
        boolean[][] drain = new boolean[size][size];
        sampleGrid(minX, minZ, origin.getY(), size, ground, topY, drain);
        int[][] rimY = computeSpillLevels(size, topY, drain);
        List<Pit> pits = extractPits(minX, minZ, size, topY, drain, rimY, requireCamp, ground);
        pits.sort(Comparator.comparingDouble(pit -> pit.center().distSqr(origin)));
        return List.copyOf(pits);
    }

    private static void sampleGrid(int minX, int minZ, int refY, int size, Ground ground,
                                   int[][] topY, boolean[][] drain) {
        for (int gx = 0; gx < size; gx++) {
            for (int gz = 0; gz < size; gz++) {
                Column col = ground.columnAt(minX + gx, minZ + gz, refY);
                drain[gx][gz] = col.drains();
                topY[gx][gz] = col.drains() ? DRAIN_LEVEL : col.topY();
            }
        }
    }

    // 从网格四边与所有排水列（水坑、楼梯井、未加载列）出发做最小溢流水位传播：顺坡能流走的列 rimY == topY。
    private static int[][] computeSpillLevels(int size, int[][] topY, boolean[][] drain) {
        int[][] rim = new int[size][size];
        boolean[][] settled = new boolean[size][size];
        PriorityQueue<int[]> queue = new PriorityQueue<>(Comparator.comparingInt(cell -> cell[2]));
        for (int gx = 0; gx < size; gx++) {
            for (int gz = 0; gz < size; gz++) {
                boolean border = gx == 0 || gz == 0 || gx == size - 1 || gz == size - 1;
                if (drain[gx][gz] || border) {
                    rim[gx][gz] = topY[gx][gz];
                    settled[gx][gz] = true;
                    queue.add(new int[] {gx, gz, rim[gx][gz]});
                } else {
                    rim[gx][gz] = Integer.MAX_VALUE;
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] d : FOUR_DIRS) {
                int nx = cur[0] + d[0];
                int nz = cur[1] + d[1];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size || settled[nx][nz]) continue;
                settled[nx][nz] = true;
                rim[nx][nz] = Math.max(topY[nx][nz], cur[2]);
                queue.add(new int[] {nx, nz, rim[nx][nz]});
            }
        }
        return rim;
    }

    private static List<Pit> extractPits(int minX, int minZ, int size, int[][] topY, boolean[][] drain,
                                         int[][] rimY, boolean requireCamp, Ground ground) {
        boolean[][] visited = new boolean[size][size];
        List<Pit> found = new ArrayList<>();
        for (int gx = 1; gx < size - 1; gx++) {
            for (int gz = 1; gz < size - 1; gz++) {
                if (visited[gx][gz] || drain[gx][gz] || rimY[gx][gz] - topY[gx][gz] < 1) continue;
                List<int[]> cluster = floodCluster(gx, gz, size, topY, drain, rimY, visited);
                Pit candidate = buildPit(minX, minZ, cluster, topY, drain, rimY, requireCamp, ground);
                if (candidate != null) found.add(candidate);
            }
        }
        return found;
    }

    private static List<int[]> floodCluster(int startX, int startZ, int size, int[][] topY, boolean[][] drain,
                                            int[][] rimY, boolean[][] visited) {
        List<int[]> cluster = new ArrayList<>();
        Deque<int[]> queue = new ArrayDeque<>();
        visited[startX][startZ] = true;
        queue.add(new int[] {startX, startZ});
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            cluster.add(cur);
            for (int[] d : FOUR_DIRS) {
                int nx = cur[0] + d[0];
                int nz = cur[1] + d[1];
                if (nx <= 0 || nz <= 0 || nx >= size - 1 || nz >= size - 1 || visited[nx][nz]) continue;
                if (drain[nx][nz] || rimY[nx][nz] - topY[nx][nz] < 1) continue;
                visited[nx][nz] = true;
                queue.add(new int[] {nx, nz});
            }
        }
        return cluster;
    }

    private static Pit buildPit(int minX, int minZ, List<int[]> cluster, int[][] topY, boolean[][] drain,
                                int[][] rimY, boolean requireCamp, Ground ground) {
        if (cluster.isEmpty() || cluster.size() > MAX_CLUSTER_COLUMNS) return null;
        int maxDepth = 0;
        int targetRim = Integer.MAX_VALUE;
        long sumX = 0;
        long sumZ = 0;
        int volume = 0;
        for (int[] col : cluster) {
            int gx = col[0];
            int gz = col[1];
            // 洼地紧邻水坑或人工楼梯等排水口时不填，免得把水塘边或地窖口误当成野坑。
            for (int[] d : FOUR_DIRS) {
                if (drain[gx + d[0]][gz + d[1]]) return null;
            }
            int wx = minX + gx;
            int wz = minZ + gz;
            if (ground.insideStructure(wx, topY[gx][gz] + 1, wz)) return null;
            int depth = rimY[gx][gz] - topY[gx][gz];
            maxDepth = Math.max(maxDepth, depth);
            targetRim = Math.min(targetRim, rimY[gx][gz]);
            volume += depth;
            sumX += wx;
            sumZ += wz;
        }
        if (maxDepth < MIN_DEPTH || maxDepth > MAX_DEPTH) return null;
        int cx = (int) Math.round((double) sumX / cluster.size());
        int cz = (int) Math.round((double) sumZ / cluster.size());
        if (requireCamp && !ground.inCamp(cx, targetRim, cz)) return null;
        List<BlockPos> fillCells = orderFillCells(minX, minZ, cluster, topY, targetRim, volume <= SOLID_FILL_BUDGET);
        if (fillCells.isEmpty()) return null;
        return new Pit(new BlockPos(cx, targetRim, cz), targetRim, maxDepth, cluster.size(), fillCells);
    }

    // 体积不大时从坑底到坑口逐层填实（每格正下方都有支撑）；大弹坑则贴着外圈坑壁往里把坑口顶层封平。
    private static List<BlockPos> orderFillCells(int minX, int minZ, List<int[]> cluster, int[][] topY,
                                                 int rimY, boolean solidFill) {
        List<int[]> ringOrder = orderFromRimInward(cluster);
        List<BlockPos> ordered = new ArrayList<>();
        if (solidFill) {
            int minTop = cluster.stream().mapToInt(c -> topY[c[0]][c[1]]).min().orElse(rimY);
            for (int y = minTop + 1; y <= rimY; y++) {
                for (int[] col : ringOrder) {
                    if (y > topY[col[0]][col[1]]) {
                        ordered.add(new BlockPos(minX + col[0], y, minZ + col[1]));
                    }
                }
            }
        } else {
            for (int[] col : ringOrder) {
                if (rimY > topY[col[0]][col[1]]) {
                    ordered.add(new BlockPos(minX + col[0], rimY, minZ + col[1]));
                }
            }
        }
        return ordered;
    }

    // 广搜从贴着坑壁外圈的列往坑心排，保证封顶时每一格至少有一面贴着已有实地。
    private static List<int[]> orderFromRimInward(List<int[]> cluster) {
        Set<Long> inCluster = new HashSet<>();
        for (int[] col : cluster) inCluster.add(pack(col[0], col[1]));
        List<int[]> ordered = new ArrayList<>(cluster.size());
        Set<Long> queued = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int[] col : cluster) {
            boolean touchesRim = false;
            for (int[] d : FOUR_DIRS) {
                if (!inCluster.contains(pack(col[0] + d[0], col[1] + d[1]))) {
                    touchesRim = true;
                    break;
                }
            }
            if (touchesRim) {
                queued.add(pack(col[0], col[1]));
                queue.add(col);
            }
        }
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            ordered.add(cur);
            for (int[] d : FOUR_DIRS) {
                int nx = cur[0] + d[0];
                int nz = cur[1] + d[1];
                long key = pack(nx, nz);
                if (inCluster.contains(key) && queued.add(key)) {
                    queue.add(new int[] {nx, nz});
                }
            }
        }
        return ordered;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
