// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 地形特征聚类：从俯视网格里把同一种符号连成的片找出来，当作一处地形特征。
 *
 * <p>水体连成片就是河流或湖，树连成片就是树林，落差连在视野边缘就是悬崖口。
 * 只在俯视网格覆盖的近处找：更大的地物（村庄、建筑、结构）要靠更大的扫描，
 * 由接线轨以特征登记的方式补进来，聚类只负责把眼前的网格读成几处特征。
 * 两格以下的孤格不报——一格水洼、一棵孤树不值得占一个观察编号。
 */
public final class FeatureSpotter {

    /** 网格里可聚类的符号与它对应的地形特征名。 */
    private FeatureSpotter() {}

    /**
     * 一处聚出来的特征：大致中心、特征名与格数。
     *
     * <p>{@code resources} 是标注好的产地证据：这种特征成片出现时顺带证明这里大概产什么
     * （树林证明有木头）；不是产地证据的特征为空列表。证据与产物的对应关系只有标注表这一份，
     * 整理观察的一方只看标注结果，不自己认特征种类。
     */
    public record Marked(WorldPosition center, String kind, int cells, List<String> resources) {
        public Marked {
            resources = List.copyOf(resources);
        }
    }

    /** 从一张俯视网格里聚出地形特征；格子太少或没有成片的就什么都不报。 */
    public static List<Marked> spot(OverheadGrid.View view) {
        char[][] cells = view.cells();
        int size = cells.length;
        boolean[][] taken = new boolean[size][size];
        List<Marked> found = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                Kind kind = kindOf(cells[row][col]);
                if (kind == null || taken[row][col]) {
                    continue;
                }
                // 同一种符号的相连格聚成一片：先广搜圈出范围，再取平均求大致中心。
                List<int[]> cluster = new ArrayList<>();
                collect(cells, taken, row, col, cells[row][col], cluster);
                if (cluster.size() < 2) {
                    continue;
                }
                long sumRow = 0;
                long sumCol = 0;
                for (int[] cell : cluster) {
                    sumRow += cell[0];
                    sumCol += cell[1];
                }
                int radius = view.radius();
                BlockPos center = view.center();
                int cx = center.getX() + (int) Math.round((double) sumCol / cluster.size()) - radius;
                int cz = center.getZ() + (int) Math.round((double) sumRow / cluster.size()) - radius;
                found.add(new Marked(WorldPosition.here(cx, center.getY(), cz),
                        kind.name(), cluster.size(), kind.resources()));
            }
        }
        return found;
    }

    // 广搜同一符号的相连格；对角相接不算连——隔一角的两片水是两处水。
    private static void collect(char[][] cells, boolean[][] taken, int row, int col,
            char symbol, List<int[]> cluster) {
        int size = cells.length;
        List<int[]> pending = new ArrayList<>();
        pending.add(new int[]{row, col});
        taken[row][col] = true;
        while (!pending.isEmpty()) {
            int[] cell = pending.remove(pending.size() - 1);
            cluster.add(cell);
            int[][] neighbors = {
                    {cell[0] - 1, cell[1]}, {cell[0] + 1, cell[1]},
                    {cell[0], cell[1] - 1}, {cell[0], cell[1] + 1}};
            for (int[] next : neighbors) {
                int nr = next[0];
                int nc = next[1];
                if (nr < 0 || nr >= size || nc < 0 || nc >= size
                        || taken[nr][nc] || cells[nr][nc] != symbol) {
                    continue;
                }
                taken[nr][nc] = true;
                pending.add(next);
            }
        }
    }

    /** 一种可聚类的符号对应的特征：特征名，以及成片出现时它证明的产地产物。 */
    private record Kind(String name, List<String> resources) {}

    /** 树林这条产地证据在记忆里记的词：来源侧认它挑"哪里见过木头"，这个词只有这一处定义。 */
    public static final String WOOD_CLUE = "木头";

    // 网格符号到特征的对照：只有值得报成一处特征的三种。
    // 产地证据也在这里一并声明（树林证明有木头）：新添产地知识只改这张表，别处不再认种类字符串。
    private static Kind kindOf(char cell) {
        return switch (cell) {
            case OverheadGrid.WATER -> new Kind("水体", List.of());
            case OverheadGrid.TREE -> new Kind("树林", List.of(WOOD_CLUE));
            case OverheadGrid.DROP -> new Kind("落差", List.of());
            default -> null;
        };
    }
}
