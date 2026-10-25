// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.navigation.GroundSteering;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟着一只敌对生物走到跟前站着挨打：远了走过去（它会动，走着换目标点），近了直接按键靠上去，1.5 格内停住。
 * 被墙边挡住就跳一下；不打、不举盾、不吃东西。它死了、不见了就如实说没了。动作一直不结束，由任务看掉没掉血。
 */
final class MobApproach implements Action {

    /** 比这远就走过去（格）。 */
    private static final double WALK_BEYOND = 3.5;
    /** 比这近就停住等它打（格）。 */
    private static final double CLOSE_ENOUGH = 1.5;
    /** 走着多少刻换一次目标点：它在动。 */
    private static final int RETARGET_TICKS = 10;

    private final Supplier<PlayerContext> context;
    private final WalkTo walks;
    private final Permissions permissions;
    private final int mobId;
    private final String mobType;
    private WalkRun walk;
    private int sinceRetarget;
    /** 被叫先到安全处再停、走过去的路上悬在半空：只推进这一趟走路到落地站稳；暂停时清掉。 */
    private boolean stopping;

    MobApproach(Supplier<PlayerContext> context, WalkTo walks, Permissions permissions, int mobId, String mobType) {
        this.context = Objects.requireNonNull(context, "context");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.mobId = mobId;
        this.mobType = Objects.requireNonNull(mobType, "mobType");
    }

    @Override public ActionStatus tick(TickContext tick) {
        // 收尾途中：只把走过去的这一趟推进到落地站稳，落地后原地等着，不按键靠上去、也不另起一趟走。
        if (stopping) {
            if (walk != null && !walk.stoppedSafely()) walk.tick(tick);
            return ActionStatus.running();
        }
        PlayerContext current = context.get();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player == null || current.input() == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，靠不过去"));
        }
        Entity mob = player.level().getEntity(mobId);
        if (mob == null || !mob.isAlive() || !BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString().equals(mobType)) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "那只怪死了或不见了"));
        }
        double distance = player.position().distanceTo(mob.position());
        if (distance > WALK_BEYOND) return walkCloser(tick, mob);
        // 近了先让走路停稳（跳到一半不撒手），再自己按键靠上去。
        if (walk != null) {
            if (!walk.stop()) {
                walk.tick(tick);
                return ActionStatus.running();
            }
            walk = null;
        }
        PlayerInput input = current.input();
        if (distance < CLOSE_ENOUGH) {
            input.halt(player);
            return ActionStatus.running();
        }
        Vec3 at = mob.position();
        double dx = at.x - player.getX();
        double dz = at.z - player.getZ();
        boolean jump = player.horizontalCollision && player.onGround();
        input.lookAt(player, new Vec3(at.x, player.getEyeY(), at.z));
        input.applySteering(yaw -> GroundSteering.keys(dx, dz, yaw, jump), player.getYRot(), current.clientTick());
        return ActionStatus.running();
    }

    // 远了走过去：它在动，走着隔一会儿换一次目标点；走不到就如实说。
    private ActionStatus walkCloser(TickContext tick, Entity mob) {
        GoalCompiler.Compiled near = GoalCompiler.near(mob.blockPosition(), 2);
        if (walk == null) {
            walk = walks.start(near, TerrainPermit.of(permissions));
            sinceRetarget = 0;
        } else if (++sinceRetarget >= RETARGET_TICKS) {
            sinceRetarget = 0;
            if (!walk.retarget(near)) {
                walk.close();
                walk = walks.start(near, TerrainPermit.of(permissions));
            }
        }
        ActionStatus status = walk.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            walk.close();
            walk = null;
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "走不到那只怪跟前：" + failed.problem().headline()));
        }
        if (status instanceof ActionStatus.Done) {
            walk.close();
            walk = null;
        }
        return status instanceof ActionStatus.Running running && running.progressed()
                ? ActionStatus.progressed() : ActionStatus.running();
    }

    @Override public void pause() {
        stopping = false;
        if (walk != null) walk.pause();
        release();
    }

    // 走过去的路上悬在半空停不得；近了自己按键靠上去、站着挨打时是正常干活。
    @Override public Interruptibility interruptibility() {
        return walk != null && walk.interruptibility() == Interruptibility.UNSAFE_TO_STOP
                ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    // 被叫先到安全处再停：走过去的路上转给走路，悬在半空的先落地、落地才算停稳；按键靠上去、站着时现在就能停。
    @Override public boolean stopSafely() {
        stopping = walk != null && walk.stopSafely();
        return stopping;
    }

    @Override public boolean stoppedSafely() {
        return !stopping || walk == null || walk.stoppedSafely();
    }

    @Override public void close() {
        if (walk != null) walk.close();
        walk = null;
        release();
    }

    private void release() {
        PlayerContext current = context.get();
        if (current != null && current.input() != null && current.localPlayer() != null) {
            current.input().halt(current.localPlayer());
        }
    }

    @Override public String describe() {
        return "走到敌对生物跟前站着";
    }
}
