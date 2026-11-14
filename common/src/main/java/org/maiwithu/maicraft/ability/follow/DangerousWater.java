// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 跟随时的危险水域判断与近期水中遇害记忆：纯判断，不直接碰客户端世界。
 *
 * <p>水里游泳移速慢、视线晃，一旦水里有溺尸或守卫者这类敌对生物，跟着被追的对象一头扎进水里极易被连续刺死；
 * 刚在同一片水里死过人（角色自己或被跟的对象），重生回来几分钟内那只水怪往往还在原地守着。
 * 遇到这两种水域时，角色留在干燥岸边看着对方等（自己已经泡在水里就先退上最近的岸），等对方上岸或威胁解除再接着跟。
 */
final class DangerousWater {

    /** 角色或被跟对象身边多远以内的水中敌对生物算眼前的水域威胁（格）。 */
    static final double SCAN_RADIUS = 16.0;
    /** 水中敌对生物离角色到目标连线这么近以内，算横在去路中间（格）。 */
    static final double LANE_RADIUS = 8.0;
    /** 刚死过人的那片水周围多大范围算危险水域（格）。 */
    static final double CASUALTY_RADIUS = 20.0;
    /** 同一片水死过人之后警戒多久（刻，3 分钟）：够重生跑回来避开上一次送命的水域。 */
    static final long CASUALTY_MEMORY_TICKS = 20L * 180;
    /** 自己已经泡在危险水里时，往四周多远以内找能站的干地退上去（格）。 */
    static final int SHORE_SCAN_RADIUS = 12;
    /** 挑岸边等待格时，尽量离危险中心留出这么多格余量（格）。 */
    private static final double SAFE_MARGIN = 5.0;
    private static final int MAX_MEMORIES = 8;

    /** 水里一只活着的敌对生物。 */
    record WaterThreat(String type, Vec3 position) {
        WaterThreat {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(position, "position");
        }
    }

    /** 当前拦住跟随的一处危险水域：危险中心、人话原因与角色此刻是不是正泡在水里。 */
    record Hazard(Vec3 center, String reason, boolean meInWater) {
        Hazard {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private record Casualty(String dimension, Vec3 position, long tick) {}

    private final List<Casualty> casualties = new ArrayList<>();

    /** 记下一次水中死亡（角色自己或被跟的对象死在水里），供重生回来时避开同一片水域。 */
    void recordCasualty(String dimension, Vec3 position, long gameTick) {
        if (dimension == null || position == null) return;
        prune(gameTick);
        // 同一片水几秒内连死几次合成最新一条，不把旧的其他水域挤掉。
        casualties.removeIf(entry -> entry.dimension.equals(dimension)
                && horizontalDistance(entry.position, position) <= 8.0);
        if (casualties.size() >= MAX_MEMORIES) {
            casualties.removeFirst();
        }
        casualties.add(new Casualty(dimension, position, gameTick));
    }

    /**
     * 判断眼下跟向目标会不会踏进危险水域：先看水里活着的水怪，再看近三分钟刚死过人的水域。
     * 角色与目标都已经在干地上、且连线中间没有水怪拦路时不拦，照常从陆上跟。
     */
    Optional<Hazard> evaluate(String dimension, long gameTick, Vec3 me, boolean meInWater,
                              Vec3 him, boolean himInWater, List<WaterThreat> waterThreats) {
        prune(gameTick);
        WaterThreat nearestThreat = blockingThreat(me, meInWater, him, himInWater, waterThreats);
        if (nearestThreat != null) {
            return Optional.of(new Hazard(nearestThreat.position(),
                    "水里有敌对生物 " + nearestThreat.type(), meInWater));
        }
        if (!meInWater && !himInWater) {
            return Optional.empty();
        }
        for (int i = casualties.size() - 1; i >= 0; i--) {
            Casualty casualty = casualties.get(i);
            if (!casualty.dimension.equals(dimension)) continue;
            boolean himInside = himInWater && horizontalDistance(him, casualty.position) <= CASUALTY_RADIUS;
            boolean meInside = meInWater && horizontalDistance(me, casualty.position) <= CASUALTY_RADIUS;
            if (himInside || meInside) {
                return Optional.of(new Hazard(casualty.position, "刚在这片水里遇害过", meInWater));
            }
        }
        return Optional.empty();
    }

    /**
     * 自己已经泡在危险水里时，在四周挑一格干燥可站的岸边脚位退上去：
     * 优先挑离危险中心留够余量、且离角色最近的干地；都很近时挑离危险中心更远的那一格。
     */
    static Optional<BlockPos> chooseShoreSpot(Vec3 me, Vec3 him, Hazard hazard, Predicate<BlockPos> dryStandable) {
        int mx = Mth.floor(me.x);
        int my = Mth.floor(me.y);
        int mz = Mth.floor(me.z);
        BlockPos bestSafe = null;
        double bestSafeScore = Double.MAX_VALUE;
        BlockPos bestFallback = null;
        double bestFallbackDangerDist = -1;
        for (int dx = -SHORE_SCAN_RADIUS; dx <= SHORE_SCAN_RADIUS; dx++) {
            for (int dz = -SHORE_SCAN_RADIUS; dz <= SHORE_SCAN_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos cell = new BlockPos(mx + dx, my + dy, mz + dz);
                    if (!dryStandable.test(cell)) continue;
                    Vec3 center = Vec3.atBottomCenterOf(cell);
                    double dangerDist = horizontalDistance(center, hazard.center());
                    double walkDist = horizontalDistance(center, me) + Math.abs(dy) * 0.5;
                    double towardHim = him == null ? 0 : horizontalDistance(center, him) * 0.05;
                    if (dangerDist >= SAFE_MARGIN) {
                        double score = walkDist + towardHim;
                        if (score < bestSafeScore) {
                            bestSafeScore = score;
                            bestSafe = cell;
                        }
                    } else if (dangerDist > bestFallbackDangerDist) {
                        bestFallbackDangerDist = dangerDist;
                        bestFallback = cell;
                    }
                }
            }
        }
        return Optional.ofNullable(bestSafe != null ? bestSafe : bestFallback);
    }

    private static WaterThreat blockingThreat(Vec3 me, boolean meInWater, Vec3 him, boolean himInWater,
                                              List<WaterThreat> threats) {
        WaterThreat chosen = null;
        double chosenDist = Double.MAX_VALUE;
        for (WaterThreat threat : threats) {
            double toMe = horizontalDistance(me, threat.position());
            double toHim = horizontalDistance(him, threat.position());
            boolean nearSwimmer = (meInWater && toMe <= SCAN_RADIUS) || (himInWater && toHim <= SCAN_RADIUS);
            boolean onPath = blocksSegment(me, him, threat.position(), LANE_RADIUS);
            if (!nearSwimmer && !onPath) continue;
            double dist = Math.min(toMe, toHim);
            if (dist < chosenDist) {
                chosenDist = dist;
                chosen = threat;
            }
        }
        return chosen;
    }

    // 威胁点是不是落在从角色走向目标的水平通道中间：投影在线段内部且垂直偏差不超过半径。
    private static boolean blocksSegment(Vec3 from, Vec3 to, Vec3 point, double radius) {
        double abx = to.x - from.x;
        double abz = to.z - from.z;
        double lenSq = abx * abx + abz * abz;
        if (lenSq < 1.0E-4) return false;
        double t = ((point.x - from.x) * abx + (point.z - from.z) * abz) / lenSq;
        if (t <= 0.05 || t >= 0.95) return false;
        double projX = from.x + t * abx;
        double projZ = from.z + t * abz;
        return Math.hypot(point.x - projX, point.z - projZ) <= radius;
    }

    private void prune(long gameTick) {
        Iterator<Casualty> iterator = casualties.iterator();
        while (iterator.hasNext()) {
            Casualty entry = iterator.next();
            if (gameTick - entry.tick >= CASUALTY_MEMORY_TICKS) {
                iterator.remove();
            }
        }
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }
}
