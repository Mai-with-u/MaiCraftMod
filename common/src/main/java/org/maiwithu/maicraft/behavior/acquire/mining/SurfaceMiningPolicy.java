// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 地表取材的挑点与成批规矩：避开营地平地、优先削坡面与已有坑壁的露头面、平地成批时先横向揭表层再往下挖。
 *
 * <p>不在床、箱子、工作台旁边的平地往下抠洞；视野里只要有侧面露头的山坡、土包或坑壁，就先去削露头面；
 * 实在只能在大平原取土取沙时，先在表层平铺着挖（只低一格，走一步就迈上来），表层不够数才往下一层挖。
 */
public final class SurfaceMiningPolicy {

    /** 侧面露头的坡面或坑壁在这个距离（格）以内时，排在脚下纯平地顶面候选之前。 */
    static final double EXPOSED_PREFER_RADIUS = 32.0;

    private SurfaceMiningPolicy() {}

    /**
     * 筛掉营地范围内只有顶面露出的平地方块：砍树（树干在地面以上）和削营地边凸出的侧面露头块不受影响，
     * 避免在营地地面就地往下掏出掉人的坑。
     */
    public static List<MinableSpot> outsideCampFlatGround(List<MinableSpot> spots, String dimension,
            Campground campground) {
        if (campground == null || spots.isEmpty()) return spots;
        return spots.stream()
                .filter(spot -> isLog(spot.blockType()) || spot.sideExposed()
                        || !campground.covers(new WorldPosition(
                                spot.pos().getX(), spot.pos().getY(), spot.pos().getZ(), dimension)))
                .toList();
    }

    /**
     * 给候选排序：非原木方块里，在 {@link #EXPOSED_PREFER_RADIUS} 内侧面已经露头（坡面、土包、坑壁、洞壁）的排前面，
     * 只有顶面露出的平地方块排后面；同组内部按离角色由近及远排。
     */
    public static List<MinableSpot> prioritizeExposed(List<MinableSpot> spots, WorldPosition characterAt) {
        if (spots.size() <= 1 || characterAt == null) return spots;
        return spots.stream()
                .sorted(Comparator.<MinableSpot>comparingInt(spot -> exposureRank(spot, characterAt))
                        .thenComparingDouble(spot -> distSqr(spot.pos(), characterAt)))
                .toList();
    }

    /**
     * 从排在最前的一格起连出一批相邻同种方块：原木只沿树干竖向连；地形与矿石先连同层或更高处以及侧面露头的相邻格
     * （削平土包、横向揭表层），同层连完还不够件数时才往下一层挖，减少在平地掏出深竖坑。
     */
    public static List<MinableSpot> batchOf(List<MinableSpot> spots, int count) {
        List<MinableSpot> batch = new ArrayList<>();
        if (spots.isEmpty() || count <= 0) return batch;
        MinableSpot seed = spots.getFirst();
        boolean upright = isLog(seed.blockType());
        Deque<MinableSpot> preferred = new ArrayDeque<>(List.of(seed));
        Deque<MinableSpot> deeper = new ArrayDeque<>();
        Set<BlockPos> taken = new HashSet<>(Set.of(seed.pos()));
        while ((!preferred.isEmpty() || !deeper.isEmpty()) && batch.size() < count) {
            MinableSpot current = !preferred.isEmpty() ? preferred.removeFirst() : deeper.removeFirst();
            batch.add(current);
            for (MinableSpot other : spots) {
                if (taken.contains(other.pos()) || !other.blockType().equals(seed.blockType())) continue;
                int dx = Math.abs(other.pos().getX() - current.pos().getX());
                int dy = Math.abs(other.pos().getY() - current.pos().getY());
                int dz = Math.abs(other.pos().getZ() - current.pos().getZ());
                boolean adjacent = upright ? dx == 0 && dz == 0 && dy == 1 : dx <= 1 && dy <= 1 && dz <= 1;
                if (!adjacent || !taken.add(other.pos())) continue;
                // 原木照树干走；其余方块同层、更高处或本身侧面露头的先挖，往脚下深挖的排在后面垫底。
                if (upright || other.pos().getY() >= seed.pos().getY() || other.sideExposed()) {
                    preferred.addLast(other);
                } else {
                    deeper.addLast(other);
                }
            }
        }
        return batch;
    }

    /** 原木、菌柄这类：树干竖着长在地面以上，砍掉不在地面留坑，成批只沿竖向连。 */
    public static boolean isLog(String blockType) {
        return blockType.endsWith("_log") || blockType.endsWith("_stem")
                || blockType.endsWith("_wood") || blockType.endsWith("_hyphae");
    }

    private static int exposureRank(MinableSpot spot, WorldPosition characterAt) {
        if (isLog(spot.blockType())) return 0;
        if (spot.sideExposed() && distSqr(spot.pos(), characterAt) <= EXPOSED_PREFER_RADIUS * EXPOSED_PREFER_RADIUS) {
            return 0;
        }
        return 1;
    }

    private static double distSqr(BlockPos pos, WorldPosition here) {
        double dx = pos.getX() - here.x();
        double dy = pos.getY() - here.y();
        double dz = pos.getZ() - here.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
