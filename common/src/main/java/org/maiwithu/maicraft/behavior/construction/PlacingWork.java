// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.approach.PlacementSpots;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 砌筑：按施工顺序一格一格放。每格先看能从哪几面点，走到能点那一面的站位，用原版放置预测核对落下来是不是要的样子，
 * 把材料换到手上，点下去，按放置确认看结果。放不上的格先做别的，情况变了（旁边新放了方块、人换了一层站、
 * 挡着的生物过一会儿可能走开）再回头；什么都没变就想别的办法：只差站高一格就垫一级台阶站上去，没有能点的面就垫一条临时方块链；
 * 垫不了的记"够不着"。材料用完就停在材料尽头，缺的格如实记下。
 *
 * <p>像真人一样站一处把够得着的都放完再挪：同一层同一阶段里挑离人最近的那格先放，点哪一面先挑站着不动就够得着的。
 * 原先按蓝图顺序一格一格来，两排墙交替写的水槽就在两边来回跑，镜头跟着乱甩。
 */
final class PlacingWork extends ConstructionWork {

    private enum Step { APPROACH, HOLD, CLICK, TURN_HOLD, TURN_CLICK }

    private static final Logger LOG = LoggerFactory.getLogger(PlacingWork.class);
    /** 同一面"到了却点不到"最多换几次站位。 */
    private static final int MAX_RESPOTS = 2;

    private final Permissions permissions;
    private final String purpose;
    private final Deque<PlannedCell> queue;
    private final DeferredPlacements deferred = new DeferredPlacements();
    /** 走过去没走到之后人停在哪、要点的是哪一面：人没挪、附近也没新放方块之前，不再从同一处为同一面算路。 */
    private final Set<FailedApproach> failedApproaches = new HashSet<>();
    /** 现在第几刻：按时回头的格拿它算时候到没到。 */
    private long now;
    /** 已经为它垫过临时方块的格：同一格只垫一回。 */
    private final Set<BlockPos> scaffolded = new HashSet<>();
    /** 这次垫的临时方块：不在蓝图里，放好记进账本而不是工地。 */
    private final Set<BlockPos> temporaries = new HashSet<>();
    private PlacementAttempt attempt;
    /** 这一面站过、核对说够得着可实际点不到的站位：再靠近时不站这些格。 */
    private final Set<BlockPos> poorSpots = new HashSet<>();
    /** 这一面为"点不到"换过几次站位。 */
    private int respots;
    private Step step;
    private ConstructionSeams.Clicks.Click click;
    private int materialBefore;
    private int consumed;

    PlacingWork(ConstructionServices services, ConstructionSite site, TaskRecords records, Permissions permissions, String purpose,
                List<PlannedCell> cells) {
        super(services, site, records);
        this.permissions = permissions;
        this.purpose = purpose;
        this.queue = new ArrayDeque<>(cells);
    }

    /** 一次没走到的走近：人停在哪一格，要去点哪一格的哪一面。 */
    private record FailedApproach(BlockPos from, BlockPos clicked, Direction face) {}

    /** 补一轮队列的结果：补上了、这一阶段做完了、或者只剩按时回头的格要等一会儿。 */
    private enum Refill { REFILLED, DONE, WAIT }

    @Override protected ActionStatus advance(TickContext context) {
        now = context.gameTick();
        if (attempt != null) {
            return switch (step) {
                case APPROACH -> arrived();
                case HOLD -> clickNow();
                case CLICK -> settle();
                case TURN_HOLD -> startTurn();
                case TURN_CLICK -> settleTurn();
            };
        }
        return nextCell();
    }

    // 挑下一格：已经对了的跳过（现场对上了就收下）；没材料的记缺；没有可点的面先放一放；受保护的不动。
    private ActionStatus nextCell() {
        while (true) {
            // 主队列空了就补一轮；补不出来（都放一放的格也处理完了）就是这一阶段做完。
            while (queue.isEmpty()) {
                switch (refill()) {
                    case DONE -> { return ActionStatus.done(); }
                    // 剩下的格都在等挡着的生物走开、别处又没活可干：站着等一会儿再回头。
                    case WAIT -> { return ActionStatus.running(); }
                    case REFILLED -> { }
                }
            }
            PlannedCell cell = pollNearest();
            boolean temporary = temporaries.contains(cell.pos());
            if (temporary ? TemporaryBlocks.solid(services.site(), cell.pos()) : !site.needsWork(cell.pos())) continue;
            // 先认这一格已经对上了再问别的：上一下其实放成了（确认说不清、被打断又回来），就不再缺料、问保护或再去点，
            // 免得自己刚装好的门被当成"玩家放的"记成受保护，还在旁边垫临时方块。
            if (!temporary && site.alreadyMatches(cell, services.site())) continue;
            String itemId = itemId(cell);
            if (!services.site().creative() && services.site().carried(itemId) <= 0) {
                if (!temporary) site.problem("缺 " + itemId, cell.pos());
                continue;
            }
            Optional<Problem> refused = services.guards().allows(PermissionCheck.WorldAction.PLACE_BLOCK, cell.pos(), null);
            if (refused.isPresent()) {
                if (!temporary) site.exclude(cell.pos(), CellEnding.PROTECTED, refused.get().message());
                continue;
            }
            // 联动规则说要等别的格先放好（带漏斗等下面的带子）：等的格还没好就先放一放，做别的再回头。
            if (!waiting(cell).isEmpty()) {
                defer(cell, DeferredPlacements.Reason.WAITS_ON_OTHERS);
                continue;
            }
            // 对着哪一格放：多数就是它自己；规则说要对着别的格放而那一格被占着，怎么点都落不到这里。
            BlockPos placeAt = site.placements().placeAt(cell, services.site());
            if (!placeAt.equals(cell.pos()) && occupied(placeAt)) {
                site.exclude(cell.pos(), CellEnding.IMPOSSIBLE, "要对着另一格放才落得到这里（动力辊这类会被下面的实心面抬高一格），那一格被占着");
                continue;
            }
            List<PlacementAttempt.Candidate> candidates = reachableFirst(
                    PlacementAttempt.candidates(services.site(), cell, placeAt));
            if (candidates.isEmpty()) {
                defer(cell, DeferredPlacements.Reason.NO_FACE_WORKED);
                continue;
            }
            attempt = new PlacementAttempt(cell, placeAt, temporary, candidates);
            return tryNextCandidate();
        }
    }

    /** 站着不动点得着的距离（格）：眼睛到那一面的中心，比原版交互距离 4.5 留半格余量。 */
    private static final double REACH_FROM_HERE = 4.0;
    /** 眼睛比脚底高多少（格）：站着时的视线高度。 */
    private static final double EYE_HEIGHT = 1.62;

    // 同一层、同一阶段（清空、骨架、贴附件）里挑离人最近的一格：先把站的地方够得着的放完再挪。
    // 临时方块链要按链上的顺序垫，队头是临时方块时照原顺序。
    private PlannedCell pollNearest() {
        PlannedCell head = queue.peek();
        BlockPos feet = services.site().feet();
        if (head == null || feet == null || temporaries.contains(head.pos())) return queue.poll();
        PlannedCell best = head;
        double bestDistance = Double.MAX_VALUE;
        for (PlannedCell cell : queue) {
            if (temporaries.contains(cell.pos()) || !sameTier(cell, head)) continue;
            double distance = cell.pos().distToCenterSqr(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
            if (distance < bestDistance) {
                best = cell;
                bestDistance = distance;
            }
        }
        queue.remove(best);
        return best;
    }

    // 施工顺序里同一档：同一层、同样要不要靠着别的东西、同一阶段。跨档仍按施工顺序，地基先、贴附件后。
    private static boolean sameTier(PlannedCell cell, PlannedCell head) {
        return cell.pos().getY() == head.pos().getY()
                && BuildOrder.needsSupport(cell.state()) == BuildOrder.needsSupport(head.state())
                && BuildOrder.stage(cell) == BuildOrder.stage(head);
    }

    // 站着不动就点得着的那几面排前面（原有的先后不变）：够得着就不走，够不着才去找站位。
    private List<PlacementAttempt.Candidate> reachableFirst(List<PlacementAttempt.Candidate> candidates) {
        BlockPos feet = services.site().feet();
        if (feet == null || candidates.size() < 2) return candidates;
        List<PlacementAttempt.Candidate> near = new ArrayList<>();
        List<PlacementAttempt.Candidate> far = new ArrayList<>();
        for (PlacementAttempt.Candidate candidate : candidates) {
            var face = PlacementSpots.faceBox(candidate.clicked(), candidate.face()).getCenter();
            double dx = face.x - (feet.getX() + 0.5);
            double dy = face.y - (feet.getY() + EYE_HEIGHT);
            double dz = face.z - (feet.getZ() + 0.5);
            (dx * dx + dy * dy + dz * dz <= REACH_FROM_HERE * REACH_FROM_HERE ? near : far).add(candidate);
        }
        near.addAll(far);
        return near;
    }

    // 主队列空了：情况变了的放一放的格排回来再试；什么都没变，就给最早放不上的那格想别的办法——
    // 只差站高一格就垫一级台阶站上去，没有能点的面就垫一条临时方块链；都不行如实记下放不成。
    private Refill refill() {
        if (deferred.isEmpty()) return Refill.DONE;
        List<PlannedCell> changed = deferred.changed(services.site().feet(), now, cell -> !waiting(cell).isEmpty());
        if (!changed.isEmpty()) {
            queue.addAll(changed);
            return Refill.REFILLED;
        }
        PlannedCell stuck = deferred.takeStuck(cell -> !waiting(cell).isEmpty()).orElse(null);
        if (stuck == null) {
            if (deferred.waitingForTime()) return Refill.WAIT;
            // 剩下的只有等别的格的、和生物回头几次还挡着的：生物挡着的写明是谁、在哪、等了多久，
            // LLM 能叫它挪开再下一次同样的目标；不为它垫方块，垫方块挪不走生物。
            for (DeferredPlacements.Left left : deferred.drain()) {
                site.unreachable(left.cell().pos(), left.reason() == DeferredPlacements.Reason.CREATURE_IN_THE_WAY
                        ? "被" + left.creature() + "挡着，前后等了 " + CREATURE_WAIT_SECONDS + " 秒没走开"
                        : "要等别的格先放好，那些格没放成");
            }
            return Refill.REFILLED;
        }
        // 垫过一回临时方块还是放不上：再垫也一样（实机放一张工作台垫了四块圆石、试了八千多次），如实记下这格放不成。
        if (!scaffolded.add(stuck.pos().immutable())) {
            site.unreachable(stuck.pos(), "垫了临时方块，每一面还是放不上");
            return Refill.REFILLED;
        }
        // 这个工地不许垫（组装好的物理结构上，垫上去就成了船的一部分）：够不着就照实记够不着。
        if (!services.site().temporaryBlocksAllowed()) {
            site.unreachable(stuck.pos(), "够不着，在物理结构上不垫临时方块（会改船的重量和样子）");
            return Refill.REFILLED;
        }
        Optional<String> material = services.site().temporaryMaterial();
        boolean mayPlace = permissions.changeBlocks() != Permissions.BlockChanges.NONE;
        // 先看是不是只差站高一格：人站在地上眼睛够不过墙顶、看不见墙顶朝上那一面，就在墙边垫一块站上去。
        BlockPos placeAt = site.placements().placeAt(stuck, services.site());
        Optional<BlockPos> step = !mayPlace ? Optional.empty() : TemporaryBlocks.step(services.site(), stuck.pos(),
                PlacementAttempt.candidates(services.site(), stuck, placeAt), services.site().feet(), this::mayPlaceTemporary,
                new PlacementAttempt(stuck, placeAt, false, List.of()).occupied());
        List<BlockPos> blocks = step.map(List::of).orElseGet(() -> !mayPlace ? List.of()
                : TemporaryBlocks.chain(services.site(), stuck.pos(), this::mayPlaceTemporary, pos -> true));
        if (blocks.isEmpty() || material.isEmpty() || !services.site().creative() && services.site().carried(material.get()) < blocks.size()) {
            site.unreachable(stuck.pos(), blocks.isEmpty() ? "够不着，也找不到能垫临时方块接过去的路" : "够不着，身上的建材不够垫临时方块");
            return Refill.REFILLED;
        }
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(material.get())).defaultBlockState();
        for (BlockPos pos : blocks) {
            temporaries.add(pos.immutable());
            queue.add(PlannedCell.block(pos, state, Set.of()));
        }
        queue.add(stuck);
        records.attempt("为 " + stuck.pos().toShortString() + " 垫临时方块", step.isPresent()
                ? "站在地上点不到，在 " + step.get().toShortString() + " 垫一级台阶站上去"
                : "找到一条 " + blocks.size() + " 块的链");
        return Refill.REFILLED;
    }

    /** 生物挡着的格前后最多等多少秒：按时回头的次数乘每次隔的刻数。 */
    private static final long CREATURE_WAIT_SECONDS =
            DeferredPlacements.MAX_TIMED_RETRIES * DeferredPlacements.CREATURE_RETRY_TICKS / 20;

    // 放一放这一格：记下此刻人站在哪、已经放成了几块，情况变了再回头。
    private void defer(PlannedCell cell, DeferredPlacements.Reason reason) {
        deferred.defer(cell, reason, services.site().feet(), now);
    }

    // 每一面都试过还没放成：有生物挡着就等它走开再回头，不然按普通放不上的格放一放。
    private void deferTried(PlacementAttempt tried) {
        if (tried.creatureInTheWay != null) {
            deferred.defer(tried.cell, DeferredPlacements.Reason.CREATURE_IN_THE_WAY, tried.creatureInTheWay,
                    services.site().feet(), now);
        } else {
            defer(tried.cell, DeferredPlacements.Reason.NO_FACE_WORKED);
        }
    }

    // 原版说这一下落不下来时，放下后会占住的那几格里有没有站着生物（门的上半格里站着女仆也算）。
    private Optional<String> creatureInCells(PlacementAttempt tried) {
        for (BlockPos pos : tried.occupied()) {
            Optional<String> creature = services.site().creatureIn(pos);
            if (creature.isPresent()) return creature;
        }
        return Optional.empty();
    }

    // 放成了一块：放一放的格拿它判断情况变没变；附近有了新方块，没走到过的站位也值得再试。
    private void blockPlaced(BlockPos pos) {
        deferred.placed(pos);
        failedApproaches.clear();
    }

    // 这一格要等的、蓝图里写了而且还没做好的格。
    private List<BlockPos> waiting(PlannedCell cell) {
        return site.placements().waitsFor(cell).stream()
                .filter(pos -> site.declared().contains(pos) && site.needsWork(pos)).toList();
    }

    // 对着放的那一格被不可替换的东西占着（空气、水、草这类能直接放进去的不算）。
    private boolean occupied(BlockPos pos) {
        if (!services.site().loaded(pos)) return false;
        BlockState live = services.site().state(pos);
        return !live.isAir() && !live.canBeReplaced();
    }

    // 临时方块只能垫在蓝图没声明的格上，而且许可与保护都放行。
    private boolean mayPlaceTemporary(BlockPos pos) {
        return !site.declared().contains(pos)
                && services.guards().allows(PermissionCheck.WorldAction.PLACE_BLOCK, pos, null).isEmpty();
    }

    // 走近站位这一趟也有限时：走向站位的走路自己不出走不通的结论（人掉进深坑、站位在够不着的高处，
    // 走路一刻答一次"还在走"），到点收掉换下一面再试，不让一格的走近吃满施工的停滞闸。
    @Override protected long maxActionTicks(Action current) {
        return step == Step.APPROACH ? BringsPlayerClose.WALK_PATIENCE_TICKS : Long.MAX_VALUE;
    }

    private ActionStatus tryNextCandidate() {
        // 上一面没点成（出岔子、说没生效）再换一面之前先看现场：方块其实已经落成要的样子，就收下这一格，
        // 不再从别的面点一个已经占着的格，也不为它垫临时方块。是这一下点成的（耗材也扣了）就照放好记账，
        // 结果里写明放了，不让 LLM 看着"没有变化"再去猜门到底装上没有。
        if (!attempt.temporary && landedAnyway()) {
            int used = materialBefore < 0 ? 0 : materialBefore - services.site().carried(itemId(attempt.cell));
            consumed += used;
            blockPlaced(attempt.cell.pos());
            records.change(new Change(Change.Kind.BLOCK_PLACED, itemId(attempt.cell), 1, attempt.cell.pos().toShortString()));
            return finish();
        }
        if (!attempt.temporary && site.alreadyMatches(attempt.cell, services.site())) {
            attempt = null;
            return nextCell();
        }
        // 从这里去点这一面刚才走过去没走到：人没挪、附近也没新放方块，再算一次路还是一样，直接看下一面。
        while (attempt.hasNext() && failedApproaches.contains(approachKey(attempt.peek()))) {
            PlacementAttempt.Candidate skipped = attempt.next();
            LOG.info("[施工] 放 {}：刚才从 {} 走不到能点 {} 的 {} 面的位置，人没挪，不再算路", attempt.cell.pos().toShortString(),
                    services.site().feet().toShortString(), skipped.clicked().toShortString(), skipped.face());
        }
        if (!attempt.hasNext()) {
            // 每一面都试过了：先放一放，做别的格，情况变了（或挡着的生物过一会儿走开）再回头。
            deferTried(attempt);
            attempt = null;
            return nextCell();
        }
        PlacementAttempt.Candidate candidate = attempt.next();
        poorSpots.clear();
        respots = 0;
        step = Step.APPROACH;
        // 施工时走来走去难查：每次为一格换一面去点都记一行，人在哪、要去点哪一面，实机日志里看得出在折腾什么。
        LOG.info("[施工] 放 {}：去点 {} 的 {} 面（人在 {}）", attempt.cell.pos().toShortString(),
                candidate.clicked().toShortString(), candidate.face(),
                services.site().feet() == null ? "未知" : services.site().feet().toShortString());
        approach(candidate);
        return ActionStatus.progressed();
    }

    // 走到能点这一面的站位：避开放下后会占住的格，以及这一面已经站过却点不到的位置。
    private void approach(PlacementAttempt.Candidate candidate) {
        Set<BlockPos> avoid = Set.copyOf(poorSpots);
        begin(services.close().toward(PlacementSpots.faceTarget(candidate.clicked(), candidate.face()), permissions,
                        PlacementSpots.avoiding(attempt.occupied(), avoid::contains)),
                "走到能点 " + candidate.clicked().toShortString() + " " + candidate.face() + " 面的位置");
    }

    // 到了：用原版的放置规则试算这一下会放成什么；不是要的样子就换一面。
    private ActionStatus arrived() {
        PlacementAttempt.Candidate candidate = attempt.current();
        Optional<PlacementPrediction.Placement> predicted = services.placements().predict(attempt.cell, attempt.placeAt,
                candidate.clicked(), candidate.face());
        BlockState live = services.site().loaded(attempt.cell.pos()) ? services.site().state(attempt.cell.pos()) : null;
        boolean exact = predicted.isPresent() && predicted.get().predicted() != null && live != null
                && (PlacementPrediction.complete(attempt.cell, predicted.get().predicted())
                || PlacementPrediction.isProgress(attempt.cell, live, predicted.get().predicted()));
        // 站位核对说够得着、到了却点不到那一面（人没站在格子正中、视线擦边）：像玩家一样挪个地方再点，
        // 换两次还不行才换下一面。原先一次点不到就换面，换遍了就垫脚手架、又收掉，接线在半空一节都没放上。
        if (predicted.isEmpty() && respots < MAX_RESPOTS && services.site().feet() != null) {
            respots++;
            poorSpots.add(services.site().feet().immutable());
            LOG.info("[施工] 放 {}：在 {} 点不到 {} 的 {} 面，换个站位再点", attempt.cell.pos().toShortString(),
                    services.site().feet().toShortString(), candidate.clicked().toShortString(), candidate.face());
            step = Step.APPROACH;
            approach(candidate);
            return ActionStatus.progressed();
        }
        // 原版放出来朝向不对，但能拿扳手转到要的样子（机械动力的方向机器）：像玩家一样照放，放下后用扳手转到位。
        // 原先站着、蹲着都放不出朝下的机械手，换遍了面就记"每一面还是放不上"。
        boolean acceptable = exact || !attempt.temporary && predicted.isPresent() && predicted.get().predicted() != null
                && live != null && turnable(predicted.get().predicted());
        if (acceptable && !exact) {
            attempt.turnAfter = true;
            LOG.info("[施工] 放 {}：放出来会是 {}，放下后用扳手转到位", attempt.cell.pos().toShortString(),
                    predicted.get().predicted());
        }
        // 站位、朝向都对，原版却说这一下落不下来：看放下后会占住的格里是不是站着生物（门口的女仆、狐狸）。
        // 是就记下它，这格等它走开再回头；格里没有生物的（物品放不住、门上半格被占）照普通放不上处理。
        Optional<String> creatureHere = predicted.isPresent() && predicted.get().predicted() == null
                ? creatureInCells(attempt) : Optional.empty();
        creatureHere.ifPresent(creature -> attempt.creatureInTheWay = "站在这一格里的 " + creature);
        if (!acceptable) {
            String why = predicted.isEmpty() ? "点不到那一面"
                    : creatureHere.map(creature -> "这一格里站着 " + creature)
                            .orElse("会放成 " + predicted.get().predicted());
            LOG.info("[施工] 放 {}：从 {} 的 {} 面不行（{}）", attempt.cell.pos().toShortString(),
                    candidate.clicked().toShortString(), candidate.face(), why);
            records.attempt("从 " + candidate.clicked().toShortString() + " 的 " + candidate.face() + " 面放 " + attempt.cell.pos().toShortString(),
                    predicted.isEmpty() ? "从这里点不到那一面"
                            : creatureHere.map(creature -> "这一格里站着 " + creature + "，原版不让放")
                                    .orElse("原版会放成 " + predicted.get().predicted() + "，不是要的样子"));
            return tryNextCandidate();
        }
        attempt.placement = predicted.get();
        return hold();
    }

    // 把材料换到手上：在手上就点；在身上就换；身上没有就是材料尽头。
    private ActionStatus hold() {
        String itemId = itemId(attempt.cell);
        return switch (services.hand().hold(itemId)) {
            case ConstructionSeams.HoldPlan.Ready ready -> clickNow();
            case ConstructionSeams.HoldPlan.Move move -> {
                step = Step.HOLD;
                begin(move.action(), "把 " + itemId + " 换到手上");
                yield ActionStatus.progressed();
            }
            case ConstructionSeams.HoldPlan.NotCarried notCarried -> {
                if (!attempt.temporary) site.problem("缺 " + itemId, attempt.cell.pos());
                attempt = null;
                yield nextCell();
            }
            case ConstructionSeams.HoldPlan.Cannot cannot -> {
                records.attempt("把 " + itemId + " 换到手上", cannot.problem().message());
                defer(attempt.cell, DeferredPlacements.Reason.NO_FACE_WORKED);
                attempt = null;
                yield nextCell();
            }
        };
    }

    // 出手前冻结现场：主格与另一半的状态、身上的件数；会开界面的方块潜行着点。
    private ActionStatus clickNow() {
        PlannedCell cell = attempt.cell;
        Map<BlockPos, BlockState> before = new HashMap<>();
        before.put(cell.pos(), services.site().state(cell.pos()));
        for (var effect : PlacementPrediction.generatedBy(cell.pos(), attempt.placement.predicted())) before.put(effect.pos(), services.site().state(effect.pos()));
        materialBefore = services.site().creative() ? -1 : services.site().carried(itemId(cell));
        attempt.confirmation = new PlacementConfirmation(cell, before, attempt.placement.predicted(), materialBefore);
        // 点会开界面的方块要蹲着；试算时蹲着才放得出要的朝向的，也蹲着点。
        boolean sneak = attempt.placement.sneak() || services.placements().requiresSneak(attempt.placement.clicked());
        click = services.clicks().place(cell, attempt.placement, sneak, attempt.confirmation);
        step = Step.CLICK;
        begin(click.action(), "放 " + cell.pos().toShortString());
        return ActionStatus.progressed();
    }

    // 看结果：生效了记变化，没放满（双层半砖、雪层）再点；没生效换一面；说不清的记未确认，不再赌第二次。
    private ActionStatus settle() {
        InteractionResult result = click.result() == null ? null : click.result().get();
        PlannedCell cell = attempt.cell;
        String itemId = itemId(cell);
        if (result == null || result.verdict() == null) return unconfirmed(cell, itemId, "没有结论");
        return switch (result.verdict()) {
            case APPLIED -> {
                int used = materialBefore < 0 ? 0 : Math.max(0, materialBefore - services.site().carried(itemId));
                consumed += used;
                attempt.uses++;
                blockPlaced(cell.pos());
                records.change(new Change(Change.Kind.BLOCK_PLACED, itemId, 1, (attempt.temporary ? "临时方块 " : "") + cell.pos().toShortString()));
                BlockState live = services.site().state(cell.pos());
                // 放下来朝向不对、能拿扳手转到位（照放的，或预测说放得成却落成了别的朝向）：转到位再收下。
                if (!PlacementPrediction.complete(cell, live) && !attempt.temporary && (attempt.turnAfter || turnable(live))) {
                    yield startTurn();
                }
                if (PlacementPrediction.complete(cell, live) || attempt.uses >= PlacementPrediction.maximumUses(cell)) yield finish();
                yield arrived();
            }
            case NOT_APPLIED -> {
                records.attempt("放 " + cell.pos().toShortString(), "没生效：" + result.scene());
                yield tryNextCandidate();
            }
            case UNEXPECTED, UNCONFIRMED -> unconfirmed(cell, itemId, result.scene());
        };
    }

    // 这一格点过一下，现场已经是要的样子，身上的耗材也少了（创造模式不看耗材）：就是这一下放成的。
    private boolean landedAnyway() {
        PlannedCell cell = attempt.cell;
        if (attempt.confirmation == null || !services.site().loaded(cell.pos())
                || !PlacementPrediction.complete(cell, services.site().state(cell.pos()))) return false;
        return materialBefore < 0 || services.site().carried(itemId(cell)) < materialBefore;
    }

    // 这样放出来能不能拿扳手转到要的样子：规则说转得到、身上有那件工具才算。
    private boolean turnable(BlockState placed) {
        return services.placements().turnsTo(placed, state -> PlacementPrediction.complete(attempt.cell, state))
                .filter(turns -> services.site().creative() || services.site().carried(toolId(turns)) > 0)
                .isPresent();
    }

    // 扳手转到位：按此刻的样子重算要点哪几面，拿扳手点第一面，点完再看；转到了、转不到、没扳手、
    // 点满四下都照现在的样子收下，还不对的由整机比对交代。
    private ActionStatus startTurn() {
        PlannedCell cell = attempt.cell;
        BlockState live = services.site().state(cell.pos());
        if (PlacementPrediction.complete(cell, live)) return finish();
        Optional<PlacementRules.Turns> turns = attempt.turnsLeft <= 0 ? Optional.empty()
                : services.placements().turnsTo(live, state -> PlacementPrediction.complete(cell, state));
        if (turns.isEmpty() || turns.get().faces().isEmpty()) {
            records.attempt("用扳手把 " + cell.pos().toShortString() + " 转到要的样子", "转不到，现在是 " + live);
            return finish();
        }
        String tool = toolId(turns.get());
        return switch (services.hand().hold(tool)) {
            case ConstructionSeams.HoldPlan.Ready ready -> clickTurn(turns.get().faces().getFirst(), live);
            case ConstructionSeams.HoldPlan.Move move -> {
                step = Step.TURN_HOLD;
                begin(move.action(), "把 " + tool + " 换到手上");
                yield ActionStatus.progressed();
            }
            case ConstructionSeams.HoldPlan.NotCarried notCarried -> {
                records.attempt("用扳手把 " + cell.pos().toShortString() + " 转到要的样子", "身上没有 " + tool);
                yield finish();
            }
            case ConstructionSeams.HoldPlan.Cannot cannot -> {
                records.attempt("把 " + tool + " 换到手上", cannot.problem().message());
                yield finish();
            }
        };
    }

    // 点一面：不潜行（潜行右键是拆下来），那一格变了样才算转了一下。
    private ActionStatus clickTurn(Direction face, BlockState before) {
        attempt.turnsLeft--;
        BlockPos pos = attempt.cell.pos();
        click = services.clicks().turn(pos, face, InteractionConfirmation.blockChanged(pos, before));
        step = Step.TURN_CLICK;
        begin(click.action(), "用扳手点 " + pos.toShortString() + " 的 " + face.getName() + " 面");
        return ActionStatus.progressed();
    }

    // 转了一下：记一笔接着看还要不要转；没转动、说不清就照现在的样子收下。
    private ActionStatus settleTurn() {
        InteractionResult result = click.result() == null ? null : click.result().get();
        BlockPos pos = attempt.cell.pos();
        if (result != null && result.verdict() == InteractionVerdict.APPLIED) {
            BlockState now = services.site().state(pos);
            records.change(new Change(Change.Kind.BLOCK_CHANGED, BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString(), 1,
                    "用扳手把 " + pos.toShortString() + " 转成 " + now));
            return startTurn();
        }
        records.attempt("用扳手转 " + pos.toShortString(), result == null ? "没有结论" : "没转动：" + result.scene());
        return finish();
    }

    private static String toolId(PlacementRules.Turns turns) {
        return BuiltInRegistries.ITEM.getKey(turns.tool()).toString();
    }

    private ActionStatus unconfirmed(PlannedCell cell, String itemId, String scene) {
        records.unconfirmed(new Change(Change.Kind.BLOCK_PLACED, itemId, 1, cell.pos().toShortString() + "：" + scene));
        site.problem("放置结果没能确认，不敢再点", cell.pos());
        attempt = null;
        consumed = 0;
        return nextCell();
    }

    private ActionStatus finish() {
        PlannedCell cell = attempt.cell;
        if (attempt.temporary) {
            services.ledger().temporaryPlaced(cell.pos(), BuiltInRegistries.BLOCK.getKey(cell.state().getBlock()).toString(), purpose);
        } else {
            site.placed(cell, consumed);
        }
        consumed = 0;
        attempt = null;
        return nextCell();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        LOG.info("[施工] {} 失败：{}", describe(), failure.problem().message());
        records.attempt(describe(), failure.problem().message());
        // 扳手转到一半出了岔子：方块已经放下了，照现在的样子收下，差异交给整机比对。
        if (step == Step.TURN_HOLD || step == Step.TURN_CLICK) return finish();
        if (step == Step.HOLD) {
            defer(attempt.cell, DeferredPlacements.Reason.NO_FACE_WORKED);
            attempt = null;
            return nextCell();
        }
        // 走不到那一面：记下人停在哪、要点哪一面，人没挪就不再为它算路；然后换一面再试。
        if (step == Step.APPROACH && services.site().feet() != null && attempt.current() != null) {
            failedApproaches.add(approachKey(attempt.current()));
        }
        // 点下去没点到那一面：看眼睛和那一面之间是不是挡着生物（跟到跟前的女仆）。是就记下它，
        // 每一面都试过还没放成时，这格等它走开再回头，而不是当成站位不好去垫方块。
        if (step == Step.CLICK && attempt.current() != null) {
            services.site().creatureBetween(attempt.current().clicked(), attempt.current().face())
                    .ifPresent(creature -> attempt.creatureInTheWay = "挡在人和要点的那一面之间的 " + creature);
        }
        return tryNextCandidate();
    }

    // 从人现在站的地方去点这一面。
    private FailedApproach approachKey(PlacementAttempt.Candidate candidate) {
        BlockPos feet = services.site().feet();
        return new FailedApproach(feet == null ? null : feet.immutable(), candidate.clicked(), candidate.face());
    }

    private static String itemId(PlannedCell cell) {
        return BuiltInRegistries.ITEM.getKey(cell.item()).toString();
    }
}
