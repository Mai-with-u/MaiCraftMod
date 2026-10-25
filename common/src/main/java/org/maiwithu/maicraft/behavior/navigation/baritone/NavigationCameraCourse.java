// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import baritone.behavior.PathingBehavior;
import baritone.pathing.path.PathExecutor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 普通行走的镜头航向：既不左右乱晃，也不歪着跑。
 *
 * <p>疾跑时偏差超过五度就转，一直转到一度以内：原版只在镜头和实际前进方向相差八度以内时，才把蹭到方块
 * 算成擦边、不打断疾跑，镜头歪着跑会让疾跑时断时续。走路时偏差超过二十五度立刻转；偏向同一边超过三度、
 * 持续半秒（十刻）也转，长直线不会一路歪着看；左右来回的小修正每次换边都重新计时，不会让镜头跟着晃。
 * 开始转了就一直转到对正，不停在半路。每刻最多转九度（疾跑十二度）；同一刻多次请求都从本刻起始角度算，
 * 避免请求次数越多转得越快。
 */
final class NavigationCameraCourse {
    /** 走路时偏差超过这个角度立刻转。 */
    static final float WALK_TURN_DEGREES = 25;
    /** 疾跑时偏差超过这个角度就转：留在原版擦边判定的八度之内。 */
    static final float SPRINT_TURN_DEGREES = 5;
    /** 偏向同一边超过这个角度开始计时，持续够久也转。 */
    static final float DRIFT_DEGREES = 3;
    /** 偏向同一边持续这么多刻（半秒）就转。 */
    static final long DRIFT_TICKS = 10;
    /** 转到偏差在这个角度以内算对正，停下。 */
    static final float SETTLED_DEGREES = 1;
    static final float WALK_STEP_DEGREES = 9;
    static final float SPRINT_STEP_DEGREES = 12;

    private boolean initialized, turning, tickStartTurning, tickStartedInitialized;
    private float yaw, tickStartYaw;
    private long tick = Long.MIN_VALUE;
    /** 偏向同一边从哪一刻开始、偏向哪一边（1 左 / -1 右 / 0 没偏）；本刻起始值另存一份。 */
    private long driftSince = Long.MIN_VALUE, tickStartDriftSince = Long.MIN_VALUE;
    private int driftSide, tickStartDriftSide;

    static float pitch(float requested, boolean submerged) {
        // 原生移动有独立的路线旋转基准；普通起跳与落地不必反复看脚下，潜泳仍保留专用俯仰。
        return submerged ? Mth.clamp(requested, -90.0f, 90.0f) : 8.0f;
    }

    /**
     * 这一刻镜头该朝哪。
     *
     * @param requestedYaw 路线前方的航向
     * @param sprinting    引擎这一刻要不要疾跑：疾跑时收紧到原版擦边判定以内
     * @param revision     本刻编号；同一刻的多次请求只按最后一次算，转速不叠加
     */
    float target(float requestedYaw, boolean sprinting, long revision) {
        if (tick != revision) {
            tick = revision;
            tickStartedInitialized = initialized;
            tickStartYaw = yaw;
            tickStartTurning = turning;
            tickStartDriftSince = driftSince;
            tickStartDriftSide = driftSide;
        }
        if (!tickStartedInitialized) {
            // 刚上路：镜头直接对着路线，不从旧朝向慢慢转过来。
            yaw = Mth.wrapDegrees(requestedYaw);
            initialized = true;
            turning = false;
            driftSince = Long.MIN_VALUE;
            driftSide = 0;
            return yaw;
        }
        // 始终依据本 tick 初始航向重新计算；后续目标会替换前一移动目标，而不会再额外消耗转向预算。
        float error = Mth.wrapDegrees(requestedYaw - tickStartYaw);
        float size = Math.abs(error);
        // 偏向同一边持续多久：回到三度以内或换到另一边都重新计时，左右来回的小修正永远攒不够。
        int side = size > DRIFT_DEGREES ? (error > 0 ? 1 : -1) : 0;
        if (side == 0) {
            driftSide = 0;
            driftSince = Long.MIN_VALUE;
        } else {
            driftSide = side;
            driftSince = side == tickStartDriftSide && tickStartDriftSince != Long.MIN_VALUE
                    ? tickStartDriftSince : revision;
        }
        boolean drifted = side != 0 && revision - driftSince >= DRIFT_TICKS;
        boolean start = size > (sprinting ? SPRINT_TURN_DEGREES : WALK_TURN_DEGREES) || drifted;
        // 已经在转就一直转到对正：停在半路会让后面整段直线都歪着看。
        turning = tickStartTurning ? size > SETTLED_DEGREES : start;
        float step = sprinting ? SPRINT_STEP_DEGREES : WALK_STEP_DEGREES;
        yaw = turning ? Mth.wrapDegrees(tickStartYaw + Mth.clamp(error, -step, step)) : tickStartYaw;
        return yaw;
    }

    void reset() {
        initialized = false;
        turning = false;
        tick = Long.MIN_VALUE;
        driftSince = Long.MIN_VALUE;
        driftSide = 0;
    }

    // 普通走路的镜头看路线往前约四格的那个点：格子路线斜着走时一格一拐的锯齿被平均掉，长直线上就对着整条线，
    // 到拐角也像人一样提前一点转头。转法交给本航向（疾跑时对正到原版擦边判定以内，走路时小修正不转、持续偏一边才转），
    // 俯仰平地固定略向下，潜泳时跟着实际潜泳方向。
    // 不追引擎每刻给的方块中心瞄点——那会压低视线、随靠近越来越陡、每段交接弹回，逐格点头。
    void alongRoute(PathingBehavior pathing, PlayerInput playerInput, PlayerContext context,
                    LocalPlayer player, boolean routePresent, boolean sprinting) {
        Vec3 anchor = null;
        var executor = pathing.getCurrent();
        boolean submerged = false;
        float submergedPitch = 0.0f;
        if (routePresent && executor != null) {
            anchor = lookAhead(executor.getPath().positions(), executor.getPosition() + 1, player.position());
            if (executor instanceof PathExecutor pathExecutor && pathExecutor.submergedWaterTravelActive()) {
                submerged = true;
                submergedPitch = pathExecutor.submergedWaterCameraPitch();
            }
        }
        if (anchor == null) return;
        Vec3 delta = anchor.subtract(player.getEyePosition());
        // 前方的点就在头顶或脚下（搭柱子、往下挖）时水平方向说不准，保持现在的朝向，不让镜头乱转。
        float rawYaw = delta.horizontalDistanceSqr() < 1.0
                ? player.getYRot()
                : (float) (Math.atan2(delta.z, delta.x) * (180 / Math.PI)) - 90.0f;
        float yaw = target(rawYaw, sprinting, context.clientTick());
        float pitch = pitch(submergedPitch, submerged);
        playerInput.requestNavigationLook(yaw, pitch, context.clientTick());
    }

    /** 镜头往前看多远（水平格数）。 */
    static final double LOOK_AHEAD_BLOCKS = 4.0;
    /** 往前最多数几个路点：绕圈的楼梯这类路线，直线距离总也不够远，不能一直数下去。 */
    static final int LOOK_AHEAD_NODES = 6;

    // 从下一个路点起往前数，取第一个水平离身体够四格的路点；都不够远就取数到的最后一个。
    // 路线是空的（一格路点都没有）就没处可看：给空，镜头保持现在的朝向，不让钳位越界把这一刻的走路弄出错。
    static Vec3 lookAhead(List<? extends BlockPos> positions, int next, Vec3 body) {
        if (positions.isEmpty()) return null;
        int index = Math.clamp(next, 0, positions.size() - 1);
        int last = Math.min(positions.size() - 1, index + LOOK_AHEAD_NODES - 1);
        for (; index < last; index++) {
            Vec3 point = Vec3.atBottomCenterOf(positions.get(index));
            double dx = point.x - body.x, dz = point.z - body.z;
            if (dx * dx + dz * dz >= LOOK_AHEAD_BLOCKS * LOOK_AHEAD_BLOCKS) break;
        }
        return Vec3.atBottomCenterOf(positions.get(index));
    }
}
