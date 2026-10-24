// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 走上前成为这辆载具的乘客：先走到伸手够得着它，再对准它右键，以角色真成了这辆（同一只实体）的乘客为准，
 * 不是以点击发出为准。船在水上漂、骆驼在走、矿车会溜：走的路上它挪开两格就换目标点。
 * 座满了、它没了、走不过去、连点几次都没坐上，如实失败并写明是哪一样。
 *
 * <p>这是正常玩家都会做的同一套动作，坐船、坐矿车与跟随的同乘共用一份；
 * 各路载具自己的事（认哪条船、矿车推没推起来、火车选哪个座位）由调用方先做好。
 */
public final class BoardsVehicle implements Action {

    /** 对着它连点这么多次还没坐上就不点了（玩家常识：连点几下没反应就不会一直点下去）。 */
    public static final int MAX_CLICKS = 3;
    /** 它挪开这么远就换走路的目标点（格）。 */
    private static final double RETARGET = 2.0;

    private final WalkTo walks;
    private final UsesEntities uses;
    private final Entity vehicle;
    private final TerrainPermit permit;
    private final Predicate<Entity> freeSeat;
    private final String label;
    private WalkRun walk;
    private BlockPos walkAnchor;
    private Action click;
    private int clicks;
    /** 最近一下右键没坐上的原因；连点放弃时带在结果里说清。 */
    private String lastClickProblem;

    /**
     * @param freeSeat 这辆此刻还有没有给我的空座；没有这项讲究的载具（一个人坐的矿车）给 null
     * @param label    给结果的一句话，带量词说全，例如"那条船""那辆矿车"
     */
    public BoardsVehicle(WalkTo walks, UsesEntities uses, Entity vehicle, TerrainPermit permit,
                         Predicate<Entity> freeSeat, String label) {
        this.walks = walks;
        this.uses = uses;
        this.vehicle = vehicle;
        this.permit = permit;
        this.freeSeat = freeSeat;
        this.label = label;
    }

    /** 这一刻坐上载具要做的决定：坐上了就完，出事如实失败，够不着走过去，够得着就右键。判断是纯函数，离线可测。 */
    enum Next { DONE, GONE, NO_SEAT, WALK, CLICK, GIVE_UP, KEEP_CLICKING }

    /** 坐上载具的现场：坐没坐上这辆、它还在不在、有没有我的座、够不够得着、已经点了几下。 */
    record Scene(boolean ridingThis, boolean vehicleGone, boolean seatFree, boolean inReach, int clicksUsed) {}

    static Next decide(Scene s) {
        if (s.ridingThis) return Next.DONE;
        if (s.vehicleGone) return Next.GONE;
        if (!s.seatFree) return Next.NO_SEAT;
        if (!s.inReach) return Next.WALK;
        return s.clicksUsed >= MAX_CLICKS ? Next.GIVE_UP : Next.CLICK;
    }

    // 每刻：坐上了就完；它没了、座满了如实失败；够不着就走过去；够得着了停下、对准它右键，等服务器把我设成它的乘客。
    @Override
    public ActionStatus tick(TickContext context) {
        LocalPlayer me = context.player().localPlayer();
        Next next = decide(new Scene(me.getVehicle() == vehicle, vehicle.isRemoved(),
                freeSeat == null || freeSeat.test(vehicle), inReach(me), clicks));
        return switch (next) {
            case DONE -> {
                closeWalk();
                yield ActionStatus.done();
            }
            case GONE -> {
                closeWalk();
                yield ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, label + "不见了"));
            }
            case NO_SEAT -> {
                closeWalk();
                yield ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, label + "上已经没有空座了"));
            }
            case WALK -> walkCloser(context);
            case GIVE_UP -> {
                closeWalk();
                // 上一下右键的结论还在：连点这么多次都没坐上，说明这辆坐不成，不再点。
                yield ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "对着" + label + "点了 " + clicks + " 次都没坐上"
                                + (lastClickProblem == null ? "" : "：" + lastClickProblem)));
            }
            case CLICK -> {
                closeWalk();
                clicks++;
                click = uses.useEntity(vehicle, InteractionConfirmation.ridingOn(vehicle));
                yield tickClick(context);
            }
            case KEEP_CLICKING -> tickClick(context);
        };
    }

    // 推进手上的那一下右键：点失败了记一笔，下一刻重新点；点成了下一刻坐上了就完。
    private ActionStatus tickClick(TickContext context) {
        ActionStatus status = click.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            click = null;
            lastClickProblem = failed.problem().message();
        } else if (status instanceof ActionStatus.Done) {
            click = null;
        }
        return me(context).getVehicle() == vehicle ? ActionStatus.done() : ActionStatus.running();
    }

    private LocalPlayer me(TickContext context) {
        return context.player().localPlayer();
    }

    // 够不够得着它：眼睛到它碰撞箱的距离在实体交互距离以内。
    private boolean inReach(LocalPlayer me) {
        double reach = me.entityInteractionRange() - 0.5;
        return vehicle.getBoundingBox().distanceToSqr(me.getEyePosition()) <= reach * reach;
    }

    // 走到它旁边：它挪开两格就换目标点；走不过去如实失败。
    private ActionStatus walkCloser(TickContext context) {
        BlockPos at = vehicle.blockPosition();
        if (walk == null) {
            walk = walks.start(GoalCompiler.near(at, 1.5), permit);
            walkAnchor = at;
        } else if (walkAnchor.distSqr(at) >= RETARGET * RETARGET && walk.retarget(GoalCompiler.near(at, 1.5))) {
            walkAnchor = at;
        }
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            closeWalk();
            return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "走不到" + label + "旁边：" + failed.problem().message()));
        }
        if (status instanceof ActionStatus.Done) {
            // 到了或停下了：下一刻按够不够得着再看，够不着重新走。
            closeWalk();
            return ActionStatus.progressed();
        }
        return status;
    }

    private void closeWalk() {
        if (walk != null) {
            walk.close();
            walk = null;
        }
    }

    @Override
    public void pause() {
        if (walk != null) walk.pause();
        if (click != null) click.pause();
    }

    @Override
    public void close() {
        closeWalk();
        if (click != null) {
            click.close();
            click = null;
        }
    }

    @Override
    public Interruptibility interruptibility() {
        if (walk != null) return walk.interruptibility();
        return Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return walk != null ? "走到" + label + "旁边" : "对着" + label + "右键坐上去";
    }
}
