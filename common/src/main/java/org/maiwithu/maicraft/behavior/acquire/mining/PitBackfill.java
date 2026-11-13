// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采掘收工前的坑口核查与现成废料回填：只盯本次任务亲手挖过的地面格子，绝不碰别人挖的工程坑、水坑或灌溉沟。
 *
 * <p>如果在平地往下挖出了深度不低于 2 格的竖坑或浅台阶入口（4 格深以内），收工前看身上有没有扣除保留需求后多出来的
 * 泥土、圆石这类次要方块（不为回填另找材料）：有就走回坑边把入口顶层封平，最多封 4 格避免拖慢取材；
 * 没有多余方块或台阶已经深入地下，就不往返奔波，如实把坑口坐标、深度与没填的原因记进 {@link DugPits}。
 */
public final class PitBackfill {

    /** 浅坑收工顺手封口的最大深度：再深的台阶已经下到地下矿层，留着台阶口供上下，不跑远路回地面封口。 */
    static final int SHALLOW_BACKFILL_MAX_DEPTH = 4;
    /** 一次收工最多放几块废料封口，保证回填耗时只占一两秒。 */
    static final int MAX_CAP_BLOCKS = 4;
    /** 走回浅坑边封口的耐心上限（刻）：超时就放弃封口如实记账，不卡住主任务。 */
    private static final int WALK_BACK_PATIENCE_TICKS = 80;

    private PitBackfill() {}

    /** 包装往下挖楼梯接缝：为取材开挖地面台阶或通道时，跟踪地面入口并在收工前尝试封平浅坑、写入坑位账本。 */
    public static DigsStairsDown wrapStairs(DigsStairsDown inner, ClientStairsDown.Site site, DugPits dugPits) {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(dugPits, "dugPits");
        return new DigsStairsDown() {
            @Override
            public Action digDown(Predicate<String> yields, int wantedCells, Permissions permissions) {
                return new StairsPitAction(inner.digDown(yields, wantedCells, permissions), site, dugPits);
            }

            @Override
            public Optional<Action> digDown(Predicate<BlockPos> reached, int plannedCells,
                    Permissions permissions, TaskRecords records) {
                return inner.digDown(reached, plannedCells, permissions, records);
            }

            @Override
            public Optional<Action> tunnelToward(Tunnel tunnel, Permissions permissions) {
                return inner.tunnelToward(tunnel, permissions)
                        .map(action -> new StairsPitAction(action, site, dugPits));
            }
        };
    }

    /** 包装地表成批采掘动作：跟踪本次批次在地面层挖开的列，若挖出深度不低于 2 格的竖坑则在收工前用多余废料封口并记账。 */
    public static Action wrapSurfaceBatch(Action collectAction, List<BlockPos> batchCells,
            ClientStairsDown.Site site, DugPits dugPits) {
        Objects.requireNonNull(collectAction, "collectAction");
        if (site == null || dugPits == null || batchCells.isEmpty()) return collectAction;
        return new BatchPitAction(collectAction, batchCells, site, dugPits);
    }

    /** 去掉命名空间前缀拼成可读的中文/简短方块名，用在回填说明里。 */
    static String shortBlockName(String fillerId) {
        return switch (fillerId) {
            case "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt" -> "泥土";
            case "minecraft:cobblestone" -> "圆石";
            case "minecraft:cobbled_deepslate" -> "深板岩圆石";
            case "minecraft:stone" -> "石头";
            case "minecraft:netherrack" -> "下界岩";
            default -> fillerId.startsWith("minecraft:") ? fillerId.substring("minecraft:".length()) : fillerId;
        };
    }

    /** 往下挖楼梯或长程台阶入口的跟踪与收工封口动作。 */
    private static final class StairsPitAction implements Action {
        private enum Stage { INNER, WALK_RIM, PLACE_CAP, DONE }

        private final Action inner;
        private final ClientStairsDown.Site site;
        private final DugPits dugPits;
        private Stage stage = Stage.INNER;
        /** 角色在地面上站定准备开挖的那一格，以及它四周紧邻方向地面以下原本实心的深度快照。 */
        private BlockPos surfaceStance;
        private final Map<Direction, Integer> solidBeforeBySide = new LinkedHashMap<>();
        private BlockPos entryCell;
        private int lowestFeetY = Integer.MAX_VALUE;
        private ActionStatus finalStatus;
        private Action cleanupStep;
        private int walkTicks;
        private String usedFiller;
        private int recordedDepth;

        StairsPitAction(Action inner, ClientStairsDown.Site site, DugPits dugPits) {
            this.inner = inner;
            this.site = site;
            this.dugPits = dugPits;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (stage == Stage.DONE) return finalStatus;
            if (stage == Stage.INNER) {
                observeStairs(context);
                ActionStatus status = inner.tick(context);
                if (status instanceof ActionStatus.Running) return status;
                observeStairs(context);
                finalStatus = status;
                return beginStairsCleanup(context);
            }
            return tickStairsCleanup(context);
        }

        // 记录角色在地面站立开挖时的位置与四周原本实心的地面格；走出营地后再开挖时会随新的地面站位刷新快照。
        private void observeStairs(TickContext context) {
            Optional<BlockPos> now = site.feet(context);
            if (now.isEmpty()) return;
            BlockPos feet = now.get();
            if (entryCell == null) {
                if (surfaceStance != null && feet.getY() < surfaceStance.getY()
                        && Math.abs(feet.getX() - surfaceStance.getX()) + Math.abs(feet.getZ() - surfaceStance.getZ()) == 1) {
                    // 刚从地面站位迈进往下挖开的第一级台阶：锁定地面站位与第一格坑口。
                    entryCell = new BlockPos(feet.getX(), surfaceStance.getY() - 1, feet.getZ());
                    lowestFeetY = feet.getY();
                } else if (onSurface(context, feet) && !feet.equals(surfaceStance)) {
                    snapshotAroundSurface(context, feet);
                }
            } else {
                lowestFeetY = Math.min(lowestFeetY, feet.getY());
            }
        }

        private boolean onSurface(TickContext context, BlockPos feet) {
            OptionalInt surface = site.surfaceY(context, feet);
            return surface.isEmpty() || feet.getY() >= surface.getAsInt() - 1;
        }

        private void snapshotAroundSurface(TickContext context, BlockPos feet) {
            surfaceStance = feet;
            lowestFeetY = feet.getY();
            solidBeforeBySide.clear();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos ahead = feet.relative(dir);
                int solidDepth = 0;
                for (int d = 1; d <= SHALLOW_BACKFILL_MAX_DEPTH; d++) {
                    if (site.ground(context, ahead.below(d)) == ClientStairsDown.Ground.DIGGABLE) {
                        solidDepth = d;
                    } else {
                        break;
                    }
                }
                if (solidDepth >= 2) solidBeforeBySide.put(dir, solidDepth);
            }
        }

        // 楼梯收场时核对地面入口有没有被挖出 >= 2 格深的坑：包括迈下去的和站在地面把前面掏深后停工的两种情形。
        private ActionStatus beginStairsCleanup(TickContext context) {
            if (entryCell == null && surfaceStance != null) {
                detectUnsteppedEntry(context);
            }
            if (entryCell == null || surfaceStance == null) {
                stage = Stage.DONE;
                return finalStatus;
            }
            int openDepth = openDepthAtEntry(context);
            recordedDepth = Math.max(surfaceStance.getY() - lowestFeetY, openDepth);
            if (recordedDepth < 2) {
                stage = Stage.DONE;
                return finalStatus;
            }
            List<String> fillers = site.fillers(context);
            if (recordedDepth > SHALLOW_BACKFILL_MAX_DEPTH) {
                recordPit(context, false, "台阶已下到地下深处，留着可走上来的台阶口");
                stage = Stage.DONE;
                return finalStatus;
            }
            if (fillers.isEmpty()) {
                recordPit(context, false, "身上没有多余的泥土或圆石，留着可走上来的台阶口");
                stage = Stage.DONE;
                return finalStatus;
            }
            usedFiller = fillers.getFirst();
            BlockPos currentFeet = site.feet(context).orElse(surfaceStance);
            if (!currentFeet.equals(surfaceStance)) {
                cleanupStep = site.stepTo(surfaceStance);
                stage = Stage.WALK_RIM;
                return ActionStatus.progressed();
            }
            return startCapPlacement(context);
        }

        private void detectUnsteppedEntry(TickContext context) {
            for (Map.Entry<Direction, Integer> side : solidBeforeBySide.entrySet()) {
                BlockPos ahead = surfaceStance.relative(side.getKey());
                int dugDepth = 0;
                for (int d = 1; d <= side.getValue(); d++) {
                    if (site.ground(context, ahead.below(d)) == ClientStairsDown.Ground.OPEN) {
                        dugDepth = d;
                    } else {
                        break;
                    }
                }
                if (dugDepth >= 2) {
                    entryCell = ahead.below();
                    lowestFeetY = Math.min(lowestFeetY, surfaceStance.getY() - dugDepth);
                    return;
                }
            }
        }

        private int openDepthAtEntry(TickContext context) {
            int depth = 0;
            for (int d = 0; d < SHALLOW_BACKFILL_MAX_DEPTH; d++) {
                if (site.ground(context, entryCell.below(d)) == ClientStairsDown.Ground.OPEN) {
                    depth = d + 1;
                } else {
                    break;
                }
            }
            return depth;
        }

        private ActionStatus startCapPlacement(TickContext context) {
            if (site.ground(context, entryCell) != ClientStairsDown.Ground.OPEN) {
                recordPit(context, true, "台阶入口已经平齐");
                stage = Stage.DONE;
                return finalStatus;
            }
            Optional<Action> place = site.placeFiller(context, entryCell, usedFiller);
            if (place.isEmpty()) {
                recordPit(context, false, "旁边没有能对着垫的实地，留着可走上来的台阶口");
                stage = Stage.DONE;
                return finalStatus;
            }
            cleanupStep = place.get();
            stage = Stage.PLACE_CAP;
            return ActionStatus.progressed();
        }

        private ActionStatus tickStairsCleanup(TickContext context) {
            ActionStatus status = cleanupStep.tick(context);
            if (stage == Stage.WALK_RIM && status instanceof ActionStatus.Running
                    && ++walkTicks > WALK_BACK_PATIENCE_TICKS) {
                closeCleanup();
                recordPit(context, false, "没能走回地面入口边，留着可走上来的台阶口");
                stage = Stage.DONE;
                return finalStatus;
            }
            if (status instanceof ActionStatus.Running) return status;
            closeCleanup();
            if (stage == Stage.WALK_RIM) {
                if (status instanceof ActionStatus.Failed) {
                    recordPit(context, false, "没能走回地面入口边，留着可走上来的台阶口");
                    stage = Stage.DONE;
                    return finalStatus;
                }
                return startCapPlacement(context);
            }
            boolean capped = !(status instanceof ActionStatus.Failed)
                    && site.ground(context, entryCell) != ClientStairsDown.Ground.OPEN;
            recordPit(context, capped, capped
                    ? "收工前用" + shortBlockName(usedFiller) + "把台阶入口封平了"
                    : "尝试封口未成，留着可走上来的台阶口");
            stage = Stage.DONE;
            return finalStatus;
        }

        private void recordPit(TickContext context, boolean filled, String note) {
            dugPits.record(new DugPits.Pit(new WorldPosition(
                    entryCell.getX(), entryCell.getY(), entryCell.getZ(), site.dimension(context)),
                    recordedDepth, filled, note));
        }

        private void closeCleanup() {
            if (cleanupStep != null) {
                cleanupStep.close();
                cleanupStep = null;
            }
        }

        @Override public void pause() {
            inner.pause();
            if (cleanupStep != null) cleanupStep.pause();
        }

        @Override public void close() {
            inner.close();
            closeCleanup();
        }

        @Override public Interruptibility interruptibility() {
            return cleanupStep != null ? cleanupStep.interruptibility() : inner.interruptibility();
        }

        @Override public String describe() {
            return stage == Stage.INNER ? inner.describe() : "回填刚挖开的台阶入口";
        }
    }

    /** 地表成批采掘的竖坑检测与收工封口动作。 */
    private static final class BatchPitAction implements Action {
        private enum Stage { COLLECT, WALK_OUT, CAP, DONE }

        private final Action collect;
        private final List<BlockPos> batchCells;
        private final ClientStairsDown.Site site;
        private final DugPits dugPits;
        /** 开工前记录本次批次涉及的每一列地面顶层格高度（只记原本顶上露空的地面列）。 */
        private final Map<Long, BlockPos> surfaceTopByCol = new LinkedHashMap<>();
        private boolean snapshotted;
        private Stage stage = Stage.COLLECT;
        private ActionStatus finalStatus;
        private List<BlockPos> capsToPlace = List.of();
        private int capIndex;
        private BlockPos pitAnchor;
        private int maxDepth;
        private String usedFiller;
        private Action cleanupStep;
        private int walkTicks;

        BatchPitAction(Action collect, List<BlockPos> batchCells, ClientStairsDown.Site site, DugPits dugPits) {
            this.collect = collect;
            this.batchCells = batchCells;
            this.site = site;
            this.dugPits = dugPits;
        }

        @Override
        public ActionStatus tick(TickContext context) {
            if (stage == Stage.DONE) return finalStatus;
            if (!snapshotted) {
                snapshotBatchColumns(context);
                snapshotted = true;
            }
            if (stage == Stage.COLLECT) {
                ActionStatus status = collect.tick(context);
                if (status instanceof ActionStatus.Running) return status;
                finalStatus = status;
                return inspectDugColumns(context);
            }
            return tickBatchCleanup(context);
        }

        private void snapshotBatchColumns(TickContext context) {
            for (BlockPos cell : batchCells) {
                long col = colKey(cell.getX(), cell.getZ());
                BlockPos existing = surfaceTopByCol.get(col);
                if (existing != null && existing.getY() >= cell.getY()) continue;
                if (site.ground(context, cell) == ClientStairsDown.Ground.DIGGABLE
                        && site.ground(context, cell.above()) == ClientStairsDown.Ground.OPEN) {
                    surfaceTopByCol.put(col, cell.immutable());
                }
            }
        }

        // 收完掉落物后看刚才挖过的地面列：只有比紧邻外侧地面低至少 2 格（一步跳不上来）的真竖坑才回填或记账；削平山坡不算坑。
        private ActionStatus inspectDugColumns(TickContext context) {
            List<BlockPos> deepCols = new ArrayList<>();
            for (BlockPos top : surfaceTopByCol.values()) {
                int drop = pitDropAt(context, top);
                if (drop >= 2) {
                    deepCols.add(top);
                    if (pitAnchor == null) pitAnchor = top;
                    maxDepth = Math.max(maxDepth, drop);
                }
            }
            if (deepCols.isEmpty()) {
                stage = Stage.DONE;
                return finalStatus;
            }
            List<String> fillers = site.fillers(context);
            if (fillers.isEmpty()) {
                recordBatchPit(context, false, "身上没有多余的泥土或圆石，没法回填");
                stage = Stage.DONE;
                return finalStatus;
            }
            usedFiller = fillers.getFirst();
            capsToPlace = deepCols.subList(0, Math.min(deepCols.size(), MAX_CAP_BLOCKS));
            Optional<BlockPos> nowFeet = site.feet(context);
            if (nowFeet.isPresent() && inColumns(nowFeet.get(), capsToPlace)) {
                Optional<BlockPos> rim = pickRimStand(context, capsToPlace);
                if (rim.isPresent()) {
                    cleanupStep = site.stepTo(rim.get());
                    stage = Stage.WALK_OUT;
                    return ActionStatus.progressed();
                }
            }
            stage = Stage.CAP;
            return nextCap(context);
        }

        // 这一列从原地面往下空了几格，并且四周都没有只高一格就能迈上去的缓坡时才算掉人的竖坑。
        private int pitDropAt(TickContext context, BlockPos top) {
            int open = 0;
            for (int d = 0; d <= SHALLOW_BACKFILL_MAX_DEPTH + 2; d++) {
                if (site.ground(context, top.below(d)) == ClientStairsDown.Ground.OPEN) {
                    open = d + 1;
                } else {
                    break;
                }
            }
            if (open < 2) return 0;
            int floorFeetY = top.getY() - open + 1;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos sideFeet = new BlockPos(top.getX() + dir.getStepX(), floorFeetY, top.getZ() + dir.getStepZ());
                if (site.standable(context, sideFeet) || site.standable(context, sideFeet.above())) {
                    return 0;
                }
            }
            return open;
        }

        private ActionStatus tickBatchCleanup(TickContext context) {
            ActionStatus status = cleanupStep.tick(context);
            if (stage == Stage.WALK_OUT && status instanceof ActionStatus.Running
                    && ++walkTicks > WALK_BACK_PATIENCE_TICKS) {
                closeCleanup();
                recordBatchPit(context, false, "站在坑里没能走上坑边封口");
                stage = Stage.DONE;
                return finalStatus;
            }
            if (status instanceof ActionStatus.Running) return status;
            closeCleanup();
            if (stage == Stage.WALK_OUT) {
                if (status instanceof ActionStatus.Failed) {
                    recordBatchPit(context, false, "站在坑里没能走上坑边封口");
                    stage = Stage.DONE;
                    return finalStatus;
                }
                stage = Stage.CAP;
                return nextCap(context);
            }
            capIndex++;
            return nextCap(context);
        }

        private ActionStatus nextCap(TickContext context) {
            while (capIndex < capsToPlace.size()) {
                BlockPos cap = capsToPlace.get(capIndex);
                if (site.ground(context, cap) != ClientStairsDown.Ground.OPEN) {
                    capIndex++;
                    continue;
                }
                List<String> fillers = site.fillers(context);
                if (fillers.isEmpty()) break;
                usedFiller = fillers.getFirst();
                Optional<Action> place = site.placeFiller(context, cap, usedFiller);
                if (place.isEmpty()) {
                    capIndex++;
                    continue;
                }
                cleanupStep = place.get();
                return ActionStatus.progressed();
            }
            boolean anyRemainingDeep = capsToPlace.stream().anyMatch(cap -> pitDropAt(context, cap) >= 2);
            recordBatchPit(context, !anyRemainingDeep, !anyRemainingDeep
                    ? "收工前用" + shortBlockName(usedFiller) + "把坑口封平了"
                    : "多余的次要方块不够封完整个坑口");
            stage = Stage.DONE;
            return finalStatus;
        }

        private void recordBatchPit(TickContext context, boolean filled, String note) {
            dugPits.record(new DugPits.Pit(new WorldPosition(
                    pitAnchor.getX(), pitAnchor.getY(), pitAnchor.getZ(), site.dimension(context)),
                    maxDepth, filled, note));
        }

        private static boolean inColumns(BlockPos feet, List<BlockPos> cols) {
            for (BlockPos col : cols) {
                if (col.getX() == feet.getX() && col.getZ() == feet.getZ()) return true;
            }
            return false;
        }

        private Optional<BlockPos> pickRimStand(TickContext context, List<BlockPos> cols) {
            for (BlockPos col : cols) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos rim = col.relative(dir).above();
                    if (!inColumns(rim, cols) && site.standable(context, rim)) return Optional.of(rim);
                }
            }
            return Optional.empty();
        }

        private static long colKey(int x, int z) {
            return (((long) x) << 32) | (z & 0xFFFFFFFFL);
        }

        private void closeCleanup() {
            if (cleanupStep != null) {
                cleanupStep.close();
                cleanupStep = null;
            }
        }

        @Override public void pause() {
            collect.pause();
            if (cleanupStep != null) cleanupStep.pause();
        }

        @Override public void close() {
            collect.close();
            closeCleanup();
        }

        @Override public Interruptibility interruptibility() {
            return cleanupStep != null ? cleanupStep.interruptibility() : collect.interruptibility();
        }

        @Override public String describe() {
            return stage == Stage.COLLECT ? collect.describe() : "回填刚挖出的地面竖坑";
        }
    }
}
