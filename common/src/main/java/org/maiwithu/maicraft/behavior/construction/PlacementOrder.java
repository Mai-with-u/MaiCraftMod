// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;

/**
 * 砌筑挑格顺序与近距避让耐心判断（纯函数）：
 * 单层连通铺面/窄路从远离起始出口的一端起手往回退着铺，避免从中段切开连通块来回走独木桥；
 * 同层挑格对与角色当前脚格或头格相交的格施加显著惩罚，排到同层其他可达且不碰撞格之后；
 * 目标格距当前脚位不足 3 格的近距避让移动缩短走步超时预算，超时立即切换候选面或延后当前格。
 */
final class PlacementOrder {

    /** 站着不动点得着的距离（格）：眼睛到那一面的中心，比原版交互距离 4.5 留半格余量。 */
    static final double REACH_FROM_HERE = 4.0;
    /** 眼睛比脚底高多少（格）：站着时的视线高度。 */
    static final double EYE_HEIGHT = 1.62;
    /** 站着够得着时转头 180 度折算的距离代价（格）：顺着眼前这一侧扫完再转身，不在四格内前后交替甩头。 */
    static final double REACHABLE_TURN_WEIGHT = 3.5;
    /** 需要迈步过去时平局或近距的朝向决胜代价（格）：对称等距时顺着当前朝向挑，不舍近求远跑冤枉路。 */
    static final double WALK_TURN_TIEBREAKER = 0.4;
    /** 挑格预筛只看离人最近的这几格：找不到够得着的就回退按距离挑。 */
    static final int REACHABLE_PROBES = 8;
    /** 候选格与角色脚格或头格碰撞时的排序惩罚（格）：将其压到同层其他不碰撞格之后，消除踩格放不下去。 */
    static final double PLAYER_OCCUPIED_PENALTY = 100.0;
    /** 触发单层远端起手退着铺所需的最小四连通水平格数：零散一两格仍按就近优先。 */
    static final int MIN_CONNECTED_STRIP_CELLS = 3;
    /** 判定为近距避让移动的距离阈值（格）：目标格离当前脚位不到 3 格时采用短超时。 */
    static final double SHORT_APPROACH_DISTANCE = 3.0;
    /** 近距避让移动的单次走步超时（刻，4 秒）：超时立刻切下一候选面或延后当前格，杜绝单格卡死。 */
    static final long SHORT_APPROACH_PATIENCE_TICKS = 80L;

    private PlacementOrder() {}

    /**
     * 判断这批待砌格是否构成高度跨度为 1 的单层连通铺面/窄路（至少 3 格四连通）：
     * 高度超过 1 层或仅有零散隔开的几格时返回假，保留常规就近扫视节奏。
     */
    static boolean isSingleLayerConnected(Collection<PlannedCell> cells) {
        if (cells == null || cells.size() < MIN_CONNECTED_STRIP_CELLS) return false;
        int layerY = Integer.MIN_VALUE;
        Set<BlockPos> positions = new HashSet<>(cells.size());
        for (PlannedCell cell : cells) {
            BlockPos pos = cell.pos();
            if (layerY == Integer.MIN_VALUE) layerY = pos.getY();
            else if (pos.getY() != layerY) return false;
            positions.add(pos.immutable());
        }
        Set<BlockPos> visited = new HashSet<>(positions.size());
        for (BlockPos start : positions) {
            if (!visited.add(start)) continue;
            int component = floodHorizontal(start, positions, visited);
            if (component >= MIN_CONNECTED_STRIP_CELLS) return true;
        }
        return false;
    }

    private static int floodHorizontal(BlockPos start, Set<BlockPos> positions, Set<BlockPos> visited) {
        Deque<BlockPos> pending = new ArrayDeque<>();
        pending.add(start);
        int count = 0;
        while (!pending.isEmpty()) {
            BlockPos cur = pending.poll();
            count++;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cur.relative(side);
                if (positions.contains(next) && visited.add(next)) pending.add(next);
            }
        }
        return count;
    }

    /** 在单层候选格中选出距离起始出口 {@code retreatOrigin} 最远的端点，作为退着铺的起手锚点。 */
    static BlockPos retreatTip(Collection<PlannedCell> tier, BlockPos retreatOrigin) {
        PlannedCell farthest = null;
        double maxDistSq = -1.0;
        for (PlannedCell cell : tier) {
            double distSq = cell.pos().distSqr(retreatOrigin);
            if (farthest == null || distSq > maxDistSq + 1.0e-9
                    || (Math.abs(distSq - maxDistSq) <= 1.0e-9 && BuildOrder.LOW_TO_HIGH.compare(cell, farthest) < 0)) {
                maxDistSq = distSq;
                farthest = cell;
            }
        }
        return farthest == null ? retreatOrigin : farthest.pos().immutable();
    }

    /** 候选格（含门/床等双格方块的副格）是否与角色此刻站立的脚格或头格相交。 */
    static boolean intersectsPlayer(PlannedCell cell, BlockPos feet) {
        if (feet == null || cell == null) return false;
        BlockPos pos = cell.pos();
        if (pos.equals(feet) || pos.equals(feet.above())) return true;
        for (var extra : PlacementPrediction.generatedBy(pos, cell.state())) {
            if (extra.pos().equals(feet) || extra.pos().equals(feet.above())) return true;
        }
        return false;
    }

    /**
     * 挑一格的综合代价：眼睛到目标格中心的距离 + 视线转向代价 + 角色碰撞箱占据惩罚。
     * 与当前脚格/头格相交的格固定加罚 {@link #PLAYER_OCCUPIED_PENALTY}，排在所有不碰撞格之后。
     */
    static double pickScore(PlannedCell cell, BlockPos feet, Vec3 look, double turnWeight) {
        double base = pickScoreBase(cell, feet, look, turnWeight);
        return intersectsPlayer(cell, feet) ? base + PLAYER_OCCUPIED_PENALTY : base;
    }

    private static double pickScoreBase(PlannedCell cell, BlockPos feet, Vec3 look, double turnWeight) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
        Vec3 toCell = Vec3.atCenterOf(cell.pos()).subtract(eye);
        double distance = toCell.length();
        if (look == null || look.lengthSqr() <= 1.0e-6 || distance <= 1.0e-6 || turnWeight <= 0.0) {
            return distance;
        }
        double cos = Mth.clamp(look.normalize().dot(toCell.scale(1.0 / distance)), -1.0, 1.0);
        return distance + turnWeight * (1.0 - cos) * 0.5;
    }

    /**
     * 单层退着铺的优先级打分：以距离远端起手锚点 {@code farTip} 由近及远为主序，
     * 保证从远端一侧连贯退着铺回出口而不从中段切断；仍保留角色占据格惩罚。
     */
    static double retreatScore(PlannedCell cell, BlockPos farTip, BlockPos feet, Vec3 look) {
        double fromTip = Math.sqrt(cell.pos().distSqr(farTip));
        double tiebreak = feet == null ? 0.0 : 0.05 * pickScoreBase(cell, feet, look, WALK_TURN_TIEBREAKER);
        double penalty = intersectsPlayer(cell, feet) ? PLAYER_OCCUPIED_PENALTY : 0.0;
        return fromTip + tiebreak + penalty;
    }

    /** 单层连通铺面/窄路的挑格：按远端退着铺打分排序，优先铺最靠近远端且不与角色碰撞的格。 */
    static PlannedCell pickForSingleLayerRetreat(List<PlannedCell> tier, BlockPos farTip, BlockPos feet, Vec3 look) {
        tier.sort(Comparator.comparingDouble(cell -> retreatScore(cell, farTip, feet, look)));
        return tier.getFirst();
    }

    /**
     * 带当前脚位与视线的挑格预筛：在最近预筛窗口内优先挑选站着够得着且不与角色脚格/头格相交的格，
     * 按距离加视线夹角综合打分；窗口内无不碰撞可达格时，优先回退到同层最近的不碰撞格（先挪出身子再放脚下格）。
     */
    static PlannedCell pickNearestReachable(List<PlannedCell> tier, Predicate<PlannedCell> hasReachableFace,
            BlockPos feet, Vec3 look) {
        int limit = Math.min(REACHABLE_PROBES, tier.size());
        PlannedCell best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean hasLook = feet != null && look != null && look.lengthSqr() > 1.0e-6;
        for (PlannedCell cell : tier.subList(0, limit)) {
            if (intersectsPlayer(cell, feet) || !hasReachableFace.test(cell)) continue;
            if (!hasLook) return cell;
            double score = pickScore(cell, feet, look, REACHABLE_TURN_WEIGHT);
            if (score < bestScore - 1.0e-9) {
                bestScore = score;
                best = cell;
            }
        }
        if (best != null) return best;
        // 窗口内没有不碰撞的可达格：优先挑同层第一个不碰撞格让角色移开，所有格都碰撞时才退回队首。
        for (PlannedCell cell : tier) {
            if (!intersectsPlayer(cell, feet)) return cell;
        }
        return tier.getFirst();
    }

    /** 这一格现在有没有站着就够得着的面：可点面中心到眼睛不超过 4.0 格。 */
    static boolean reachableFromHere(ReadsBlocks world, PlannedCell cell, BlockPos placeAt, BlockPos feet) {
        for (PlacementAttempt.Candidate candidate : PlacementAttempt.candidates(world, cell, placeAt)) {
            var face = PlacementSpots.faceBox(candidate.clicked(), candidate.face()).getCenter();
            double dx = face.x - (feet.getX() + 0.5);
            double dy = face.y - (feet.getY() + EYE_HEIGHT);
            double dz = face.z - (feet.getZ() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= REACH_FROM_HERE * REACH_FROM_HERE) return true;
        }
        return false;
    }

    /**
     * 计算单次走近站位的超时预算（刻）：仅同层或上下 1 格且水平距离不足 3 格的近距避让微调缩短至 80 刻，
     * 以便快速切换候选面或延后当前格；高出 2 格以上的墙体/屋顶格与远距走近保持常规耐心（400 刻），
     * 避免外层 80 刻提前强杀切断靠近动作内部的半截路止损与不可达记忆。
     */
    static long approachPatienceTicks(BlockPos feet, BlockPos target) {
        if (feet == null || target == null) return BringsPlayerClose.WALK_PATIENCE_TICKS;
        if (Math.abs(target.getY() - feet.getY()) > 1) return BringsPlayerClose.WALK_PATIENCE_TICKS;
        long dx = target.getX() - feet.getX();
        long dz = target.getZ() - feet.getZ();
        return dx * dx + dz * dz < SHORT_APPROACH_DISTANCE * SHORT_APPROACH_DISTANCE
                ? SHORT_APPROACH_PATIENCE_TICKS : BringsPlayerClose.WALK_PATIENCE_TICKS;
    }
}
