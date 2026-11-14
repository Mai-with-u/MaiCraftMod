// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.child.ChildTaskAction;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.interrupt.KeepsCompany;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.Standing;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 跟随任务：像一个陪着走的玩家那样跟着一个人。常驻，不自己完成。
 *
 * <p>跟：以他脚下站得住的那一格为准，离它超过 distance+2 起步、走进 distance 就停；他在走时换目标点、路线不停下重算；
 * 远了跑、快到了走。停着：看着他，挡了他的道就让到一边。跟不过去：停下看着他等，他挪开再试，2 分钟都没跟上才结束。
 * 跟丢了：分清是过门、死了、下线还是走远了，过门就跟进去，走远就去最后看见他的地方找，30 秒找不回来才结束。
 * 他坐上有空座的载具就坐同一辆，他下来我也下来；他在跟敌对生物打就上去帮（许可只给自卫时不帮）。
 * 这些处境变化都发 follow_status 事件，角色不停下等回答。
 */
final class FollowTask extends PhasedTask<FollowTask.Phase> implements Standing, KeepsCompany, ThreatResponder {

    /** 跟随的阶段。 */
    enum Phase {
        /** 锁定要跟的人。 */
        LOCK,
        /** 跟着：保持距离、停着看他、挡道就让。 */
        KEEP,
        /** 跟不过去：停下看着他等，他挪开再试。 */
        BLOCKED,
        /** 前面是危险水域：先退上岸或在岸边看着他等，不下水送死。 */
        WATER_WAIT,
        /** 跟丢了：去最后看见他的地方找，或原地等他回来。 */
        SEARCH,
        /** 跟着过传送门。 */
        PORTAL,
        /** 走过去坐上他那辆。 */
        BOARD,
        /** 和他坐同一辆。 */
        RIDE,
        /** 从载具上下来。 */
        GET_OFF,
        /** 帮他打敌对生物。 */
        HELP
    }

    /** 起步要超出设定距离多少格；迟滞防抖，避免在临界距离上一进一出地走停。 */
    static final double HYSTERESIS = 2.0;
    /** 他挪出原目标点这么远就换目标点（格）。 */
    static final double RETARGET_SLACK = 3.0;
    /** 离他脚下超过 distance 再加这么多才疾跑，近了只走，不冲过头撞人。 */
    static final double SPRINT_MARGIN = 4.0;
    /** 连续这么久没跟上就不等了（刻，2 分钟）。 */
    static final long BLOCKED_LIMIT_TICKS = 20L * 120;
    /** 跟不过去时，至少隔这么久才再试一次（刻）。 */
    static final long RETRY_GAP_TICKS = 20L * 3;
    /** 跟丢了找这么久（刻，30 秒）。 */
    static final long SEARCH_LIMIT_TICKS = 20L * 30;
    /** 让过一次路，这么久内不再让（刻）：免得两个人在门口来回躲。 */
    static final long YIELD_COOLDOWN_TICKS = 40;
    /** 帮打撤出来以后，这么久内不再上去（刻）。 */
    static final long HELP_COOLDOWN_TICKS = 20L * 5;
    /** 帮打一下都没打着就结束（怪够不着）以后，这么久内不再上去（刻）。 */
    static final long HELP_IDLE_COOLDOWN_TICKS = 20L * 30;
    /** 兜底：这么久既没跟上、也没在走近，算卡住（刻，3 分钟）；跟不过去与找人各有更短的时限。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60 * 3;

    private final FollowInput input;
    private final FollowView view;
    private final FollowMoves moves;
    private final WalkTo walks;
    private final TaskEventSink events;
    private final TerrainPermit permit;
    private final TargetMotion motion = new TargetMotion();
    private final VehicleChoices vehicles = new VehicleChoices();

    private FollowView.Locked target;
    private FollowView.Sighting last;
    private long lastSeenTick;
    private String lastDirection;
    private long now;

    private WalkRun walk;
    /** 这一趟走的目标点；他挪远过它才换。 */
    private BlockPos walkAnchor;
    /** 这一趟是让路，不是跟。 */
    private boolean yielding;
    private long lastYieldTick = Long.MIN_VALUE / 2;

    /** 跟不过去从哪一刻起算；真跟上了才清零，两次跟不过去之间短暂走几步不算跟上。 */
    private long blockedSince = -1;
    private Problem blockedBecause;
    private BlockPos blockedAnchor;
    private long lastTryTick = Long.MIN_VALUE / 2;
    /** 在岸边避险等待从哪一刻起算。 */
    private long waterWaitSince = -1;
    private long lastShoreTryTick = Long.MIN_VALUE / 2;

    private LostReason lost;
    private long lostSince;
    private Vec3 lookOut;

    private ChildTaskAction portalRun;
    private String portalTarget;

    private Action boarding;
    private Integer boardingVehicle;
    private Action gettingOff;

    private Battle help;
    /** 这一场帮打包成的手上的事：基类的暂停、收尾、能不能打断经它够得着战斗引擎。 */
    private Action helpHand;
    private long helpEndedTick = Long.MIN_VALUE / 2;
    /** 上一场帮打之后要歇多久才再上（刻）：撤出来了、或一下都没打着时歇一阵，免得来回冲。 */
    private long helpCooldown;
    /** 这一场帮打真打着了、或真挪了步。 */
    private boolean helpLanded;

    FollowTask(FollowInput input, FollowView view, FollowMoves moves, WalkTo walks, TaskEventSink events) {
        super("跟随", Phase.LOCK, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.view = Objects.requireNonNull(view, "view");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.events = Objects.requireNonNull(events, "events");
        // 跟随的走到许可直接沿用任务许可的档位：只垫不挖时跟人开路也只垫不拆；落地办法随动土一起放开。
        this.permit = TerrainPermit.of(input.permissions());
    }

    @Override protected Action enter(Phase phase) { return null; }

    // 换阶段时手上的事都带过去接着做：走路跟过阶段、上下车那一下跟着换阶段，收哪一样由任务按处境自己定。
    @Override
    protected boolean carriesInto(Phase nextPhase, Action hand) {
        return true;
    }

    @Override
    protected String doing() {
        if (portalRun != null) return portalRun.describe();
        if (help != null) return "帮他打：" + help.describe();
        if (boarding != null) return boarding.describe();
        if (gettingOff != null) return gettingOff.describe();
        if (walk != null) return yielding ? "让路：" + walk.describe() : walk.describe();
        return "看着他";
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        now = context.gameTick();
        return switch (phase) {
            case LOCK -> lock(context);
            case KEEP -> keep(context);
            case BLOCKED -> blocked(context);
            case WATER_WAIT -> waterWait(context);
            case SEARCH -> search(context);
            case PORTAL -> portal(context);
            case BOARD -> board(context);
            case RIDE -> ride(context);
            case GET_OFF -> getOff(context);
            case HELP -> help(context);
        };
    }

    private Next<Phase> lock(TickContext context) {
        FollowView.Locked locked = view.lock(context, input.target());
        if (locked == null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                    "找不到要跟的目标：它现在不在视野或观察登记里，先 observe 确认它还在",
                    "重新观察后用新的观察编号再跟"));
        }
        target = locked;
        lastDirection = locked.direction();
        return Next.go(Phase.KEEP, "锁定了目标 " + locked.name());
    }

    // 看他一眼：记下最后看见的样子与他往哪走。
    private FollowView.Sighting see(TickContext context) {
        FollowView.Sighting him = view.observe(context);
        if (him == null) return null;
        motion.observe(him.feet());
        last = him;
        lastSeenTick = now;
        lastDirection = him.direction();
        return him;
    }

    // 跟着：先看他在不在、坐没坐车、在不在打架，再按距离走或停。
    private Next<Phase> keep(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null || him.dead()) {
            return lose(context, him);
        }
        Next<Phase> turn = turnFor(context, him);
        if (turn != null) return turn;
        return keepPace(context, him);
    }

    // 他坐车、我坐着别的、前面是危险水域、他在打架：这几样先于走路处理。
    private Next<Phase> turnFor(TickContext context, FollowView.Sighting him) {
        if (him.sameVehicle()) {
            closeWalk();
            return Next.go(Phase.RIDE, "和他坐在同一辆上");
        }
        if (him.meRiding()) {
            closeWalk();
            return Next.go(Phase.GET_OFF, "我坐在别的东西上，先下来再跟");
        }
        Optional<DangerousWater.Hazard> hazard = view.waterHazard(context, him);
        if (him.vehicleId() != null && (hazard.isEmpty() || him.distance() <= 4.0)) {
            Next<Phase> boardTurn = tryBoard(context, him);
            if (boardTurn != null) return boardTurn;
        }
        if (hazard.isPresent()) {
            if (phase() == Phase.WATER_WAIT) return null;
            return startWaterWait(context, him, hazard.get());
        }
        if (him.inFight() && mayHelp() && now - helpEndedTick >= helpCooldown) {
            closeWalk();
            return Next.go(Phase.HELP, "他在跟敌对生物打，上去帮他");
        }
        return null;
    }

    // 他坐上了车：同一辆上有空座就去坐；没空座、刚没坐上的，走路跟并说一声。
    private Next<Phase> tryBoard(TickContext context, FollowView.Sighting him) {
        int vehicle = him.vehicleId();
        if (!vehicles.mayTry(vehicle, now)) return null;
        Optional<Action> board = moves.rideAlong(context, vehicle, input.permissions());
        if (board.isPresent()) {
            closeWalk();
            boarding = board.get();
            hold(boarding);
            boardingVehicle = vehicle;
            tell("他坐上了 " + him.vehicleType() + "，上面还有空座，走过去坐上");
            return Next.go(Phase.BOARD, "走过去坐上他那辆");
        }
        if (vehicles.shouldTellNoSeat(vehicle)) {
            tell("他坐上了 " + him.vehicleType() + "，上面没有我的座，走路跟着；跟不上会去找他");
        }
        return null;
    }

    // 按距离走或停：离他脚下那一格超过 distance+2 起步，进 distance 叫停；路上他接着走就换目标点，远了跑、近了走。
    private Next<Phase> keepPace(TickContext context, FollowView.Sighting him) {
        double gap = him.groundGap();
        if (gap <= input.distance() + HYSTERESIS) {
            // 跟上了：之前跟不过去的话说一声，计时清零，算一次真实进展。
            if (blockedSince >= 0) tell("又跟上了，接着跟");
            blockedSince = -1;
            waterWaitSince = -1;
            recordProgress("跟着他，相距 " + FollowWording.round(him.distance()) + " 格");
        }
        if (walk != null) {
            if (!yielding) {
                if (gap <= input.distance()) {
                    // 进了设定距离：叫停，落稳了就停住，不走到他跟前去。
                    walk.stop();
                } else {
                    walk.allowSprint(gap > input.distance() + SPRINT_MARGIN);
                    retargetIfMoved(him);
                }
            }
            Next<Phase> settled = stepWalk(context);
            if (settled != null) return settled;
            if (walk != null) return Next.stay();
        }
        if (gap > input.distance() + HYSTERESIS) {
            startWalk(him);
            return Next.stay();
        }
        // 停着：看着他；挡了他的道就让到一边。
        moves.lookAt(context, him.eye());
        maybeYield(context, him);
        return Next.stay();
    }

    // 推进一次走路；还在走返回 null，走完（到了或停下）清掉，走不通转去跟不过去。
    private Next<Phase> stepWalk(TickContext context) {
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Running running && running.progressed()) {
            recordProgress(walk.describe());
        }
        if (status instanceof ActionStatus.Failed failed) {
            boolean wasYield = yielding;
            closeWalk();
            // 让路走不过去：原地站着就是了，不算跟不过去。
            return wasYield ? null : startBlocked(context, failed.problem());
        }
        WalkReport report = walk.report();
        if (status instanceof ActionStatus.Done || report.state() == WalkReport.State.ARRIVED
                || report.state() == WalkReport.State.STOPPED) {
            closeWalk();
        }
        return null;
    }

    private void startWalk(FollowView.Sighting him) {
        closeWalk();
        walkAnchor = him.ground();
        yielding = false;
        // 走到他脚下那一格的 distance 以内就算跟上；路上进了 distance 也会叫停。
        walk = walks.start(goalNear(him.ground()), permit);
        hold(walk);
        walk.allowSprint(him.groundGap() > input.distance() + SPRINT_MARGIN);
        lastTryTick = now;
    }

    private GoalCompiler.Compiled goalNear(BlockPos ground) {
        return GoalCompiler.near(ground, Math.max(1.0, input.distance()));
    }

    // 他挪出原目标点 3 格：换目标点，路线不停下重算；这种走法换不了就另开一趟。
    private void retargetIfMoved(FollowView.Sighting him) {
        if (walkAnchor != null && walkAnchor.distSqr(him.ground()) < RETARGET_SLACK * RETARGET_SLACK) return;
        if (walk.retarget(goalNear(him.ground()))) {
            walkAnchor = him.ground();
        } else {
            startWalk(him);
        }
    }

    // 让路：挡了他的道、又过了让路的冷却，在他行进方向两侧挑一格走过去；挑不出就原地不动。
    private void maybeYield(TickContext context, FollowView.Sighting him) {
        if (now - lastYieldTick < YIELD_COOLDOWN_TICKS) return;
        double vx = motion.velocityX();
        double vz = motion.velocityZ();
        if (!AsideSpot.inTheWay(him.me(), him.feet(), vx, vz)) return;
        double[] heading = Math.hypot(vx, vz) >= AsideSpot.MOVING ? new double[] {vx, vz} : AsideSpot.facing(him.yaw());
        lastYieldTick = now;
        Optional<BlockPos> spot = view.asideSpot(context, him, heading[0], heading[1], input.distance());
        if (spot.isEmpty()) return;
        walk = walks.start(GoalCompiler.standOn(spot.get()), permit);
        hold(walk);
        walk.allowSprint(false);
        walkAnchor = null;
        yielding = true;
        recordProgress("挡了他的道，让到 " + spot.get().toShortString());
    }

    // 跟不过去：停下看着他，发一条事件说清他在哪、我在哪、为什么过不去；连着跟不过去时不重复说。
    private Next<Phase> startBlocked(TickContext context, Problem cause) {
        blockedBecause = cause;
        blockedAnchor = last == null ? null : last.ground();
        lastTryTick = now;
        boolean fresh = blockedSince < 0;
        if (fresh) {
            blockedSince = now;
            tell("跟不过去：" + cause.message() + "；他在 " + FollowWording.spot(last)
                    + "，我停在 " + FollowWording.mine(last)
                    + "；" + FollowWording.openingHint(permit) + "；他挪到我走得到的地方就接着跟");
        }
        recordAttempt("跟过去", "走不通：" + cause.message());
        return Next.go(Phase.BLOCKED, "跟不过去，停下等他");
    }

    // 跟不过去时：看着他；他回到身边就接着跟；他挪开 3 格、离上次试过了 3 秒就再试；2 分钟都没跟上就结束。
    private Next<Phase> blocked(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null || him.dead()) {
            closeWalk();
            return lose(context, him);
        }
        Next<Phase> turn = turnFor(context, him);
        if (turn != null) return turn;
        if (walk != null) {
            Next<Phase> settled = stepWalk(context);
            if (settled != null) return Next.stay();
            if (walk != null && walk.report().state() == WalkReport.State.ON_THE_WAY) {
                // 再试的这一趟走起来了：回到跟着，路上他再挪就换目标点。引擎可能先走一段半截路线，
                // 真走到他跟前才算跟上、才说"又跟上了"；半路又走不通就回来接着等，跟不过去的计时不清零。
                return Next.go(Phase.KEEP, "路走起来了，接着跟");
            }
            if (walk != null) return Next.stay();
        }
        if (him.groundGap() <= input.distance() + HYSTERESIS) {
            blockedSince = -1;
            tell("他回到我身边了，接着跟");
            return Next.go(Phase.KEEP, "他回来了");
        }
        if (now - blockedSince >= BLOCKED_LIMIT_TICKS) {
            return Next.fail(FollowWording.unreachable(blockedBecause, BLOCKED_LIMIT_TICKS, permit));
        }
        moves.lookAt(context, him.eye());
        boolean moved = blockedAnchor == null
                || blockedAnchor.distSqr(him.ground()) >= RETARGET_SLACK * RETARGET_SLACK;
        if (moved && now - lastTryTick >= RETRY_GAP_TICKS) {
            blockedAnchor = him.ground();
            startWalk(him);
        }
        return Next.stay();
    }

    // 前面是危险水域：不下水去追也不下水帮打；自己已经泡在水里就先走上最近的岸，在岸边看着他等。
    private Next<Phase> startWaterWait(TickContext context, FollowView.Sighting him, DangerousWater.Hazard hazard) {
        closeWalk();
        endHelp();
        if (waterWaitSince < 0) {
            waterWaitSince = now;
            tell("前面" + hazard.reason() + "（他在 " + FollowWording.spot(him) + "），下水太危险，在岸边等他上岸或绕开");
        }
        if (hazard.meInWater()) {
            retreatToShore(context, him, hazard);
        }
        recordAttempt("下水跟过去", hazard.reason());
        return Next.go(Phase.WATER_WAIT, "水里危险，在岸边等他");
    }

    private Next<Phase> waterWait(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null || him.dead()) {
            closeWalk();
            return lose(context, him);
        }
        Next<Phase> turn = turnFor(context, him);
        if (turn != null) return turn;
        Optional<DangerousWater.Hazard> hazard = view.waterHazard(context, him);
        if (hazard.isEmpty()) {
            closeWalk();
            waterWaitSince = -1;
            tell("他离开危险水域了，接着跟");
            recordProgress("离开危险水域，接着跟");
            return Next.go(Phase.KEEP, "水里不挡了，接着跟");
        }
        if (walk != null) {
            ActionStatus status = walk.tick(context);
            if (status instanceof ActionStatus.Running running && running.progressed()) {
                recordProgress("退回岸边干地");
            } else if (!(status instanceof ActionStatus.Running)) {
                closeWalk();
            }
        } else if (hazard.get().meInWater() && now - lastShoreTryTick >= RETRY_GAP_TICKS) {
            retreatToShore(context, him, hazard.get());
        } else {
            moves.lookAt(context, him.eye());
        }
        if (now - waterWaitSince >= BLOCKED_LIMIT_TICKS) {
            return Next.fail(FollowWording.waterDanger(hazard.get(), BLOCKED_LIMIT_TICKS));
        }
        return Next.stay();
    }

    private void retreatToShore(TickContext context, FollowView.Sighting him, DangerousWater.Hazard hazard) {
        lastShoreTryTick = now;
        view.shoreWaitSpot(context, him, hazard).ifPresent(shore -> {
            walk = walks.start(GoalCompiler.standOn(shore), permit);
            hold(walk);
            walkAnchor = null;
            yielding = false;
        });
    }

    // 看不见他了：分清是过门、死了、下线还是走远了，各说一声；过门就跟进去，走远就去最后看见他的地方找。
    private Next<Phase> lose(TickContext context, FollowView.Sighting dead) {
        closeWalk();
        endHelp();
        lostSince = now;
        if (dead != null || last != null && last.dead()) {
            lost = LostReason.DIED;
            tell("他死了（最后在 " + FollowWording.spot(dead != null ? dead : last) + "），在原地等他回来");
            return Next.go(Phase.SEARCH, "他死了，等他回来");
        }
        if (last != null && last.portal() != null) {
            String destination = PortalTrail.destination(last.position().dimension(), last.portal());
            if (destination != null) {
                portalTarget = destination;
                tell("他走进了 " + last.portal() + "（" + FollowWording.spot(last) + "），跟着进去");
                return Next.go(Phase.PORTAL, "他进了传送门，跟进去");
            }
            lost = LostReason.PORTAL_FAILED;
            // 出口门把每个人送回各自的重生点：跟进去也落不到他身边，就在原地等。
            tell("他走进了末地的出口门，这扇门把人送回各自的重生点，跟进去也找不到他；在原地等他回来");
            return Next.go(Phase.SEARCH, "他进了跟不过去的门");
        }
        if (view.presence(context) == FollowView.Presence.OFFLINE) {
            lost = LostReason.OFFLINE;
            tell("他下线了（最后在 " + FollowWording.spot(last) + "），在原地等他上线");
            return Next.go(Phase.SEARCH, "他下线了，等他回来");
        }
        lost = LostReason.OUT_OF_SIGHT;
        String where = last == null ? "看不见了" : "最后看见他在 " + FollowWording.spot(last) + "（" + lastDirection + "）";
        Optional<DangerousWater.Hazard> waterHazard = last == null ? Optional.empty() : view.waterHazard(context, last);
        if (waterHazard.isPresent()) {
            tell("跟丢了：" + where + "，那片" + waterHazard.get().reason() + "，不下水去找，在岸边等他");
            lookOut = last.eye();
            return Next.go(Phase.SEARCH, "跟丢了，水里危险不下水找");
        }
        tell("跟丢了：" + where + "，去那儿找");
        if (last != null) {
            walk = walks.start(GoalCompiler.near(last.ground(), 1.5), permit);
            hold(walk);
            walkAnchor = null;
            yielding = false;
            lookOut = motion.lookOutFrom(last.eye());
        }
        return Next.go(Phase.SEARCH, "跟丢了，去找");
    }

    // 找人：重新看见他就接着跟；走远了的先走到最后看见他的地方，再朝他离开的方向看；30 秒找不回来就结束。
    private Next<Phase> search(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him != null && !him.dead()) {
            closeWalk();
            blockedSince = -1;
            tell("又看见他了（" + FollowWording.spot(him) + "），接着跟");
            return Next.go(Phase.KEEP, "找回来了");
        }
        if (walk != null) {
            ActionStatus status = walk.tick(context);
            if (status instanceof ActionStatus.Running running && running.progressed()) {
                recordProgress("去最后看见他的地方找");
            } else if (!(status instanceof ActionStatus.Running)) {
                closeWalk();
            }
        } else if (lookOut != null) {
            moves.lookAt(context, lookOut);
        }
        if (now - lostSince >= SEARCH_LIMIT_TICKS) {
            String where = last == null ? "" : "，最后看见他在 " + FollowWording.spot(last) + "（" + lastDirection + "）";
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "跟丢了目标：" + lost.ending + where,
                    "再 observe 一次，找到它后用新的观察编号或名字重新跟随"));
        }
        return Next.stay();
    }

    // 过门：把出行的跨维度段按动作交给跟随，它交代的事实并进跟随的结果；过去了就在那边找他，没过去也按跟丢找满 30 秒再说。
    private Next<Phase> portal(TickContext context) {
        if (portalRun == null) {
            portalRun = new ChildTaskAction(moves.crossPortal(portalTarget, input.permissions()), records());
            hold(portalRun);
        }
        if (portalRun.tick(context) instanceof ActionStatus.Running running) {
            if (running.progressed()) recordProgress("跟着过传送门");
            return Next.stay();
        }
        TaskResult crossed = portalRun.settle();
        release(portalRun);
        portalRun = null;
        lostSince = now;
        lookOut = null;
        if (crossed.status() == TaskResult.Status.DONE) {
            lost = LostReason.AFTER_PORTAL;
            tell("跟着过了传送门，到了 " + portalTarget + "，在这边找他");
            return Next.go(Phase.SEARCH, "过了门，找他");
        }
        lost = LostReason.PORTAL_FAILED;
        String why = crossed.problem() == null ? crossed.summary() : crossed.problem().message();
        recordAttempt("跟着过传送门", why);
        tell("没跟着过去传送门：" + why + "；在原地等他回来");
        return Next.go(Phase.SEARCH, "没过去，等他回来");
    }

    // 上车：坐上了就和他同乘；没坐上记下这辆，一阵子内不再去坐，接着走路跟。
    private Next<Phase> board(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null || him.vehicleId() == null || !him.vehicleId().equals(boardingVehicle)) {
            // 他已经下来或换了车：不坐了，接着跟。
            endBoarding();
            return him == null ? lose(context, null) : Next.go(Phase.KEEP, "他不在那辆上了");
        }
        ActionStatus status = boarding.tick(context);
        if (status instanceof ActionStatus.Running running) {
            if (running.progressed()) recordProgress(boarding.describe());
            return Next.stay();
        }
        if (status instanceof ActionStatus.Failed failed) {
            vehicles.noteFailure(boardingVehicle, now);
            recordAttempt("坐上他那辆 " + him.vehicleType(), failed.problem().message());
            tell("没坐上他那辆 " + him.vehicleType() + "：" + failed.problem().message() + "；走路跟着");
            endBoarding();
            return Next.go(Phase.KEEP, "没坐上，走路跟");
        }
        endBoarding();
        recordProgress("坐上了他那辆 " + him.vehicleType());
        tell("坐上了他那辆 " + him.vehicleType());
        return Next.go(Phase.RIDE, "坐上了");
    }

    // 同乘：他还在这辆上就坐着；他下来了我也下来；我被甩下来了就接着走路跟。
    private Next<Phase> ride(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him == null) return lose(context, null);
        if (him.sameVehicle()) {
            recordProgress("和他坐同一辆");
            return Next.stay();
        }
        if (!him.meRiding()) {
            return Next.go(Phase.KEEP, "我已经不在车上了");
        }
        tell(him.vehicleId() == null ? "他下来了，我也下来" : "他换了一辆，我先下来");
        return Next.go(Phase.GET_OFF, "他不在这辆上了，下来");
    }

    private Next<Phase> getOff(TickContext context) {
        if (gettingOff == null) {
            gettingOff = moves.getOff();
            hold(gettingOff);
        }
        ActionStatus status = gettingOff.tick(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        gettingOff.close();
        release(gettingOff);
        gettingOff = null;
        if (status instanceof ActionStatus.Failed failed) {
            recordAttempt("从载具上下来", failed.problem().message());
            return Next.fail(Problem.of(Problem.Kind.STUCK, "跟随要下车接着走，" + failed.problem().message(),
                    "看看是不是被什么卡在座位上了"));
        }
        recordProgress("从载具上下来了");
        return Next.go(Phase.KEEP, "下来了，接着跟");
    }

    // 帮打：战斗引擎打他身边的敌对生物和打我的；他进了危险水域就立刻收手退回岸边；打完、撤出来都回去跟。
    private Next<Phase> help(TickContext context) {
        FollowView.Sighting him = see(context);
        if (him != null) {
            Optional<DangerousWater.Hazard> hazard = view.waterHazard(context, him);
            if (hazard.isPresent()) {
                return startWaterWait(context, him, hazard.get());
            }
        }
        if (help == null) {
            help = moves.helpFight(() -> last == null ? null : last.feet());
            helpHand = help.asAction();
            hold(helpHand);
            helpLanded = false;
            tell("他在跟敌对生物打（" + FollowWording.spot(last) + "），上去帮他");
        }
        Battle.Moment moment = help.tick(context);
        if (moment.progressed()) {
            helpLanded = true;
            recordProgress("帮他打：" + moment.doing());
        }
        switch (moment.state()) {
            case FINISHED -> {
                boolean landed = helpLanded;
                endHelp();
                // 一下没打着就结束（怪在够不着的地方）：歇半分钟再看，免得一直在"上去帮—帮完了"之间打转。
                helpCooldown = landed ? 0 : HELP_IDLE_COOLDOWN_TICKS;
                tell(landed ? "帮他打完了，接着跟" : "他身边的敌对生物我够不着，接着跟");
                return him == null ? lose(context, null) : Next.go(Phase.KEEP, "打完了");
            }
            case ESCAPED -> {
                endHelp();
                helpCooldown = HELP_COOLDOWN_TICKS;
                tell("帮他打不过，撤出来了（" + moment.retreatWhy() + "），接着跟");
                return him == null ? lose(context, null) : Next.go(Phase.KEEP, "撤出来了");
            }
            default -> {
                return Next.stay();
            }
        }
    }

    private void endHelp() {
        if (helpHand == null) return;
        release(helpHand);
        helpHand.close();
        helpHand = null;
        help = null;
        helpEndedTick = now;
    }

    private void endBoarding() {
        if (boarding != null) {
            boarding.close();
            release(boarding);
            boarding = null;
        }
        boardingVehicle = null;
    }

    // 许可只给自卫（fight=self_defense）时不帮他打。
    private boolean mayHelp() {
        return input.permissions().fight() != Permissions.Fight.SELF_DEFENSE;
    }

    private void closeWalk() {
        if (walk != null) {
            walk.close();
            release(walk);
            walk = null;
        }
        walkAnchor = null;
        yielding = false;
    }

    // 跟随状况发一句话；跟的是玩家时再挂上他的名字和 UUID（事件字段 player），宿主按 UUID 认出跟的是谁，
    // 点名时用的是观察编号、玩家改了显示名也对得上。跟的是动物、女仆这类时不挂。
    private void tell(String what) {
        String who = target == null ? "" : "跟随 " + target.name() + "：";
        FollowedPlayer player = target == null || target.playerUuid() == null ? null
                : new FollowedPlayer(target.name(), target.playerUuid().toString());
        events.publish(TaskEvent.Kind.FOLLOW_STATUS, who + what, player);
    }

    /** 陪着的人睡没睡：最近一刻看见他躺在床上。夜晚需求据此决定去不去睡。 */
    @Override
    public boolean companionAsleep() {
        return last != null && last.sleeping() && now - lastSeenTick <= 20;
    }

    /** 正在帮他打、引擎没在撤：被攻击的生存需求让位，这场仗归帮打管。 */
    @Override
    public boolean confidentAgainstCurrentThreats() {
        return phase() == Phase.HELP && help != null && !help.retreating();
    }

    /** 被带离原地后从哪接着做都行：跟随本来就跟着人挪地方，回来先看他在哪。 */
    @Override
    public boolean resumesAnywhere() {
        return true;
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        if (portalRun != null) return portalRun.interruptibility(context);
        if (help != null) return help.interruptibility();
        if (boarding != null) return boarding.interruptibility();
        if (gettingOff != null) return gettingOff.interruptibility();
        // 坐在车上：吃口东西这类事插得进来，夜里不会被拽下车去睡。
        if (phase() == Phase.RIDE) return Interruptibility.WORKING;
        // 走路途中按走路说（在空中不能停）；停着就是两个动作之间，不急的事（他睡了我也去睡）能插进来。
        if (walk != null) return walk.interruptibility();
        return Interruptibility.BETWEEN_ACTIONS;
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case LOCK -> "锁定目标";
            case KEEP -> "跟着";
            case BLOCKED -> "跟不过去，等他";
            case WATER_WAIT -> "水里危险，岸边等他";
            case SEARCH -> "跟丢了，找他";
            case PORTAL -> "跟着过传送门";
            case BOARD -> "坐上他那辆";
            case RIDE -> "和他同乘";
            case GET_OFF -> "下车";
            case HELP -> "帮他打";
        };
    }

    @Override
    protected ResultDetails details() {
        return target == null ? ResultDetails.NONE
                : new FollowDetails(target.type() + "（实体 " + target.entityId() + "）",
                        last == null ? null : FollowWording.round(last.distance()), lastDirection);
    }
}
