// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 临时方块的找台阶、找链与收回顺序：某格只差站高一格才点得到时，在地上垫一级台阶站上去；
 * 没有可点击面时，从目标六向的空气格向外找到最近的已有方块，垫一条可拆的链接到目标旁边；收回时先收站着够得着的，再沿自己的柱逐格下撤，每格拆前确认下一格落得稳。
 * 纯函数，只读世界；放与拆由施工任务做，账记在世界记忆里。
 */
public final class TemporaryBlocks {

    /** 先往下找，再四周，最后往上：人垫脚先垫脚下。 */
    private static final Direction[] DIRECTIONS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.UP};
    /** 一次搜索最多看多少格；再多说明周围空得离谱，不是垫几块能解决的。 */
    private static final int MAX_CELLS = 4096;
    /** 一条链最多几块。 */
    public static final int MAX_CHAIN = 32;
    private static final int REACH_SIDEWAYS = 4;
    private static final int REACH_DOWN = 24;
    private static final int REACH_UP = 2;

    private TemporaryBlocks() {}

    /**
     * 从目标旁边找一条垫块链：按离目标的步数一圈圈往外，先下后四周最后上；碰到第一块已有方块就沿来路还原。
     * 顺序从贴着真实地形的那一端开始，逐块接到目标旁边。链末端（贴目标的那块）必须能给目标提供要的点击面。
     * 找不到或超过 {@link #MAX_CHAIN} 块返回空列表。
     *
     * @param mayPlace      这一格允不允许放临时方块（许可与保护说了算）
     * @param usableSupport 贴着目标的那块能不能当目标的点击面（朝向有要求的方块要挑面）
     */
    public static List<BlockPos> chain(ReadsBlocks world, BlockPos target, Predicate<BlockPos> mayPlace, Predicate<BlockPos> usableSupport) {
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        ArrayDeque<BlockPos> search = new ArrayDeque<>();
        for (Direction direction : DIRECTIONS) {
            BlockPos start = target.relative(direction);
            if (available(world, target, start, mayPlace) && usableSupport.test(start)) {
                parents.put(start, null);
                search.add(start);
            }
        }
        while (!search.isEmpty() && parents.size() <= MAX_CELLS) {
            BlockPos next = search.removeFirst();
            for (Direction direction : DIRECTIONS) {
                BlockPos neighbor = next.relative(direction);
                if (neighbor.equals(target) || !world.loaded(neighbor)) continue;
                if (solid(world, neighbor)) return fromGround(next, parents);
                if (!parents.containsKey(neighbor) && available(world, target, neighbor, mayPlace)) {
                    parents.put(neighbor, next);
                    search.addLast(neighbor);
                }
            }
        }
        return List.of();
    }

    /** 找一级台阶时水平看多远：站上去还得够得着目标，再远没有用。 */
    private static final int STEP_RADIUS = 3;
    /** 站在台阶上要点的那一面离眼睛最远多少格：原版交互距离 4.5。 */
    private static final double STEP_REACH = 4.5;
    /** 眼睛比脚底高多少格。 */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * 找一级台阶：在地上垫一块、人站上去就能点到目标某一面的位置。只挑"站在地上点不到、高出这一格才点得到"的地方——
     * 人站在地上眼睛够不过墙顶，看不见墙顶朝上的那一面，垫一块站上去就看得见了；站在地上本来就点得到的面不靠台阶解决。
     * 台阶要垫在已有的实心方块上，台阶上面两格空着能站人，不占要放的那几格，也不垫在人脚下；
     * 紧挨着目标的先挑（像人在墙边垫一块），一样近的挑离人近的。
     * 纯函数，只读世界；找不到返回空。
     *
     * @param faces    目标此刻能从哪几面点
     * @param feet     人现在站在哪（不知道时为 null，就只按离目标远近挑）
     * @param mayPlace 这一格允不允许放临时方块（许可与保护说了算）
     * @param keepFree 放下目标会占住的格：台阶和站上去的身体都不能在里面
     */
    static Optional<BlockPos> step(ReadsBlocks world, BlockPos target, List<PlacementAttempt.Candidate> faces, BlockPos feet,
                                   Predicate<BlockPos> mayPlace, Set<BlockPos> keepFree) {
        BlockPos best = null;
        double bestNear = Double.MAX_VALUE, bestWalk = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-STEP_RADIUS, -3, -STEP_RADIUS),
                target.offset(STEP_RADIUS, -1, STEP_RADIUS))) {
            BlockPos step = pos.immutable();
            BlockPos standing = step.above();
            if (!world.loaded(step.below()) || !world.loaded(step) || !world.loaded(standing) || !world.loaded(standing.above())) continue;
            if (!solid(world, step.below()) || !world.state(step).isAir() || !passable(world, standing) || !passable(world, standing.above())) continue;
            if (keepFree.contains(step) || keepFree.contains(standing) || keepFree.contains(standing.above()) || !mayPlace.test(step)) continue;
            if (step.equals(feet) || !raisedReach(faces, step)) continue;
            double near = step.distSqr(target);
            double walk = feet == null ? 0 : step.distSqr(feet);
            if (near < bestNear || near == bestNear && walk < bestWalk) {
                best = step;
                bestWalk = walk;
                bestNear = near;
            }
        }
        return Optional.ofNullable(best);
    }

    // 站在台阶上（脚在 step 上面一格）能点到某一面，而站在台阶这格的地上点不到：高出这一格正是缺的那一点。
    private static boolean raisedReach(List<PlacementAttempt.Candidate> faces, BlockPos step) {
        Vec3 ground = new Vec3(step.getX() + 0.5, step.getY() + EYE_HEIGHT, step.getZ() + 0.5);
        Vec3 raised = ground.add(0, 1, 0);
        boolean fromGround = false, fromStep = false;
        for (PlacementAttempt.Candidate face : faces) {
            fromGround |= facesEye(face, ground);
            fromStep |= facesEye(face, raised);
        }
        return fromStep && !fromGround;
    }

    // 那一面朝着眼睛（眼睛在面的外侧），而且在交互距离内：正对着、够得着才点得到。
    private static boolean facesEye(PlacementAttempt.Candidate candidate, Vec3 eye) {
        Direction face = candidate.face();
        Vec3 center = Vec3.atCenterOf(candidate.clicked()).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Vec3 out = eye.subtract(center);
        double front = out.x * face.getStepX() + out.y * face.getStepY() + out.z * face.getStepZ();
        return front > 0.05 && out.lengthSqr() <= STEP_REACH * STEP_REACH;
    }

    // 人能站进去的格：没有碰撞外形，也不是液体。
    private static boolean passable(ReadsBlocks world, BlockPos pos) {
        BlockState state = world.state(pos);
        return state.getFluidState().isEmpty() && state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
    }

    /** 已有的、站得住或点得了的方块：不是空气、不可替换、有形状。 */
    static boolean solid(ReadsBlocks world, BlockPos pos) {
        BlockState state = world.state(pos);
        return !state.isAir() && !state.canBeReplaced() && !state.getFluidState().isSource() && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
    }

    // 只在水平四格、向下二十四格、向上两格内找已加载、允许放、现在是空气的格。
    private static boolean available(ReadsBlocks world, BlockPos target, BlockPos candidate, Predicate<BlockPos> mayPlace) {
        return !candidate.equals(target)
                && Math.abs(candidate.getX() - target.getX()) <= REACH_SIDEWAYS
                && Math.abs(candidate.getZ() - target.getZ()) <= REACH_SIDEWAYS
                && candidate.getY() >= target.getY() - REACH_DOWN && candidate.getY() <= target.getY() + REACH_UP
                && world.loaded(candidate) && mayPlace.test(candidate) && world.state(candidate).isAir();
    }

    // 沿来路还原：从贴地的那块到贴目标的那块；超过上限就放弃，不截成一条够不到目标的短链。
    private static List<BlockPos> fromGround(BlockPos anchor, Map<BlockPos, BlockPos> parents) {
        List<BlockPos> ordered = new ArrayList<>();
        for (BlockPos pos = anchor; pos != null; pos = parents.get(pos)) {
            if (ordered.size() >= MAX_CHAIN) return List.of();
            ordered.add(pos);
        }
        return List.copyOf(ordered);
    }

    /**
     * 收回顺序：先收不在脚下那一柱里的（站着就够得着的，离脚远的先收）；再收脚下这一柱，从上往下。
     * 脚下这一柱指从脚下那格往下连续的自己垫的方块。
     */
    public static List<BlockPos> removalOrder(Set<BlockPos> placed, BlockPos feet) {
        List<BlockPos> column = new ArrayList<>();
        for (BlockPos below = feet.below(); placed.contains(below); below = below.below()) column.add(below);
        List<BlockPos> others = new ArrayList<>();
        for (BlockPos pos : placed) if (!column.contains(pos)) others.add(pos);
        others.sort((a, b) -> Double.compare(b.distSqr(feet), a.distSqr(feet)));
        List<BlockPos> out = new ArrayList<>(others);
        out.addAll(column);
        return List.copyOf(out);
    }

    /**
     * 拆脚下这一格之前，下一格落得稳吗：要么下面紧挨着就是实心（落一格），要么下面连着的几格里
     * 最多空三格后有实心（不摔伤）。什么都没有或太深就不拆，这块留着记进结果。
     */
    public static boolean safeToDescend(ReadsBlocks world, BlockPos standingOn) {
        for (int drop = 1; drop <= 4; drop++) {
            BlockPos below = standingOn.below(drop);
            if (!world.loaded(below)) return false;
            if (solid(world, below)) return true;
            if (!world.state(below).getFluidState().isEmpty()) return false;
        }
        return false;
    }

    /** 当前格暂时做不了时，从它后面开始轮一圈，只保留仍待做的，把当前格留到最后再试。 */
    public static <T> List<T> defer(List<T> queue, int current, Predicate<T> pending) {
        List<T> result = new ArrayList<>();
        for (int offset = 1; offset <= queue.size(); offset++) {
            T candidate = queue.get((current + offset) % queue.size());
            if (pending.test(candidate)) result.add(candidate);
        }
        return result;
    }
}
