// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.explore.ExplorationSector;
import org.maiwithu.maicraft.behavior.explore.ExploreDetails;
import org.maiwithu.maicraft.behavior.explore.ExploreFinding;
import org.maiwithu.maicraft.behavior.explore.ExploreFindings;
import org.maiwithu.maicraft.behavior.explore.FindingRecorder;
import org.maiwithu.maicraft.behavior.lighting.TorchesAlongTheWay;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.perception.CaveOpenings;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 钻洞：从脚下或最近的看得见的洞口进去，顺着洞走，一路看洞壁上露出来的矿。
 * 在洞里就地开始，在地面上先走到洞口；之后反复"站定转头看一圈 → 挑下一段 → 走过去"。
 *
 * <p>看一圈的同时量路（泛洪知道哪里连着、哪里走过）、看矿（只认过视线闸的）；下一段只挑眼睛看得见的新地方，
 * 不拿泛洪偷看拐角后面哪边有矿、哪边是死胡同。眼前没有新地方就去最近的没走的岔路，岔路也没了这片洞就走完了。
 * 看见留意清单里的东西就走到跟前收场；收场时留在原地，回地面交给回露天（离洞口近会走回洞口）。
 * 钻洞自己不改方块：走路挖垫交给走到，按这次的许可。
 */
public final class CaveWalkTask extends PhasedTask<CaveWalkTask.Phase> implements TorchesAlongTheWay {

    /** 钻洞的阶段：起步（在洞里还是去洞口）→ 走到洞口 → 看一眼 → 走一段。 */
    public enum Phase { START, ENTRANCE, LOOK, WALK }

    /** 没给 max_seconds 时多久没有真实进展算卡住（刻）：走到新地方、看见新矿脉都续期。 */
    static final long DEFAULT_STUCK_AFTER_TICKS = 20L * 30;
    /** 看矿看多远（格）：与量路的水平范围一样。 */
    static final int SIGHT_RADIUS = WalkableArea.RADIUS;
    /** 一趟最多走多少格路；走满了收场，再钻一次接着走。 */
    static final int WALK_LIMIT = 256;
    /** 就地开始时这么近（格）看得见的洞口，当作从那里进的。 */
    static final int NEAR_MOUTH = 8;
    /** 场景还没扫完第一圈洞口时最多等几刻（一圈约两秒）。 */
    static final int OPENINGS_WAIT_TICKS = 60;
    /** 看完一圈后矿的索引还没补完时最多再等几刻：补不完就按已经看到的算。 */
    static final int SIGHT_WAIT_TICKS = 40;

    private final CaveWalkPlan plan;
    private final CaveWorld world;
    private final WalkTo walks;
    /** 场景此刻报的洞口与峡谷（近的在前）：挑洞口就挑这几处，与 observe 看到的一致。 */
    private final Supplier<List<CaveOpenings.Opening>> openings;
    /** 站定转头看一圈的动作：演给观众看角色在找；闲时看关着时给 null，站着看不转头。 */
    private final Supplier<Action> look;
    /** 走过的地方：几次钻洞之间留着，再钻一次不重走。 */
    private final WalkedArea walked;
    private final ExploreFindings findings = new ExploreFindings();
    private final FindingRecorder recorder;
    private final CaveTrip trip = new CaveTrip();
    private final String startDimension;
    private final BlockPos origin;
    /** 下令时给的方位（度，0 朝北顺时针）；没给为 null。 */
    private final Double bias;
    private final List<BlockPos> triedMouths = new ArrayList<>();
    /** 走过去没走到的格：它们附近的不再挑。 */
    private final List<BlockPos> tried = new ArrayList<>();
    private final List<BlockPos> unreachableInterests = new ArrayList<>();

    private BlockPos entrance;
    private WalkableArea area;
    /** 这一份量路是在哪一格量的（角色脚下）。 */
    private BlockPos areaFrom;
    private Leg leg;
    /** 下一次"看一眼"要不要转头：走到新地方转一圈；一段没走到、原地重挑就不再转。 */
    private boolean turnHead = true;
    private boolean lookDone;
    private boolean sightReady;
    private int waitedTicks;
    /** 上一段走的方位（度）：顺着走，不来回折返。 */
    private Double heading;
    private boolean anyLegArrived;
    private ExploreDetails.StopReason stopReason;

    public CaveWalkTask(CaveWalkPlan plan, CaveWorld world, WalkTo walks,
            Supplier<List<CaveOpenings.Opening>> openings, Supplier<Action> look, WalkedArea walked,
            WorldMemory memory, TaskEventSink events) {
        super("钻洞", Phase.START, new ProgressTracker(
                plan.maxSeconds() > 0 ? plan.maxSeconds() * 20L : DEFAULT_STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.plan = plan;
        this.world = world;
        this.walks = walks;
        this.openings = openings;
        this.look = look;
        this.walked = walked;
        this.recorder = new FindingRecorder(findings, world::currentSpot, memory, events);
        WorldPosition spot = world.currentSpot();
        this.origin = spot == null ? BlockPos.ZERO : new BlockPos(spot.x(), spot.y(), spot.z());
        this.startDimension = spot == null ? null : spot.dimension();
        float facing = world.facingYaw() == null ? 0f : world.facingYaw();
        this.bias = plan.direction() == null ? null
                : ExplorationSector.fix(plan.direction(), facing, plan.angleDegrees(), 0, 1).centerBearing();
    }

    @Override
    protected Action enter(Phase phase) {
        return switch (phase) {
            case START -> null;
            case LOOK -> enterLook();
            case ENTRANCE, WALK -> {
                leg.walk = walks.start(leg.goal(), plan.permit());
                yield leg.walk;
            }
        };
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        WorldPosition spot = world.currentSpot();
        if (spot == null) {
            return Next.stay();
        }
        if (!spot.dimension().equals(startDimension)) {
            return Next.done(TaskResult.cancelled("钻洞：中途换到别的维度（" + spot.dimension()
                    + "），这次钻洞作废，旧的洞不跨维度"));
        }
        // 每刻记脚下：走过的地方再钻一次不重走，这一趟走了多远按它算。
        walked.note(spot);
        trip.noteFeet(spot);
        return switch (phase) {
            case START -> tickStart(feetOf(spot));
            case ENTRANCE -> tickWalking(context, this::arrivedAtMouth, this::mouthFailed);
            case LOOK -> tickLook(context, feetOf(spot));
            case WALK -> tickWalking(context, this::legArrived, this::legFailed);
        };
    }

    /** 起步：先从脚下量一圈；头顶有遮挡、量得到一片暗处就是已经在洞里，就地开始，不然去最近的看得见的洞口。 */
    private Next<Phase> tickStart(BlockPos feet) {
        measureFrom(feet);
        if (!area.tick()) {
            return Next.stay();
        }
        long dark = area.reached().keySet().stream().filter(cell -> CaveOpenings.dark(world.skyLight(cell))).count();
        if (CaveOpenings.insideCave(world.skyLight(feet), (int) dark)) {
            startInPlace(feet);
            turnHead = true;
            return Next.go(Phase.LOOK, "已经在洞里，就地开始看一眼");
        }
        List<CaveOpenings.Opening> seen = openings.get();
        if (seen.isEmpty() && waitedTicks++ < OPENINGS_WAIT_TICKS) {
            // 场景的第一圈洞口还没扫完：等一会儿，一圈约两秒。
            return Next.stay();
        }
        Optional<CaveOpenings.Opening> mouth = pickMouth(seen, feet);
        if (mouth.isPresent()) {
            leg = new Leg(LegKind.MOUTH, mouth.get().at());
            recordProgress("挑了一个洞口");
            return Next.go(Phase.ENTRANCE, "走到最近的看得见的洞口");
        }
        stopReason = ExploreDetails.StopReason.BLOCKED;
        if (!triedMouths.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "钻洞：看得见的洞口试了 " + triedMouths.size() + " 处都没走到",
                    "许可动土（change_blocks 给 natural 或以上）放宽走路的挖垫，或先走近一点再下达"));
        }
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                "钻洞：附近看不见洞口（看了水平 " + CaveOpenings.RADIUS + " 格、上下 " + CaveOpenings.HEIGHT
                        + " 格内眼睛看得见的地方）；附近看不见不等于附近没有",
                "先 explore 跑一跑再看，或用 obtain 长程挖矿"));
    }

    /** 就地开始：脚下是开口格、或 8 格内看得见一个洞口，当作从那里进的，记进世界记忆；都不是就从出发点量。 */
    private void startInPlace(BlockPos feet) {
        Optional<CaveOpenings.Opening> near = openings.get().stream()
                .filter(opening -> opening.kind() == CaveOpenings.Kind.CAVE_MOUTH)
                .filter(opening -> opening.at().distSqr(feet) <= NEAR_MOUTH * NEAR_MOUTH)
                .min(Comparator.comparingDouble(opening -> opening.at().distSqr(feet)));
        if (near.isPresent()) {
            enterAt(near.get().at());
        } else {
            entrance = feet;
        }
    }

    /** 挑洞口：最近的、给了方向就只挑扇形里的、比起找距离远的、没走不到过的；峡谷不当洞口挑。 */
    private Optional<CaveOpenings.Opening> pickMouth(List<CaveOpenings.Opening> seen, BlockPos feet) {
        float facing = world.facingYaw() == null ? 0f : world.facingYaw();
        ExplorationSector sector = plan.direction() == null
                ? ExplorationSector.allAround(plan.minDistance(), Double.MAX_VALUE)
                : ExplorationSector.fix(plan.direction(), facing, plan.angleDegrees(), plan.minDistance(),
                        Double.MAX_VALUE);
        return seen.stream()
                .filter(opening -> opening.kind() == CaveOpenings.Kind.CAVE_MOUTH)
                .filter(opening -> !triedMouths.contains(opening.at()))
                .filter(opening -> sector.contains(opening.at().getX() - feet.getX(), opening.at().getZ() - feet.getZ()))
                .min(Comparator.comparingDouble(opening -> opening.at().distSqr(feet)));
    }

    /** 走到了洞口：记下这个洞口（到访），从这里开始看第一眼。 */
    private Next<Phase> arrivedAtMouth() {
        enterAt(leg.destination);
        area = null;
        turnHead = true;
        return Next.go(Phase.LOOK, "到洞口了，往里看一眼");
    }

    /** 洞口没走到：换下一个看得见的洞口。 */
    private Next<Phase> mouthFailed(Problem problem) {
        triedMouths.add(leg.destination);
        recordAttempt("走到洞口" + where(leg.destination), "没走到：" + problem.message());
        area = null;
        return Next.go(Phase.START, "这个洞口没走到，换一个");
    }

    // 从这个洞口进的：进结果的 entrance，也作为到访过的洞口记进世界记忆，回露天离得近就走回这里。
    private void enterAt(BlockPos mouth) {
        entrance = mouth.immutable();
        recorder.record(new ExploreFinding(ExploreFinding.Kind.TERRAIN, CaveOpenings.Kind.CAVE_MOUTH.word(),
                new WorldPosition(mouth.getX(), mouth.getY(), mouth.getZ(), startDimension), MemoryOrigin.VISITED));
    }

    /** 进"看一眼"：走到新地方转头看一圈；没走到、原地重挑时不再转。量路从脚下重新量。 */
    private Action enterLook() {
        Action turning = turnHead ? look.get() : null;
        lookDone = turning == null;
        sightReady = false;
        waitedTicks = 0;
        WorldPosition spot = world.currentSpot();
        BlockPos feet = spot == null ? origin : feetOf(spot);
        measureFrom(feet);
        return turning;
    }

    // 从脚下量路；脚还在上次量的那一格就接着用那一份。站在半砖上时量路从上面一格量起，
    // 所以按"从哪一格开始量的"比，不按量路自己的起点比，免得每刻重量、永远量不完。
    private void measureFrom(BlockPos feet) {
        if (area == null || !feet.equals(areaFrom)) {
            area = new WalkableArea(world, feet, plan.permit().mayChangeTerrain());
            areaFrom = feet;
        }
    }

    /** 看一眼：转头的同时量路、补矿的索引；三样都好了才挑下一段，不让人转完头再站着等。 */
    private Next<Phase> tickLook(TickContext context, BlockPos feet) {
        if (!lookDone) {
            ActionStatus status = runAction(context);
            lookDone = !(status instanceof ActionStatus.Running);
        }
        boolean measured = area.tick();
        if (!sightReady) {
            sightReady = world.prepareSight(SIGHT_RADIUS, plan.interestBlocks());
        }
        if (!lookDone || !measured) {
            return Next.stay();
        }
        if (!sightReady && waitedTicks++ < SIGHT_WAIT_TICKS) {
            return Next.stay();
        }
        return decide(feet);
    }

    /** 看完了：记下看见的，看见留意的就过去，走够了就收场，不然挑下一段。 */
    private Next<Phase> decide(BlockPos feet) {
        List<CaveWorld.Sighting> sightings = world.blocksInSight(SIGHT_RADIUS, plan.interestBlocks());
        noteSightings(sightings, feet);
        trip.noteBlocked(area.blocked());
        Optional<CaveWorld.Sighting> interest = sightings.stream()
                .filter(sighting -> sighting.interest() || plan.watchLava() && sighting.lava())
                .filter(sighting -> !unreachableInterests.contains(sighting.pos()))
                .findFirst();
        if (interest.isPresent()) {
            leg = new Leg(interest.get().lava() && !interest.get().interest() ? LegKind.LAVA : LegKind.INTEREST,
                    interest.get().pos());
            return Next.go(Phase.WALK, "看见了留意的东西，走到跟前");
        }
        if (trip.walkedBlocks() >= WALK_LIMIT) {
            stopReason = ExploreDetails.StopReason.WALK_LIMIT;
            return Next.done(TaskResult.done("钻洞：这一趟走满了 " + WALK_LIMIT + " 格路，还有 " + trip.branchCount()
                    + " 条没走的岔路；再钻一次接着走，走过的不重走。" + backToSurface()));
        }
        if (horizontal(entrance, feet) >= plan.maxDistance() - CaveLegChoice.FRESH) {
            stopReason = ExploreDetails.StopReason.RADIUS_REACHED;
            return Next.done(TaskResult.done("钻洞：离洞口走到了说好的 " + plan.maxDistance() + " 格，收场。"
                    + backToSurface()));
        }
        return chooseLeg(feet);
    }

    // 这一眼看见的：矿按格记，聚成矿脉落账；岩浆按岩浆湖落账。只认过了视线闸的。
    private void noteSightings(List<CaveWorld.Sighting> sightings, BlockPos feet) {
        if (trip.noteOres(sightings)) {
            recordProgress("看见了新的矿");
        }
        for (OreVeins.Vein vein : OreVeins.group(trip.ores(), feet)) {
            recorder.record(new ExploreFinding(ExploreFinding.Kind.ORE_VEIN, vein.blockId(), at(vein.nearest()),
                    MemoryOrigin.SEEN));
        }
        for (CaveWorld.Sighting sighting : sightings) {
            if (sighting.lava()) {
                recorder.record(new ExploreFinding(ExploreFinding.Kind.TERRAIN, "lava_pool", at(sighting.pos()),
                        MemoryOrigin.SEEN));
            }
        }
    }

    /** 挑下一段：眼前看得见的新地方挑一个走过去；没有就去最近的没走的岔路；岔路也没了，这片洞走完了。 */
    private Next<Phase> chooseLeg(BlockPos feet) {
        List<BlockPos> covered = area.reached().keySet().stream()
                .filter(cell -> CaveOpenings.underCover(world.skyLight(cell)))
                .toList();
        CaveLegChoice.Choice choice = CaveLegChoice.choose(
                new CaveLegChoice.Situation(feet, entrance, plan.maxDistance(), heading, bias),
                covered, cell -> walked.walkedNear(cell, CaveLegChoice.FRESH), world::visible, tried);
        trip.noteBranches(choice.branches());
        trip.dropBranches(cell -> walked.walkedNear(cell, CaveLegChoice.FRESH)
                || tried.stream().anyMatch(failed -> failed.distSqr(cell) <= CaveLegChoice.TRIED_RADIUS * CaveLegChoice.TRIED_RADIUS));
        if (choice.next() != null) {
            heading = CaveLegChoice.bearingOf(feet, choice.next());
            leg = new Leg(LegKind.STEP, choice.next());
            return Next.go(Phase.WALK, "往看得见的最远的新地方走一段");
        }
        Optional<CaveLegChoice.Branch> branch = trip.takeNearestBranch(feet);
        if (branch.isPresent()) {
            heading = CaveLegChoice.bearingOf(feet, branch.get().at());
            leg = new Leg(LegKind.BRANCH, branch.get().at());
            return Next.go(Phase.WALK, "眼前没有新地方，去最近的没走的岔路（" + branch.get().compass() + "）");
        }
        if (!anyLegArrived && trip.failures() > 0) {
            stopReason = ExploreDetails.StopReason.BLOCKED;
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "钻洞受阻：一段都没走成，试了 " + trip.failures() + " 处都没走到",
                    "许可动土（change_blocks 给 natural 或以上）放宽走路的挖垫，或换个洞口"));
        }
        stopReason = ExploreDetails.StopReason.FRONTIERS_EXHAUSTED;
        return Next.done(TaskResult.done("钻洞：这片洞能走到的地方都走过了，收场。" + backToSurface()));
    }

    /** 一段走到了：走到留意的东西跟前就收场，不然到了新地方再看一眼。 */
    private Next<Phase> legArrived() {
        trip.noteLeg(true);
        anyLegArrived = true;
        if (leg.kind == LegKind.INTEREST || leg.kind == LegKind.LAVA) {
            stopReason = ExploreDetails.StopReason.INTEREST_SEEN;
            return Next.done(TaskResult.done("钻洞：看见了留意的东西，停在它跟前" + where(leg.destination)
                    + "；要挖就接着 obtain。" + backToSurface()));
        }
        area = null;
        turnHead = true;
        return Next.go(Phase.LOOK, "走到了新地方，停下看一眼");
    }

    /** 一段没走到：记一段失败，这一处附近不再挑，原地重挑（不再转头）。 */
    private Next<Phase> legFailed(Problem problem) {
        trip.noteLeg(false);
        if (leg.kind == LegKind.INTEREST || leg.kind == LegKind.LAVA) {
            unreachableInterests.add(leg.destination);
        } else {
            tried.add(leg.destination);
        }
        recordAttempt(describeLeg(), "没走到：" + problem.message());
        area = null;
        turnHead = false;
        return Next.go(Phase.LOOK, "这一段没走到，原地换一处");
    }

    /**
     * 走路的那一段：推进走到，走到或走不到按段结算。走到报"已停下"不是走到了：被叫先到安全处再停、
     * 落地了就站着等外层停下；被打断过的这一段从原地接着走。
     */
    private Next<Phase> tickWalking(TickContext context, Supplier<Next<Phase>> arrived,
            Function<Problem, Next<Phase>> failed) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running
                || leg.walk.report().state() == WalkReport.State.STOPPED && (stopping() || leg.walk.resume())) {
            return Next.stay();
        }
        if (status instanceof ActionStatus.Failed failure) {
            return failed.apply(failure.problem());
        }
        return arrived.get();
    }

    /** 空转满时限：带上走了几段、走了多远。 */
    @Override
    protected Problem noProgressProblem(ProgressTracker.Status status) {
        String idleText = switch (status) {
            case ProgressTracker.Status.Stuck stuck ->
                    "自「" + stuck.lastProgress() + "」之后推进了 " + stuck.idleTicks() + " 刻都没有新进展";
            case ProgressTracker.Status.TimedOut timedOut ->
                    "已推进 " + timedOut.activeTicks() + " 刻，超过这件事最多能做的时长";
            default -> "长时间没有新进展";
        };
        stopReason = ExploreDetails.StopReason.BLOCKED;
        return Problem.of(Problem.Kind.STUCK,
                "钻洞空转收场：" + idleText + "；共走 " + trip.legs() + " 段失败 " + trip.failures() + " 段，走了 "
                        + trip.walkedBlocks() + " 格路",
                "换个洞口，或许可动土（change_blocks 给 natural 或以上）后再钻");
    }

    @Override
    protected ResultDetails details() {
        WorldPosition spot = world.currentSpot();
        BlockPos feet = spot == null ? origin : feetOf(spot);
        List<CaveDetails.Finding> listed = new ArrayList<>();
        for (ExploreFinding finding : findings.all()) {
            if (finding.kind() != ExploreFinding.Kind.ORE_VEIN) {
                listed.add(new CaveDetails.Finding(finding.kind(), finding.targetId(), finding.position(),
                        finding.origin(), null));
            }
        }
        // 矿脉按格重新聚：同种相连的算一条，带上看见了几块；矿脉落账时按 24 格合并的只管记忆与事件。
        for (OreVeins.Vein vein : OreVeins.group(trip.ores(), feet)) {
            listed.add(new CaveDetails.Finding(ExploreFinding.Kind.ORE_VEIN, vein.blockId(), at(vein.nearest()),
                    MemoryOrigin.SEEN, vein.blocks()));
        }
        BlockPos from = entrance == null ? origin : entrance;
        return new CaveDetails(listed, at(from), trip.describeBranches(feet),
                new CaveDetails.Legs(trip.legs(), trip.failures(), trip.walkedBlocks()), stopReason);
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case START -> "看看在不在洞里";
            case ENTRANCE -> "走到洞口";
            case LOOK -> "停下看一眼";
            case WALK -> "在洞里走";
        };
    }

    /** 本来就在挪地方：被临时任务带离原地后从新地点接着走。 */
    @Override
    public boolean resumesAnywhere() {
        return true;
    }

    private String describeLeg() {
        return switch (leg.kind) {
            case MOUTH -> "走到洞口" + where(leg.destination);
            case STEP -> "往新地方走一段" + where(leg.destination);
            case BRANCH -> "去没走的岔路" + where(leg.destination);
            case INTEREST, LAVA -> "走到留意的东西跟前" + where(leg.destination);
        };
    }

    private static String backToSurface() {
        return "回地面用 travel(here)：离洞口近会走回洞口，远了就近找见天的地方或挖楼梯上去";
    }

    private static String where(BlockPos at) {
        return "（" + at.getX() + ", " + at.getY() + ", " + at.getZ() + "）";
    }

    private WorldPosition at(BlockPos cell) {
        return new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), startDimension);
    }

    private static BlockPos feetOf(WorldPosition spot) {
        return new BlockPos(spot.x(), spot.y(), spot.z());
    }

    private static double horizontal(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 一段走去哪：洞口、新地方、岔路、留意的方块或岩浆。 */
    private enum LegKind { MOUTH, STEP, BRANCH, INTEREST, LAVA }

    /** 一段在途的走行：去哪、怎么算到了、走到运行。 */
    private static final class Leg {
        final LegKind kind;
        final BlockPos destination;
        WalkRun walk;

        Leg(LegKind kind, BlockPos destination) {
            this.kind = kind;
            this.destination = destination.immutable();
        }

        // 新地方、岔路、洞口走到附近站定就行；留意的方块要够得着、看得见；岩浆不挨着，离几格看着就停。
        GoalCompiler.Compiled goal() {
            return switch (kind) {
                case MOUTH, STEP, BRANCH -> GoalCompiler.near(destination, 2);
                case INTEREST -> GoalCompiler.interact(destination);
                case LAVA -> GoalCompiler.near(destination, 4);
            };
        }
    }
}
