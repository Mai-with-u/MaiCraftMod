// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementTraverse;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import baritone.pathing.path.PathExecutor;

/**
 * 执行合并后的地面直线：朝终点前进并疾跑；后面接着是不急拐的平路就跑着交给下一段，否则接近终点时停下。真正轮到这一步时，仍反复检查眼前一段路和惯性可能带到的位置。
 */
public final class MovementGroundStraight extends Movement {
    private final Vec3 from, target;
    private final double originalCost;

    MovementGroundStraight(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest,
                           Vec3 from, Vec3 target, double cost) {
        super(baritone, src, dest, new BetterBlockPos[0]);
        this.from = from; this.target = target; this.originalCost = cost;
        override(cost);
    }

    @Override public boolean calculatedWhileLoaded() { return true; }
    @Override public double getCost(CalculationContext context) { return calculateCost(context); }
    @Override public double recalculateCost(CalculationContext context) { return calculateCost(context); }

    @Override public double calculateCost(CalculationContext context) {
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null || executor.getPosition() >= executor.getPath().movements().size()
                || executor.getPath().movements().get(executor.getPosition()) != this) return originalCost;
        Vec3 start = ctx.player().position(), delta = target.subtract(start);
        if (ownsHop()) {
            start = new Vec3(start.x, from.y, start.z);
            delta = target.subtract(start);
        }
        double distance = delta.length();
        // 安装时已检查整条路线。执行期间只刷新包含当前动量的制动距离窗口，不必每 tick 重新扫描远处路线。
        double reach = Math.max(4, ctx.player().getDeltaMovement().horizontalDistance() * 6 + 1);
        Vec3 end = distance <= reach ? target : start.add(delta.scale(reach / distance));
        GroundCorridor geometry = corridor();
        Vec3 drift = ctx.player().getDeltaMovement().multiply(4, 0, 4);
        return geometry.clear(start, end) && geometry.clear(start, start.add(drift)) ? originalCost : COST_INF;
    }

    private GroundCorridor corridor() {
        return new GroundCorridor(ctx.world(), pos -> ctx.world().isLoaded(pos)
                && ctx.world().getWorldBorder().isWithinBounds(pos), ctx.player().getBbWidth(), ctx.player().getBbHeight(),
                NavigationProtection.snapshot().noEntryCells(), PhysicalObstacleSnapshot.EMPTY);
    }

    // 允许角色在直线附近少量偏移，避免不是恰好踩在格心就算离开路线；这份容许位置集合不替代实时碰撞检查。
    @Override protected Set<BetterBlockPos> calculateValidPositions() {
        // 记录路线经过的格子而非仅记录端点：即使直线路径未经过图节点，路线执行段恢复和距离检查仍需使用实际移动走廊。
        var cells = new HashSet<BetterBlockPos>();
        for (int x = Math.min(src.x, dest.x); x <= Math.max(src.x, dest.x); x++) {
            for (int z = Math.min(src.z, dest.z); z <= Math.max(src.z, dest.z); z++) {
                Vec3 p = new Vec3(x + 0.5, from.y, z + 0.5);
                Vec3 delta = target.subtract(from);
                double t = Mth.clamp(p.subtract(from).dot(delta) / delta.lengthSqr(), 0, 1);
                if (p.distanceToSqr(from.lerp(target, t)) <= 1) cells.add(new BetterBlockPos(x, src.y, z));
            }
        }
        cells.add(src); cells.add(dest);
        return Set.copyOf(cells);
    }

    @Override public MovementState updateState(MovementState state) {
        super.updateState(state);
        boolean hopping = ownsHop();
        if ((!ctx.player().onGround() && !hopping) || ctx.player().isInWater() || ctx.player().isPassenger()) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        Vec3 delta = target.subtract(ctx.player().position());
        Movement next = carriedInto();
        boolean carryThrough = next != null;
        // 后面还是同一高度、拐角不急的平路时，像人按住疾跑那样一路跑过去：经过这一段终点附近就交给下一段，
        // 不先减速停准再起步；疾跑一断一续会让视野跟着一缩一放。急拐弯、接上下台阶或跳、整条路走到头，
        // 才在最后两格松开疾跑、停准了再交接，免得冲过头来回修正。
        // 跑着交接还要脚已经踩进下一段认的格子：否则路线执行会按脚下格把人退回这一段，两段来回交接。
        boolean arrived = carryThrough
                ? delta.horizontalDistanceSqr() < CARRY_THROUGH_RADIUS_SQR
                        && next.getValidPositions().contains(ctx.playerFeet())
                : delta.horizontalDistanceSqr() < PRECISE_ARRIVAL_RADIUS_SQR;
        if (ctx.player().onGround() && arrived && Math.abs(delta.y) < 0.05) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90;
        boolean sprint = hopping || carryThrough || delta.horizontalDistanceSqr() > 4;
        return state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, 8), false))
                .setInput(Input.MOVE_FORWARD, true).setInput(Input.SPRINT, sprint);
    }

    // 路线上紧接着的那一步若是能接着跑过去的平路就返回它，否则返回 null。
    private Movement carriedInto() {
        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return null;
        var movements = executor.getPath().movements();
        int index = executor.getPosition();
        if (index < 0 || index + 1 >= movements.size() || movements.get(index) != this) return null;
        IMovement next = movements.get(index + 1);
        boolean flatNext = next instanceof MovementGroundStraight
                || next instanceof MovementTraverse || next instanceof MovementDiagonal;
        return flatNext && carriesThrough(from, target, next.getSrc(), next.getDest()) ? (Movement) next : null;
    }

    /**
     * 这一段跑完能不能不减速接着跑下一段：下一段与这一段同一高度，且拐角不超过 60 度。
     * 拐得更急时疾跑的惯性会把人带出下一段的走廊，还是先停准再转。
     */
    static boolean carriesThrough(Vec3 from, Vec3 target, BlockPos nextSrc, BlockPos nextDest) {
        // 终点脚底高度与下一段起点所在格差半格以上，说明中间有台阶或落差，不算平路。
        if (nextSrc.getY() != nextDest.getY() || Math.abs(target.y - nextSrc.getY()) > 0.5) return false;
        double ax = target.x - from.x, az = target.z - from.z;
        double bx = nextDest.getX() - nextSrc.getX(), bz = nextDest.getZ() - nextSrc.getZ();
        double lengths = Math.sqrt((ax * ax + az * az) * (bx * bx + bz * bz));
        if (lengths < 1.0E-6) return false;
        return (ax * bx + az * bz) / lengths >= MAX_CARRY_TURN_COS;
    }

    @Override protected boolean prepared(MovementState state) { return true; }
    @Override protected boolean safeToCancel(MovementState state) { return !ownsHop(); }

    private boolean ownsHop() {
        var executor = baritone.getPathingBehavior().getCurrent();
        return executor instanceof PathExecutor path && path.controlsGroundJump(this);
    }

    public Vec3 target() { return target; }

    /** 停准交接：离终点 0.25 格以内才算走完这一段。 */
    private static final double PRECISE_ARRIVAL_RADIUS_SQR = 0.0625;
    /** 跑着交接：经过终点 0.6 格以内就交给下一段；疾跑一刻约走 0.28 格，这个圈两三刻内一定会穿过。 */
    private static final double CARRY_THROUGH_RADIUS_SQR = 0.36;
    /** 跑着交接允许的最大拐角（60 度）的余弦。 */
    private static final double MAX_CARRY_TURN_COS = 0.5;
}
