// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fillpit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.perception.CampPits;
import org.maiwithu.maicraft.behavior.travel.planning.LandmarkResolver;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 填坑任务：找地面坑 → 视距离走近目标点 → 逐坑挑坑沿平地站好 → 用身上现成多余的泥土、圆石从下往上或从外往里填平。
 *
 * <p>严格遵守"只用身上现成多余废料，不为填坑另去挖料或翻箱"：一开头身上没有多余废料就报 NEED_ITEM 收手；
 * 半途多余废料用完时保留已填平或填浅的事实如实收场。
 */
final class FillPitTask extends PhasedTask<FillPitTask.Phase> {

    enum Phase { LOCATE, APPROACH_TARGET, NEXT_CELL, STEP_RIM, PLACE_CELL }

    private static final int TARGET_SCAN_RADIUS = 6;
    private static final int APPROACH_THRESHOLD = 12;

    private final FillPitInput input;
    private final FillPitSeams.Site site;
    private final Deque<CampPits.Pit> pitQueue = new ArrayDeque<>();
    private final List<FillPitDetails.FilledPit> settledPits = new ArrayList<>();
    private final Map<String, Integer> usedByBlock = new LinkedHashMap<>();
    private BlockPos namedAnchor;
    private CampPits.Pit currentPit;
    private int cellIndex;
    private int placedInCurrentPit;
    private int totalPlaced;
    private String currentFiller;
    private BlockPos currentCell;
    private boolean fillerExhausted;
    private Action prepared;

    FillPitTask(FillPitInput input, FillPitSeams.Site site) {
        super("填平地面坑", Phase.LOCATE, new ProgressTracker(200, 20L * 180));
        this.input = Objects.requireNonNull(input, "input");
        this.site = Objects.requireNonNull(site, "site");
    }

    @Override
    protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case LOCATE -> locate(context);
            case APPROACH_TARGET -> approachTarget(context);
            case NEXT_CELL -> nextCell(context);
            case STEP_RIM -> stepRim(context);
            case PLACE_CELL -> placeCell(context);
        };
    }

    private Next<Phase> locate(TickContext context) {
        if (input.permissions().changeBlocks() == Permissions.BlockChanges.NONE) {
            return Next.fail(Problem.of(Problem.Kind.NEED_APPROVAL, "当前许可不允许改动方块，没法填坑", null));
        }
        return switch (input.target()) {
            case null -> enqueueAreaPits(context, site.feet(context), input.radius(), true);
            case Target.Here ignored -> enqueueAreaPits(context, site.feet(context), input.radius(), true);
            case Target.Seen seen -> locateSeen(context, seen.id());
            case Target.Position pos -> locateWorldPosition(context,
                    new WorldPosition(pos.x(), pos.y() != null ? pos.y() : site.feet(context).getY(),
                            pos.z(), pos.dimension()));
            case Target.Landmark landmark -> locateLandmark(context, landmark.name());
            default -> Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "填坑不接受这种目标：" + input.target().kind(), null));
        };
    }

    private Next<Phase> locateSeen(TickContext context, String seenId) {
        Optional<WorldPosition> resolved = site.seen(seenId);
        if (resolved.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seenId + " 对应的位置已经不在了", "重新 observe"));
        }
        return locateWorldPosition(context, resolved.get());
    }

    private Next<Phase> locateLandmark(TickContext context, String name) {
        var resolved = LandmarkResolver.resolve(name, site::landmark, site::signs);
        if (resolved instanceof LandmarkResolver.Resolution.Missing missing) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, missing.sentence(), null));
        }
        return locateWorldPosition(context, ((LandmarkResolver.Resolution.Found) resolved).position());
    }

    private Next<Phase> locateWorldPosition(TickContext context, WorldPosition targetPos) {
        String dim = site.dimension(context);
        if (targetPos.dimension() != null && dim != null && !targetPos.dimension().equals(dim)) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "指定的坑不在当前维度", null));
        }
        if (site.cellProtected(targetPos, input.permissions().protectedLandmarks())) {
            return Next.fail(Problem.of(Problem.Kind.NEED_APPROVAL, "指定位置落在受保护的地盘或地标旁，不填那里的坑", null));
        }
        // 离指定坑位超过 12 格时先走到 8 格内再扫坑，避免远处在未加载边缘漏扫。
        namedAnchor = new BlockPos(targetPos.x(), targetPos.y(), targetPos.z());
        BlockPos now = site.feet(context);
        if (Math.hypot(namedAnchor.getX() - now.getX(), namedAnchor.getZ() - now.getZ()) > APPROACH_THRESHOLD) {
            prepared = site.walkTo(namedAnchor);
            return Next.go(Phase.APPROACH_TARGET, "走向指定的坑边");
        }
        return enqueueTargetedPit(context, namedAnchor);
    }

    private Next<Phase> approachTarget(TickContext context) {
        ActionStatus status = runAction(context);
        BlockPos now = site.feet(context);
        if (Math.hypot(namedAnchor.getX() - now.getX(), namedAnchor.getZ() - now.getZ()) <= 8.0) {
            return enqueueTargetedPit(context, namedAnchor);
        }
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (status instanceof ActionStatus.Failed failed) return Next.fail(failed.problem());
        return enqueueTargetedPit(context, namedAnchor);
    }

    private Next<Phase> enqueueAreaPits(TickContext context, BlockPos origin, int radius, boolean requireCamp) {
        String dim = site.dimension(context);
        for (CampPits.Pit pit : site.scanPits(context, origin, radius, requireCamp)) {
            WorldPosition at = new WorldPosition(pit.center().getX(), pit.center().getY(), pit.center().getZ(), dim);
            if (!site.cellProtected(at, input.permissions().protectedLandmarks())) {
                pitQueue.add(pit);
            }
        }
        if (pitQueue.isEmpty()) {
            return Next.done(TaskResult.done("附近营地没有需要回填的地面坑"));
        }
        return startFillingOrReportNoFiller(context);
    }

    private Next<Phase> enqueueTargetedPit(TickContext context, BlockPos anchor) {
        String dim = site.dimension(context);
        for (CampPits.Pit pit : site.scanPits(context, anchor, TARGET_SCAN_RADIUS, false)) {
            WorldPosition at = new WorldPosition(pit.center().getX(), pit.center().getY(), pit.center().getZ(), dim);
            if (!site.cellProtected(at, input.permissions().protectedLandmarks())) {
                pitQueue.add(pit);
                break;
            }
        }
        if (pitQueue.isEmpty()) {
            return Next.done(TaskResult.done("指定位置地面已经平齐，没有深度不低于 2 格的地面坑"));
        }
        return startFillingOrReportNoFiller(context);
    }

    private Next<Phase> startFillingOrReportNoFiller(TickContext context) {
        if (site.availableFillers(context).isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "身上没有多余的泥土、圆石等回填方块（不为填坑另去挖料或翻箱）", null));
        }
        recordProgress("扫出 " + pitQueue.size() + " 处待填地面坑");
        return Next.go(Phase.NEXT_CELL, "开始逐格回填");
    }

    private Next<Phase> nextCell(TickContext context) {
        String dim = site.dimension(context);
        while (true) {
            if (currentPit == null) {
                if (pitQueue.isEmpty()) return finishSummary();
                currentPit = pitQueue.poll();
                cellIndex = 0;
                placedInCurrentPit = 0;
            }
            while (cellIndex < currentPit.fillCells().size()) {
                BlockPos candidate = currentPit.fillCells().get(cellIndex);
                WorldPosition worldCell = new WorldPosition(candidate.getX(), candidate.getY(), candidate.getZ(), dim);
                if (!site.needsFill(context, candidate)
                        || site.cellProtected(worldCell, input.permissions().protectedLandmarks())) {
                    cellIndex++;
                    continue;
                }
                List<String> fillers = site.availableFillers(context);
                // 身上扣除预留后的多余废料中途用完时，不再另去挖料或翻箱，直接结算已填部分收场。
                if (fillers.isEmpty()) {
                    fillerExhausted = true;
                    settleCurrentPit(context, false);
                    return finishSummary();
                }
                currentCell = candidate;
                currentFiller = fillers.getFirst();
                Optional<BlockPos> rim = site.rimStandFor(context, candidate, currentPit.fillCells());
                if (rim.isPresent()) {
                    prepared = site.walkTo(rim.get());
                    return Next.go(Phase.STEP_RIM, "站到坑边平地");
                }
                return enterPlacement(context);
            }
            boolean filled = currentPit.fillCells().stream().noneMatch(cell -> site.needsFill(context, cell));
            settleCurrentPit(context, filled);
        }
    }

    private Next<Phase> enterPlacement(TickContext context) {
        Optional<Action> place = site.placeFiller(context, currentCell, currentFiller);
        if (place.isEmpty()) {
            recordAttempt("回填 " + shortPos(currentCell), "旁边没有结实支撑面");
            cellIndex++;
            return Next.stay();
        }
        prepared = place.get();
        return Next.go(Phase.PLACE_CELL, "往坑里垫放 " + currentFiller);
    }

    private Next<Phase> stepRim(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (status instanceof ActionStatus.Failed) {
            recordAttempt("走到坑边 " + shortPos(currentCell), "没能站到够得着的坑沿");
            cellIndex++;
            return Next.go(Phase.NEXT_CELL, "换下一格试");
        }
        return enterPlacement(context);
    }

    private Next<Phase> placeCell(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        // 放置动作结束且目标格不再是待填干空位才算放成，没放上的格子记一次尝试后换下一格。
        boolean placed = !(status instanceof ActionStatus.Failed) && !site.needsFill(context, currentCell);
        if (placed) {
            placedInCurrentPit++;
            totalPlaced++;
            usedByBlock.merge(currentFiller, 1, Integer::sum);
            recordChange(new Change(Change.Kind.BLOCK_PLACED, currentFiller, 1, "回填地面坑"));
            recordChange(new Change(Change.Kind.ITEM_CONSUMED, currentFiller, 1, "回填地面坑"));
        } else {
            recordAttempt("回填 " + shortPos(currentCell), "这一下没放上");
        }
        cellIndex++;
        return Next.go(Phase.NEXT_CELL, "继续下一格");
    }

    private void settleCurrentPit(TickContext context, boolean allCellsFilled) {
        if (currentPit == null) return;
        String dim = site.dimension(context);
        WorldPosition center = new WorldPosition(currentPit.center().getX(), currentPit.center().getY(),
                currentPit.center().getZ(), dim);
        settledPits.add(new FillPitDetails.FilledPit(center, currentPit.depth(), currentPit.cells(),
                placedInCurrentPit, allCellsFilled));
        currentPit = null;
    }

    private Next<Phase> finishSummary() {
        if (totalPlaced == 0) {
            if (fillerExhausted) {
                return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                        "身上没有多余的泥土、圆石等回填方块（不为填坑另去挖料或翻箱）", null));
            }
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "站在坑边够不着或没有结实支撑面，一块都没填上", null));
        }
        int filledCount = (int) settledPits.stream().filter(FillPitDetails.FilledPit::filled).count();
        String usedText = usedByBlock.entrySet().stream()
                .map(e -> e.getKey() + " ×" + e.getValue())
                .reduce((a, b) -> a + "、" + b).orElse(totalPlaced + " 块");
        String summary = filledCount > 0
                ? "用现成废料（" + usedText + "）填平了 " + filledCount + " 处地面坑"
                : "用现成废料（" + usedText + "）回填了 " + totalPlaced + " 格";
        if (fillerExhausted) {
            summary += "；身上的多余泥土圆石已经用完，剩下的没有另去挖料";
        }
        return Next.done(TaskResult.done(summary));
    }

    private static String shortPos(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }

    @Override
    protected ResultDetails details() {
        int filledCount = (int) settledPits.stream().filter(FillPitDetails.FilledPit::filled).count();
        return new FillPitDetails(filledCount, totalPlaced, settledPits, fillerExhausted ? Boolean.TRUE : null);
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case LOCATE -> "查看地面坑";
            case APPROACH_TARGET -> "走向目标坑位";
            case NEXT_CELL -> "挑选回填格";
            case STEP_RIM -> "站到坑边平地";
            case PLACE_CELL -> "放置回填方块";
        };
    }
}
