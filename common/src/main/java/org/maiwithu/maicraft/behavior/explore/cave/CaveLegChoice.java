// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;

/**
 * 挑下一段：在量到的地方里挑眼睛看得见的新地方，走到看得见的最远处；别的方向上的新地方记成岔路。
 *
 * <p>只看眼前看得见的东西：多远、新不新、顺不顺路。不看拐角后面还有多长、是不是死胡同、哪边有矿——
 * 量路知道拐角后面的形状，但下一段只挑看得见的格，死胡同走进去看见了再回头，和玩家一样。
 * 纯判断：量到的格、走过没有、看不看得见都由调用方给。
 */
public final class CaveLegChoice {

    /** 离走过的地方这么远（格）才算新地方。 */
    static final int FRESH = 6;
    /** 走不到的候选这么近（格）的不再挑。 */
    static final int TRIED_RADIUS = 8;
    /** 一段至少走多远（格）：脚边那几格不值得停下来再走一段。 */
    static final int MIN_LEG = 4;
    /** 每个方向从远到近最多看几格视线：远的看见了就够了，不对几千格都打一遍视线。 */
    static final int SIGHT_CHECKS_PER_DIRECTION = 8;
    /** 顺着上一段方向走的加分。 */
    static final double HEADING_WEIGHT = 6;
    /** 偏向下令时给的方位的加分。 */
    static final double BIAS_WEIGHT = 4;
    /** 离洞口更远（往深处走）的加分：每远一格加这么多。 */
    static final double DEPTH_WEIGHT = 0.1;

    private CaveLegChoice() {}

    /**
     * 这一段的处境。
     *
     * @param feet        角色脚下
     * @param entrance    洞口（就地开始时是出发点）：往深处走、不越界都按它量
     * @param maxDistance 离洞口直线最远走到几格
     * @param heading     上一段走的方位（度，0 朝北顺时针）；刚开始为 null
     * @param bias        下令时给的方位（度）；没给为 null
     */
    public record Situation(BlockPos feet, BlockPos entrance, int maxDistance, Double heading, Double bias) {}

    /** 一条没走的岔路：在哪、在角色哪个方位。 */
    public record Branch(BlockPos at, String compass) {

        public Branch {
            at = at.immutable();
        }
    }

    /**
     * 挑的结果。
     *
     * @param next     下一段走到哪一格；眼前看不见新地方为 null
     * @param branches 别的方向上看得见的新地方，每个方位一条（最远的那格）
     */
    public record Choice(BlockPos next, List<Branch> branches) {}

    /**
     * 挑下一段。
     *
     * @param reached 量到的能站的格
     * @param walked  这一格离走过的地方近不近（6 格以内算走过）
     * @param visible 从眼睛看不看得见这一格
     * @param tried   走过去没走到的格：它们附近的不再挑
     */
    public static Choice choose(Situation situation, Collection<BlockPos> reached, Predicate<BlockPos> walked,
            Predicate<BlockPos> visible, List<BlockPos> tried) {
        Map<String, List<BlockPos>> byDirection = new LinkedHashMap<>();
        for (BlockPos cell : reached) {
            if (!fresh(situation, cell, walked, tried)) continue;
            byDirection.computeIfAbsent(compassOf(situation.feet(), cell), key -> new ArrayList<>()).add(cell);
        }
        // 每个方位从远到近看几格视线：看得见的最远那几格才是这个方向上"能看见的地方"。
        Map<String, List<BlockPos>> seen = new LinkedHashMap<>();
        for (Map.Entry<String, List<BlockPos>> direction : byDirection.entrySet()) {
            List<BlockPos> cells = direction.getValue();
            cells.sort(Comparator.comparingDouble((BlockPos cell) -> cell.distSqr(situation.feet())).reversed());
            List<BlockPos> visibleCells = new ArrayList<>();
            for (int i = 0; i < Math.min(SIGHT_CHECKS_PER_DIRECTION, cells.size()); i++) {
                if (visible.test(cells.get(i))) visibleCells.add(cells.get(i));
            }
            if (!visibleCells.isEmpty()) seen.put(direction.getKey(), visibleCells);
        }
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (List<BlockPos> cells : seen.values()) {
            for (BlockPos cell : cells) {
                double score = score(situation, cell);
                if (score > bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
        }
        if (best == null) {
            return new Choice(null, List.of());
        }
        String chosenDirection = compassOf(situation.feet(), best);
        List<Branch> branches = new ArrayList<>();
        for (Map.Entry<String, List<BlockPos>> direction : seen.entrySet()) {
            if (direction.getKey().equals(chosenDirection)) continue;
            branches.add(new Branch(direction.getValue().getFirst(), direction.getKey()));
        }
        return new Choice(best.immutable(), branches);
    }

    // 新地方：不在脚边、离走过的地方够远、不挨着走不到的候选、没越过离洞口的界。
    private static boolean fresh(Situation situation, BlockPos cell, Predicate<BlockPos> walked, List<BlockPos> tried) {
        if (cell.distSqr(situation.feet()) < MIN_LEG * MIN_LEG) return false;
        if (horizontal(situation.entrance(), cell) > situation.maxDistance()) return false;
        for (BlockPos failed : tried) {
            if (failed.distSqr(cell) <= TRIED_RADIUS * TRIED_RADIUS) return false;
        }
        return !walked.test(cell);
    }

    // 打分：越远越好，顺着上一段的方向、偏向下令的方位加分，离洞口更远的略加分。
    private static double score(Situation situation, BlockPos cell) {
        double score = Math.sqrt(cell.distSqr(situation.feet()));
        double bearing = bearingOf(situation.feet(), cell);
        if (situation.heading() != null) {
            score += HEADING_WEIGHT * Math.cos(Math.toRadians(bearing - situation.heading()));
        }
        if (situation.bias() != null) {
            score += BIAS_WEIGHT * Math.cos(Math.toRadians(bearing - situation.bias()));
        }
        score += DEPTH_WEIGHT * (horizontal(situation.entrance(), cell) - horizontal(situation.entrance(), situation.feet()));
        return score;
    }

    /** 从 from 到 to 的方位（度，0 朝北顺时针）。 */
    public static double bearingOf(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    private static String compassOf(BlockPos from, BlockPos to) {
        return DirectionWords.compassOf(to.getX() - from.getX(), to.getZ() - from.getZ());
    }

    private static double horizontal(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
