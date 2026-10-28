// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 航点候选：从角色脚下沿扇形射线、或没给方向时按方形螺旋往外找，取已加载、不越半径判定带的落点列，
 * 带上纯水面、要不要跨水、离原点多远这些打分要用的量，交给航点选择挑一个。只读世界的列样本，不动身体。
 */
final class WaypointCandidates {

    // 数值初值按实机观感调整：航点远近与打分射线的条数。
    static final int WAYPOINT_MAX = 80;
    static final int WAYPOINT_MIN = 16;
    static final int RAY_COUNT = 17;

    private final ExplorationWorld world;
    /** 探索原点：离它多远是打分主项，也按它看越没越判定带。 */
    private final BlockPos origin;
    private final RadiusVerdict radius;

    WaypointCandidates(ExplorationWorld world, BlockPos origin, RadiusVerdict radius) {
        this.world = world;
        this.origin = origin;
        this.radius = radius;
    }

    /** 扇形内沿射线找落点：每条射线从最远往回找第一个已加载且不越判定带的列。 */
    List<WaypointChooser.Candidate> rays(ExplorationSector sector, BlockPos from) {
        double half = sector.angleDegrees() / 2;
        List<WaypointChooser.Candidate> candidates = new ArrayList<>();
        for (int ray = 0; ray < RAY_COUNT; ray++) {
            double bearing = sector.centerBearing() - half
                    + (sector.angleDegrees() * ray / (RAY_COUNT - 1));
            for (int dist = WAYPOINT_MAX; dist >= WAYPOINT_MIN; dist -= ObservationSampling.COLUMN_STEP) {
                int x = from.getX() + (int) Math.round(Math.sin(Math.toRadians(bearing)) * dist);
                int z = from.getZ() - (int) Math.round(Math.cos(Math.toRadians(bearing)) * dist);
                ColumnSample sample = world.sampleColumn(x, z);
                if (!sample.loaded() || distanceFrom(x, z) > radius.waypointBound()) {
                    continue;
                }
                candidates.add(toCandidate(sector, new BlockPos(x, 0, z), sample, from, dist));
                break;
            }
        }
        return candidates;
    }

    /** 无方向时方形螺旋外扩：沿途截取最远 80 格的已加载路段，不越判定带。 */
    List<WaypointChooser.Candidate> spiral(ExplorationSector sector, BlockPos from) {
        List<WaypointChooser.Candidate> candidates = new ArrayList<>();
        for (int ring = ObservationSampling.COLUMN_STEP; ring <= WAYPOINT_MAX && candidates.size() < RAY_COUNT;
                ring += ObservationSampling.COLUMN_STEP) {
            for (int[] offset : squareRing(ring)) {
                int x = from.getX() + offset[0];
                int z = from.getZ() + offset[1];
                ColumnSample sample = world.sampleColumn(x, z);
                if (!sample.loaded() || distanceFrom(x, z) > radius.waypointBound()) {
                    continue;
                }
                candidates.add(toCandidate(sector, new BlockPos(x, 0, z), sample, from,
                        Math.sqrt((double) offset[0] * offset[0] + offset[1] * offset[1])));
            }
        }
        return candidates;
    }

    /** 航点候选：纯水面不做落点；要不要跨水沿直线探一眼，渡不渡由走到的跨水保证结算。 */
    private WaypointChooser.Candidate toCandidate(ExplorationSector sector, BlockPos column, ColumnSample sample,
            BlockPos from, double rayDistance) {
        double dx = column.getX() - origin.getX();
        double dz = column.getZ() - origin.getZ();
        return new WaypointChooser.Candidate(column, sample.topFluid(),
                crossesWater(from, column), Math.sqrt(dx * dx + dz * dz), rayDistance,
                sector.eccentricity(dx, dz));
    }

    /** 从出发点到落点的直线上有没有顶盖是液体的列：穿水候选整体让位，全穿水（身处岛屿）仍选。 */
    private boolean crossesWater(BlockPos from, BlockPos column) {
        int steps = (int) Math.max(1, Math.floor(Math.hypot(
                column.getX() - from.getX(), column.getZ() - from.getZ()) / (double) ObservationSampling.COLUMN_STEP));
        for (int step = 1; step < steps; step++) {
            int x = from.getX() + (column.getX() - from.getX()) * step / steps;
            int z = from.getZ() + (column.getZ() - from.getZ()) * step / steps;
            ColumnSample sample = world.sampleColumn(x, z);
            if (sample.loaded() && sample.topFluid()) {
                return true;
            }
        }
        return false;
    }

    /** 回字形一圈上的采样点，按抽样步长取。 */
    private static List<int[]> squareRing(int ring) {
        List<int[]> cells = new ArrayList<>();
        for (int edge = -ring; edge <= ring; edge += ObservationSampling.COLUMN_STEP) {
            cells.add(new int[]{edge, -ring});
            cells.add(new int[]{edge, ring});
            cells.add(new int[]{-ring, edge});
            cells.add(new int[]{ring, edge});
        }
        return cells;
    }

    private double distanceFrom(int x, int z) {
        double dx = x - origin.getX();
        double dz = z - origin.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
