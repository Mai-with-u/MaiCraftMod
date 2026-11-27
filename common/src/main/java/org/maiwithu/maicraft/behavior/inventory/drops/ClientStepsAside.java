// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.drops;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走开几步执行接缝的读端：丢完东西后朝远离丢出点的方向走几步，
 * 走路用角色的移动入口逐刻续发，不寻路——拾取冷却面前几步开阔地就够了。
 *
 * <p>方向挑背对丢出点的水平方向；同时避开刚丢出的落点登记（{@link DropAvoidance}）、实心障碍与危险地形：
 * 正后方有墙、危险或落点挡路时顺偏转表侧绕可走方向，遇到一格高的池岸或台阶自动补跳迈上去；
 * 若所有方向均被挡住或存在岩浆、深坑等危险则当场停步报失败，绝不硬走或跳进危险里。
 * 走完的标准同样是离开丢出点够远且不再踩着任何登记落点。
 */
public final class ClientStepsAside implements StepsAside {

    /** 朝某水平方向迈步时的地形通行状态：畅通、被普通实心障碍挡住、或存在岩浆/火/深坑等危险。 */
    enum PathClearance { CLEAR, BLOCKED_BY_WALL, HAZARDOUS }

    /** 单步地形探测结果：当前步通行状态及落地后的下一步脚位（用于跨台阶/下台阶后的连续探测）。 */
    private record StepProbe(PathClearance clearance, BlockPos nextCursor) {}

    /** 每走一格最多给多少刻；走一格正常只要几刻，留出起跳与卡角的余量。 */
    private static final int TICKS_PER_BLOCK = 12;
    /** 方向挑选时的候选偏转角（度）：正后方被墙或落点占住时逐档侧绕，直到背对半圈。 */
    private static final int[] DEVIATION_DEGREES = {0, 45, -45, 90, -90, 135, -135, 180};
    /** 探测前方障碍的水平步进采样距离（格）：近点防贴墙卡死，中点防刚走半步就撞墙或踏入深坑。 */
    private static final double[] PROBE_DISTANCES = {0.85, 1.6};
    /** 安全下台阶或浅水池底的最大容许落差（格）。 */
    private static final int MAX_SAFE_DROP = 3;

    private final InputDriver input;
    private final DropAvoidance avoidance;

    public ClientStepsAside(InputDriver input, DropAvoidance avoidance) {
        this.input = Objects.requireNonNull(input);
        this.avoidance = Objects.requireNonNull(avoidance);
    }

    @Override
    public Optional<Action> stepAway(WorldPosition from, int blocks, double awayX, double awayZ) {
        if (blocks <= 0) return Optional.empty();
        return Optional.of(new WalkAwayAction(from, blocks, (long) blocks * TICKS_PER_BLOCK + 40, awayX, awayZ));
    }

    /**
     * 仅按落点登记挑方向的重载，障碍判定默认畅通。
     */
    static Vec3 steppingDirection(double dx, double dz, Predicate<Vec3> stepIntoDrop) {
        return steppingDirection(dx, dz, stepIntoDrop, candidate -> false);
    }

    /**
     * 从背对丢出点的方向开始，按候选偏转角同时避开刚丢落点与障碍方块：
     * 先选既不踩落点也不被障碍挡住的可走方向；退而求其次选不踩落点的方向；再不行选没被墙挡的方向；
     * 全部候选都受阻时回原方向。离线可测。
     */
    static Vec3 steppingDirection(double dx, double dz, Predicate<Vec3> stepIntoDrop, Predicate<Vec3> blocked) {
        Vec3 away = baseDirection(dx, dz);
        Vec3 clearOfDrop = null;
        Vec3 clearOfWall = null;
        for (int degrees : DEVIATION_DEGREES) {
            Vec3 candidate = rotateY(away, degrees);
            boolean hitsDrop = stepIntoDrop.test(candidate);
            boolean hitsWall = blocked.test(candidate);
            if (!hitsDrop && !hitsWall) return candidate;
            if (!hitsDrop && clearOfDrop == null) clearOfDrop = candidate;
            if (!hitsWall && clearOfWall == null) clearOfWall = candidate;
        }
        if (clearOfDrop != null) return clearOfDrop;
        if (clearOfWall != null) return clearOfWall;
        return away;
    }

    /**
     * 结合落点登记与地形安全性挑选退开方向：
     * 先选既不踩刚丢落点又安全畅通的方向；若所有安全方向都会踩落点，则退而选安全畅通的方向；
     * 若所有候选方向都被墙挡住或通向岩浆、悬崖等危险，则返回空，由调用方当场停步报失败而不冒失踏入危险。
     */
    static Optional<Vec3> chooseSteppingDirection(double dx, double dz, Predicate<Vec3> stepIntoDrop,
                                                  Function<Vec3, PathClearance> clearance) {
        Vec3 away = baseDirection(dx, dz);
        Vec3 fallbackClear = null;
        for (int degrees : DEVIATION_DEGREES) {
            Vec3 candidate = rotateY(away, degrees);
            if (clearance.apply(candidate) != PathClearance.CLEAR) continue;
            if (!stepIntoDrop.test(candidate)) return Optional.of(candidate);
            if (fallbackClear == null) fallbackClear = candidate;
        }
        return Optional.ofNullable(fallbackClear);
    }

    private static Vec3 baseDirection(double dx, double dz) {
        return Math.abs(dx) + Math.abs(dz) < 0.01 ? new Vec3(0, 0, 1) : new Vec3(dx, 0, dz).normalize();
    }

    /**
     * 判断朝给定水平方向迈步是否无法安全通行（被墙挡住或存在岩浆、深坑等危险）。
     */
    static boolean directionBlocked(BlockGetter level, Vec3 playerPos, Vec3 direction) {
        return directionClearance(level, playerPos, direction) != PathClearance.CLEAR;
    }

    /**
     * 沿给定水平方向逐级探测地形通行性，实时跟踪上台阶或下台阶后的真实脚高：
     * 畅通返回 CLEAR，遇到墙体/低矮天花板返回 BLOCKED_BY_WALL，遇到岩浆/火/仙人掌/4 格及以上深坑返回 HAZARDOUS。
     */
    static PathClearance directionClearance(BlockGetter level, Vec3 playerPos, Vec3 direction) {
        if (level == null || playerPos == null || direction == null) return PathClearance.CLEAR;
        BlockPos cursor = BlockPos.containing(playerPos.x, Math.floor(playerPos.y), playerPos.z);
        for (double dist : PROBE_DISTANCES) {
            BlockPos probe = BlockPos.containing(playerPos.x + direction.x * dist, cursor.getY(),
                    playerPos.z + direction.z * dist);
            if (probe.getX() == cursor.getX() && probe.getZ() == cursor.getZ()) continue;
            StepProbe step = probeStep(level, cursor, probe);
            if (step.clearance() != PathClearance.CLEAR) return step.clearance();
            cursor = step.nextCursor();
        }
        return PathClearance.CLEAR;
    }

    /** 判断朝选定方向迈出第一步时前方是否是一格高可安全跨上的岸沿/台阶，需要附带跳跃输入。 */
    static boolean needsStepUpJump(BlockGetter level, Vec3 playerPos, Vec3 direction) {
        if (level == null || playerPos == null || direction == null) return false;
        BlockPos standing = BlockPos.containing(playerPos.x, Math.floor(playerPos.y), playerPos.z);
        BlockPos probe = BlockPos.containing(playerPos.x + direction.x * PROBE_DISTANCES[0], standing.getY(),
                playerPos.z + direction.z * PROBE_DISTANCES[0]);
        if (probe.equals(standing)) return false;
        return !passableHeadroom(level, probe) && canStepUpOnto(level, standing, probe);
    }

    // 逐格判定从 from 迈进 probe 的通行性与落脚后脚高：优先识别危险，再判平走/安全下台阶或跨上一格台阶。
    private static StepProbe probeStep(BlockGetter level, BlockPos from, BlockPos probe) {
        if (BlockPassability.isHazard(level, probe) || BlockPassability.isHazard(level, probe.above())) {
            return new StepProbe(PathClearance.HAZARDOUS, probe);
        }
        if (passableHeadroom(level, probe)) {
            BlockPos landed = findSafeFootingBelow(level, probe);
            return landed != null
                    ? new StepProbe(PathClearance.CLEAR, landed)
                    : new StepProbe(PathClearance.HAZARDOUS, probe);
        }
        if (canStepUpOnto(level, from, probe)) {
            return new StepProbe(PathClearance.CLEAR, probe.above());
        }
        if (BlockPassability.canWalkOn(level, probe)
                && (BlockPassability.isHazard(level, probe.above(2))
                        || BlockPassability.isHazard(level, from.above(2)))) {
            return new StepProbe(PathClearance.HAZARDOUS, probe);
        }
        return new StepProbe(PathClearance.BLOCKED_BY_WALL, probe);
    }

    private static boolean passableHeadroom(BlockGetter level, BlockPos feet) {
        return !BlockPassability.isHazard(level, feet)
                && !BlockPassability.isHazard(level, feet.above())
                && BlockPassability.canWalkThrough(level, feet)
                && BlockPassability.canWalkThrough(level, feet.above());
    }

    private static boolean canStepUpOnto(BlockGetter level, BlockPos standing, BlockPos probe) {
        return !BlockPassability.isHazard(level, probe)
                && !BlockPassability.isHazard(level, standing.above(2))
                && BlockPassability.canWalkOn(level, probe)
                && BlockPassability.canWalkThrough(level, standing.above(2))
                && passableHeadroom(level, probe.above());
    }

    // 向下查找容许落差内的安全落脚面：若先遇到危险方块或超过 MAX_SAFE_DROP 仍悬空返回 null，否则返回落地脚位。
    private static BlockPos findSafeFootingBelow(BlockGetter level, BlockPos feet) {
        for (int drop = 1; drop <= MAX_SAFE_DROP; drop++) {
            BlockPos below = feet.below(drop);
            if (BlockPassability.isHazard(level, below)) return null;
            if (BlockPassability.canWalkOn(level, below) || BlockPassability.isWater(level, below)) {
                return below.above();
            }
        }
        return null;
    }

    /** 水平向量绕竖轴旋转给定角度；角度制方便对着候选偏转表读。 */
    private static Vec3 rotateY(Vec3 flat, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(flat.x * cos + flat.z * sin, 0.0, -flat.x * sin + flat.z * cos);
    }

    /** 朝背对丢出点的方向走到离开够远、且不再踩着刚丢的落点为止；四周受阻或危险时当场停步报失败。 */
    private final class WalkAwayAction implements Action {
        private final WorldPosition from;
        private final int blocks;
        private final long deadlineTicks;
        /** 还站在丢出点上时往哪边起步：背对东西落下的方向。 */
        private final double awayX;
        private final double awayZ;
        private long walkedTicks;

        WalkAwayAction(WorldPosition from, int blocks, long deadlineTicks, double awayX, double awayZ) {
            this.from = from;
            this.blocks = blocks;
            this.deadlineTicks = deadlineTicks;
            this.awayX = awayX;
            this.awayZ = awayZ;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            var player = context.player().localPlayer();
            double dx = player.getX() - (from.x() + 0.5);
            double dz = player.getZ() - (from.z() + 0.5);
            // 刚丢完还站在原地：按丢出方向的反方向起步，避免走进刚丢的那堆里吸回包里。
            boolean stillHere = Math.abs(dx) + Math.abs(dz) < 0.3;
            double headX = stillHere ? awayX : dx;
            double headZ = stillHere ? awayZ : dz;
            WorldPosition standing = WorldPosition.here(
                    (int) Math.floor(player.getX()),
                    (int) Math.floor(player.getY()),
                    (int) Math.floor(player.getZ()));
            // 已经离开足够远、脚下也不在自己刚丢的落点里：目的达到，松开按键收场。
            if (Math.sqrt(dx * dx + dz * dz) >= blocks && !avoidance.shouldAvoid(standing, context.gameTick())) {
                input.halt(player);
                return ActionStatus.done();
            }
            if (++walkedTicks > deadlineTicks) {
                input.halt(player);
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "走不出去：四周都被挡住了，丢出的东西可能被自己捡回", null));
            }
            // 背对丢出点挑选安全可走方向：若所有方向都被墙堵死或通向岩浆/深坑，立刻停步报失败，绝不往危险里走或跳。
            Vec3 playerPos = player.position();
            var level = player.level();
            Optional<Vec3> chosen = chooseSteppingDirection(headX, headZ, direction -> {
                double stepX = player.getX() + direction.x * 1.5;
                double stepZ = player.getZ() + direction.z * 1.5;
                WorldPosition nextCell = WorldPosition.here(
                        (int) Math.floor(stepX), standing.y(), (int) Math.floor(stepZ));
                return avoidance.shouldAvoid(nextCell, context.gameTick());
            }, direction -> directionClearance(level, playerPos, direction));
            if (chosen.isEmpty()) {
                input.halt(player);
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "走不出去：四周都被挡住或存在危险，丢出的东西可能被自己捡回", null));
            }
            Vec3 away = chosen.get();
            input.stepToward(player, player.getEyePosition().add(away.scale(2)), false);
            if (player.isInWater() || needsStepUpJump(level, playerPos, away)) {
                input.jump(player);
            }
            return ActionStatus.running();
        }

        @Override
        public String describe() {
            return "从丢出点走开 " + blocks + " 格";
        }
    }
}
