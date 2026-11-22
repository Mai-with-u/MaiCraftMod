// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 2×1 双格交错安全穿沙井状态机：绝不 1×1 直挖自己脚下，角色始终站在安全格 {@code standingCol}，
 * 仅挖掘相邻的 {@code otherCol} 深 1–2 格；等上方重力方块落定重清并确认 {@code otherCol} 脚下为实心非流体方块后
 * 才跨入 {@code otherCol}，随后交替下掘另一列。两列井底均进入实岩且头顶保留 3 格实岩顶板后平滑切回标准阶梯；
 * 若中途暴露空腔或流体则立即安全中止并停留在当前安全格，绝不坠落。
 */
final class SandShaftDigging implements Action {

    /** 穿沙井结束方式：顺利进入实岩下 3 格交还标准阶梯，或中途遇险/受阻安全中止。 */
    enum ShaftExit { HANDOFF_TO_STAIRS, ABORTED_HAZARD }

    private enum Stage { CHECK_CUT, DIG_OTHER, SETTLE_FALL, STEP_ACROSS, PICK_UP }

    /** 每次交替在相邻列下掘的最大落差（格）：跨入无摔落伤害且在触及距离内。 */
    private static final int STEP_DROP = 2;

    private final ClientStairsDown.Site site;
    private final DigsBlocks digs;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;
    private final Permissions permissions;
    private final SandShaftPlan plan;
    private final Predicate<String> yields;
    private final int wantedCells;
    private final Predicate<BlockPos> reached;
    private final DigsStairsDown.Tunnel tunnel;
    private final Predicate<BlockPos> forbiddenCell;
    private final Supplier<DigsBlocks.ToolPrep> toolPrep;

    private BlockPos standingCol;
    private BlockPos otherCol;
    private BlockPos feet;
    private Stage stage = Stage.CHECK_CUT;
    private Action step;
    private int cutTopY;
    private int cutBottomY;
    private boolean stepAfterCut;
    private BlockPos diggingCell;
    private String diggingType;
    private boolean diggingYields;
    private int digTicks;
    private int digsThisCut;
    private boolean gravityPending;
    private boolean settledThisCut;
    private int settleWaitTicks;
    private int stepRetries;
    private BlockPos stepTarget;
    private Set<Integer> dropsBefore = Set.of();
    private int yielded;
    private final Map<String, Integer> dugByType = new LinkedHashMap<>();
    private ShaftExit exit;
    private boolean stopping;
    private boolean paused;

    SandShaftDigging(ClientStairsDown.Site site, DigsBlocks digs, PicksUpDrops drops, PermissionCheck permission,
            Permissions permissions, SandShaftPlan plan, Predicate<String> yields, int wantedCells,
            Predicate<BlockPos> reached, DigsStairsDown.Tunnel tunnel, Predicate<BlockPos> forbiddenCell,
            Supplier<DigsBlocks.ToolPrep> toolPrep) {
        this.site = site;
        this.digs = digs;
        this.drops = drops;
        this.permission = permission;
        this.permissions = permissions;
        this.plan = plan;
        this.yields = yields;
        this.wantedCells = wantedCells;
        this.reached = reached;
        this.tunnel = tunnel;
        this.forbiddenCell = forbiddenCell;
        this.toolPrep = toolPrep;
        this.standingCol = plan.colA();
        this.otherCol = plan.colB();
    }

    ShaftExit exit() {
        return exit;
    }

    int yieldedCount() {
        return yielded;
    }

    Map<String, Integer> dugByType() {
        return dugByType;
    }

    SandShaftPlan plan() {
        return plan;
    }

    /** 中途遇险时正在下掘的相邻列（用于排除坏井位重选）。 */
    BlockPos abortedColumn() {
        return otherCol;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        if (exit != null) return ActionStatus.done();
        if (stopping) {
            if (step != null && !step.stoppedSafely()) step.tick(context);
            return ActionStatus.running();
        }
        if (paused) {
            paused = false;
            closeStep();
            digTicks = 0;
            stage = Stage.CHECK_CUT;
        }
        if (step != null) return advanceStep(context);
        return switch (stage) {
            case CHECK_CUT -> checkCut(context);
            case DIG_OTHER -> digOther(context);
            case SETTLE_FALL -> settleFall(context);
            case STEP_ACROSS, PICK_UP -> throw new IllegalStateException("跨格或拾取阶段缺少子动作");
        };
    }

    // 推进当前正在进行的子动作（单格挖掘、跨入另一列、捡掉落物）。
    private ActionStatus advanceStep(TickContext context) {
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running running) {
            if (running.progressed()) {
                digTicks = 0;
            } else if (stage == Stage.DIG_OTHER && ++digTicks > DigsBlocks.DIG_PATIENCE_TICKS) {
                closeStep();
                digTicks = 0;
                return finish(ShaftExit.ABORTED_HAZARD);
            }
            return status;
        }
        digTicks = 0;
        closeStep();
        return switch (stage) {
            case DIG_OTHER -> afterDig(status);
            case STEP_ACROSS -> afterStep(context, status);
            case PICK_UP -> {
                stage = Stage.CHECK_CUT;
                yield ActionStatus.progressed();
            }
            default -> throw new IllegalStateException("意外的子动作阶段：" + stage);
        };
    }

    // 每轮下掘前核对角色位置与目标深度：已达标或两列井底均落入实岩下 3 格时平滑切入阶梯。
    private ActionStatus checkCut(TickContext context) {
        if (!site.onGround(context)) return ActionStatus.running();
        Optional<BlockPos> standing = site.feet(context);
        if (standing.isEmpty()) return ActionStatus.running();
        feet = standing.get();
        if (reachedEarlyStop()) return finish(ShaftExit.HANDOFF_TO_STAIRS);
        if (!alignStandingColumn(feet)) return finish(ShaftExit.ABORTED_HAZARD);
        if (feet.getY() <= plan.targetFeetY()) {
            // 当前站立列已到目标高度：若另一列也已齐平且两列井底均为实岩则完成穿沙井，否则把另一列补挖齐平再交接。
            if (columnOpen(context, otherCol, plan.targetFeetY(), feet.getY() + 1)) {
                return bothFloorsSolidRock(context, plan.targetFeetY() - 1)
                        ? finish(ShaftExit.HANDOFF_TO_STAIRS) : finish(ShaftExit.ABORTED_HAZARD);
            }
            prepareCut(feet.getY() + 1, plan.targetFeetY(), false);
            return ActionStatus.progressed();
        }
        int nextBottom = Math.max(plan.targetFeetY(), feet.getY() - STEP_DROP);
        prepareCut(feet.getY() + 1, nextBottom, true);
        return ActionStatus.progressed();
    }

    private void prepareCut(int topY, int bottomY, boolean stepAfter) {
        cutTopY = topY;
        cutBottomY = bottomY;
        stepAfterCut = stepAfter;
        digsThisCut = 0;
        stepRetries = 0;
        gravityPending = false;
        settledThisCut = false;
        settleWaitTicks = 0;
        dropsBefore = drops.nearby();
        stage = Stage.DIG_OTHER;
    }

    // 始终站在 standingCol 从上往下挖 otherCol：绝不挖自己脚下，每格动工前复核流体、空腔与许可。
    private ActionStatus digOther(TickContext context) {
        for (int y = cutTopY; y >= cutBottomY; y--) {
            BlockPos cell = new BlockPos(otherCol.getX(), y, otherCol.getZ());
            ClientStairsDown.Ground ground = site.ground(context, cell);
            if (ground == ClientStairsDown.Ground.OPEN) continue;
            if (ground != ClientStairsDown.Ground.DIGGABLE || site.hurtsToStandOn(context, cell)) {
                return finish(ShaftExit.ABORTED_HAZARD);
            }
            if (SandShaftPlan.touchesFluidAround(site, context, cell) || forbiddenCell.test(cell)) {
                return finish(ShaftExit.ABORTED_HAZARD);
            }
            if (digsThisCut >= ClientStairsDown.MAX_GRAVITY_DIGS_PER_STEP) {
                return finish(ShaftExit.ABORTED_HAZARD);
            }
            String blockType = site.blockType(context, cell);
            WorldPosition where = new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), site.dimension(context));
            if (permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK, where, blockType).isPresent()) {
                return finish(ShaftExit.ABORTED_HAZARD);
            }
            // 开挖前拍下上方重力方块或下落实体快照：单层沙砾转实体瞬间顶部变空气，靠快照触发落定等待。
            if (site.fallingBlock(context, cell.above()) || site.hasFallingEntityAbove(context, cell)) {
                gravityPending = true;
            }
            Optional<Action> dig = digs.dig(cell, toolPrep.get());
            if (dig.isEmpty()) return finish(ShaftExit.ABORTED_HAZARD);
            step = dig.get();
            diggingCell = cell;
            diggingType = blockType;
            diggingYields = yields == null || yields.test(blockType);
            digTicks = 0;
            digsThisCut++;
            settledThisCut = false;
            return ActionStatus.progressed();
        }
        return finishOrStepOther(context);
    }

    // otherCol 本轮切深已全部挖空：若上方仍有重力方块/实体则先原地等落定，再核验落脚底面实心无流体后跨入。
    private ActionStatus finishOrStepOther(TickContext context) {
        BlockPos topCell = new BlockPos(otherCol.getX(), cutTopY, otherCol.getZ());
        if (!settledThisCut
                && (gravityPending || site.fallingBlock(context, topCell.above())
                || site.hasFallingEntityAbove(context, topCell))) {
            stage = Stage.SETTLE_FALL;
            settleWaitTicks = 0;
            return ActionStatus.running();
        }
        BlockPos landing = new BlockPos(otherCol.getX(), cutBottomY, otherCol.getZ());
        if (!safeLanding(context, landing)) return finish(ShaftExit.ABORTED_HAZARD);
        if (!stepAfterCut) {
            return bothFloorsSolidRock(context, plan.targetFeetY() - 1)
                    ? finish(ShaftExit.HANDOFF_TO_STAIRS) : finish(ShaftExit.ABORTED_HAZARD);
        }
        stepTarget = landing;
        stage = Stage.STEP_ACROSS;
        step = site.stepTo(stepTarget);
        return ActionStatus.progressed();
    }

    // 原地等待上方重力方块落定：窗内一旦塌落填回 otherCol 立即继续清理，等满仍空才允许跨入。
    private ActionStatus settleFall(TickContext context) {
        for (int y = cutTopY; y >= cutBottomY; y--) {
            BlockPos cell = new BlockPos(otherCol.getX(), y, otherCol.getZ());
            if (site.ground(context, cell) != ClientStairsDown.Ground.OPEN) {
                gravityPending = false;
                settledThisCut = false;
                stage = Stage.DIG_OTHER;
                return digOther(context);
            }
        }
        BlockPos topCell = new BlockPos(otherCol.getX(), cutTopY, otherCol.getZ());
        boolean stillFalling = site.hasFallingEntityAbove(context, topCell);
        if (++settleWaitTicks >= ClientStairsDown.GRAVITY_SETTLE_TICKS
                && (!stillFalling || settleWaitTicks >= ClientStairsDown.GRAVITY_SETTLE_TICKS * 2)) {
            gravityPending = false;
            settledThisCut = true;
            stage = Stage.DIG_OTHER;
            return finishOrStepOther(context);
        }
        return ActionStatus.running();
    }

    private ActionStatus afterDig(ActionStatus status) {
        if (status instanceof ActionStatus.Failed) return finish(ShaftExit.ABORTED_HAZARD);
        dugByType.merge(diggingType, 1, Integer::sum);
        if (diggingYields) yielded++;
        return ActionStatus.progressed();
    }

    // 跨入 otherCol：若走步失败且落脚格或头顶格被落沙回填，退回 DIG_OTHER 重挖（最多 3 次）；成功则交换安全站立列。
    private ActionStatus afterStep(TickContext context, ActionStatus status) {
        if (status instanceof ActionStatus.Failed) {
            boolean refilled = site.ground(context, stepTarget) == ClientStairsDown.Ground.DIGGABLE
                    || site.ground(context, stepTarget.above()) == ClientStairsDown.Ground.DIGGABLE;
            if (refilled && stepRetries < ClientStairsDown.MAX_STEP_DIG_RETRIES) {
                stepRetries++;
                stage = Stage.DIG_OTHER;
                return ActionStatus.progressed();
            }
            return finish(ShaftExit.ABORTED_HAZARD);
        }
        BlockPos previous = standingCol;
        standingCol = otherCol;
        otherCol = previous;
        stage = Stage.PICK_UP;
        step = drops.pickUpNewSince(dropsBefore);
        return ActionStatus.progressed();
    }

    // 跨入前核验目标脚下必须是实心可挖非流体且不烫脚的方块，且落脚两格四周无水/岩浆，避免坠入突发洞穴或岩浆。
    private boolean safeLanding(TickContext context, BlockPos landing) {
        BlockPos floor = landing.below();
        if (site.ground(context, floor) != ClientStairsDown.Ground.DIGGABLE || site.hurtsToStandOn(context, floor)) {
            return false;
        }
        return !SandShaftPlan.touchesFluidAround(site, context, landing)
                && !SandShaftPlan.touchesFluidAround(site, context, landing.above());
    }

    private boolean bothFloorsSolidRock(TickContext context, int floorY) {
        BlockPos floorA = new BlockPos(plan.colA().getX(), floorY, plan.colA().getZ());
        BlockPos floorB = new BlockPos(plan.colB().getX(), floorY, plan.colB().getZ());
        return site.solidRock(context, floorA) && site.solidRock(context, floorB);
    }

    private boolean columnOpen(TickContext context, BlockPos col, int bottomY, int topY) {
        for (int y = bottomY; y <= topY; y++) {
            if (site.ground(context, new BlockPos(col.getX(), y, col.getZ())) != ClientStairsDown.Ground.OPEN) {
                return false;
            }
        }
        return true;
    }

    private boolean reachedEarlyStop() {
        if (reached != null && reached.test(feet)) return true;
        if (reached == null && wantedCells > 0 && yielded >= wantedCells) return true;
        return tunnel != null && (feet.getY() <= tunnel.targetY() || tunnel.inSight().test(feet));
    }

    private boolean alignStandingColumn(BlockPos pos) {
        if (sameColumn(pos, plan.colA())) {
            standingCol = plan.colA();
            otherCol = plan.colB();
            return true;
        }
        if (sameColumn(pos, plan.colB())) {
            standingCol = plan.colB();
            otherCol = plan.colA();
            return true;
        }
        return false;
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private ActionStatus finish(ShaftExit reason) {
        closeStep();
        exit = reason;
        return ActionStatus.done();
    }

    private void closeStep() {
        if (step != null) {
            step.close();
            step = null;
        }
    }

    @Override
    public void pause() {
        stopping = false;
        if (step != null) step.pause();
        paused = true;
    }

    @Override
    public boolean stopSafely() {
        stopping = step != null && step.stopSafely();
        return stopping;
    }

    @Override
    public boolean stoppedSafely() {
        return !stopping || step == null || step.stoppedSafely();
    }

    @Override
    public void close() {
        closeStep();
    }

    @Override
    public Interruptibility interruptibility() {
        return step == null ? Interruptibility.BETWEEN_ACTIONS : step.interruptibility();
    }

    @Override
    public String describe() {
        return "2×1 安全穿沙井下掘（目标实岩脚高 Y=" + plan.targetFeetY() + "）";
    }
}
