// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.acquire.mining.DigsBlocks;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.permission.LowValueTerrainBlocks;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.interaction.DigTool;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 清障：要清的格从上往下，一格一格问许可、走过去、清掉。受保护的格记下来不动，挖不动的记做不了；
 * 清掉一格算一次真实进展。掉落物等角色自己捡，这里不管。
 * 走到跟前的路一次没走通先换个站位再走近（一格最多换两次），还是不行就先清别的格，
 * 等情况变了（人站高了一层、旁边清出了能走的路）再回头，什么都没变才按走不到交代这一格。
 * 挖格没有换面、垫台阶垫链那些办法（那是放格的），走到跟前只有站位可换。
 * 走到跟前后怎么清：模组工具认领的方块整块拆下；其余按连锁挖一格挖——装了连锁挖时对准的那一面 3×3
 * 每一格都是本来就要清的格、都过许可才整片一起挖，不然只挖这一格。
 */
final class ClearingWork extends ConstructionWork {

    private enum Step { APPROACH, DIG, DISMANTLE, SETTLE_FALL }

    /** 同一格"走不到跟前"最多换几次站位重新走近。 */
    private static final int MAX_RESPOTS = 2;
    /** 挖掉下方方块后，若上方压着重力方块，最多等几刻确认它有没有塌下来重占原格（覆盖实体化调度+1格自由落体+同步）。 */
    private static final int GRAVITY_SETTLE_TICKS = 12;
    /** 同一格因为上方重力方块连环塌落最多连续重清几次，防止无限砂塔死循环。 */
    private static final int MAX_GRAVITY_RECLEARS = 16;

    private final Permissions permissions;
    /** 目标里点名的挖方块工具：清障挖方块时只用那一件，身上没有就直接失败；null 自动挑。 */
    private final DigTool digTool;
    private final Deque<PlannedCell> queue;
    /** 走近换过站位还是走不到的格先记在这里，等情况变了再回头；什么都没变就按走不到交代。 */
    private final DeferredCells deferred = new DeferredCells();
    /** 现在第几刻：回头的格拿它判断情况变没变。 */
    private long now;
    /** 这一格走近失败后已经换过几次站位。 */
    private int respots;
    private PlannedCell cell;
    private Step step;
    private BlockState before;
    /** 挖之前触发格四周一圈里还排着要清的格和各自方块状态：连锁挖顺带挖掉的，挖完逐格复查结算并分级判断归属。 */
    private Map<BlockPos, BlockState> around = Map.of();
    /** 同一清障任务中拆掉的别人放在家里的低价值天然地形：≤3 格静默豁免归属说明，≥4 格收尾合并汇总 1 条。 */
    private final Map<String, Map<BlockPos, LowValueCleared>> lowValueOwned = new LinkedHashMap<>();

    private record LowValueCleared(BlockPos pos, String blockId, String prefix) {}
    /** 这一格挑的是一整片的中心：对准它的顶面挖，连锁的 3×3 正好是这一片；不是为 null，哪面看得见挖哪面。 */
    private Direction chainFace;
    /** 挖到一半被自卫、吃饭这类事叫走过：人已离了跟前，回来先重新走近再挖。 */
    private boolean interrupted;
    /** 挖完后等上方重力方块落定已过去几刻。 */
    private int settleWaitTicks;
    /** 开挖前正上方是否压着重力方块：单层沙子被挖后约第 2 刻转为下落实体、上方瞬间变空气，必须凭开挖前快照进入等待。 */
    private boolean hadFallingAbove;
    /** 这一格因上方重力方块塌落已经连续重清了几次。 */
    private int gravityReclears;

    ClearingWork(ConstructionServices services, ConstructionSite site, TaskRecords records, Permissions permissions,
            List<PlannedCell> cells, DigTool digTool) {
        super(services, site, records);
        this.permissions = permissions;
        this.digTool = digTool;
        this.queue = new ArrayDeque<>(cells);
    }

    @Override protected ActionStatus advance(TickContext context) {
        now = context.gameTick();
        if (cell == null) return nextCell();
        if (step == Step.APPROACH) {
            // 挖到一半被叫走回来过：先重新走到这一格跟前再出手，不隔着老远接着挖
            // （挖掘射线够不着，一格吃满施工的停滞闸）。
            if (interrupted) {
                interrupted = false;
                begin(services.close().toward(digTarget(), permissions),
                        "走回 " + cell.pos().toShortString() + " 跟前");
                return ActionStatus.progressed();
            }
            return clear();
        }
        if (step == Step.SETTLE_FALL) return waitGravitySettle();
        return settle();
    }

    // 挑下一格：先补一轮回头再试的格；没加载的跳过；现场已经对上的收下；受保护的、挖不动的记下来；都没问题就走过去。
    private ActionStatus nextCell() {
        refillDue();
        while (!queue.isEmpty()) {
            PlannedCell centre = services.digs() != null && services.digs().chains() ? squareCentre() : null;
            chainFace = centre == null ? null : Direction.UP;
            if (centre != null) queue.remove(centre);
            cell = centre != null ? centre : queue.poll();
            respots = 0;
            settleWaitTicks = 0;
            hadFallingAbove = false;
            gravityReclears = 0;
            BlockPos pos = cell.pos();
            if (!services.site().loaded(pos)) continue;
            before = services.site().state(pos);
            if (before.isAir()) continue;
            // 要清的格现场已经是要的样子（上一轮其实放成了、别人先换好了）：先认下，不再问保护也不挖。
            if (site.alreadyMatches(cell, services.site())) continue;
            // 基岩、世界边界按游戏规则挖不动；受保护的格照保护判断给的种类记；没接挖掘是施工这边不支持。
            if (services.site().unbreakable(pos)) {
                site.exclude(pos, CellEnding.IMPOSSIBLE, Problem.Kind.NOT_POSSIBLE_HERE, "挖不动（基岩或世界边界）");
                continue;
            }
            Optional<Problem> refused = services.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, blockId(before));
            if (refused.isPresent()) {
                site.exclude(pos, CellEnding.PROTECTED, refused.get().kind(), refused.get().message());
                continue;
            }
            if (services.digs() == null) {
                site.giveUp(pos, Problem.Kind.UNSUPPORTED, "挖掘没接上，清不了");
                continue;
            }
            step = Step.APPROACH;
            begin(services.close().toward(digTarget(), permissions), "走到 " + pos.toShortString() + " 跟前");
            return ActionStatus.progressed();
        }
        cell = null;
        flushLowValueOwned();
        // 主队列清完了，回头再试的格也什么都没等到（人没站高、旁边也没清出新路）：按走不到交代，这一阶段清完。
        for (DeferredCells.Left left : deferred.drain()) {
            site.giveUp(left.cell().pos(), Problem.Kind.UNREACHABLE, "走不到能挖这一格的位置");
        }
        return ActionStatus.done();
    }

    // 主队列空了先补一轮：走近走不到先放一放的格，人站高了一层、旁边清出了能走的路的回头再试；
    // 什么都没变就不回头，不把走不到的格再空走一遍。
    private void refillDue() {
        while (queue.isEmpty()) {
            List<PlannedCell> due = deferred.takeChanged(services.site().feet(), now, cell -> false);
            if (due.isEmpty()) return;
            queue.addAll(due);
        }
    }

    // 走近的目标与采掘挖格是同一份：普通格按「会被拆掉的方块」靠近——不把要挖的那格上面当站位，
    // 下面是岩浆或看不见底的洞也不站上去挖。连锁挖的中心格例外：要对准顶面连成一片，靠近按那一面挑位。
    private ApproachTarget digTarget() {
        return chainFace == null ? ApproachTarget.ofBlockToDig(cell.pos())
                : ApproachTarget.ofBlockFace(cell.pos(), chainFace);
    }

    // 走近与挖都有限时：走近的耐心是靠近接缝上那份（走向站位的走路自己不出走不通的结论），
    // 挖的耐心与采掘挖一格同一条。到点按没做成交给 failed 决定换站位还是放一放回头，不让一格吃满施工的停滞闸。
    @Override protected long maxActionTicks(Action current) {
        if (step == Step.DIG || step == Step.DISMANTLE) return DigsBlocks.DIG_PATIENCE_TICKS;
        return step == Step.APPROACH ? BringsPlayerClose.WALK_PATIENCE_TICKS : Long.MAX_VALUE;
    }

    // 挖到一半被自卫、吃饭这类事插队叫走：人会被带离跟前，手上那一下也早松了。
    // 把挖的那一下收掉，回来时重新走近再挖；走近那一步被叫走的，走到动作自己接着走，不用重走。
    @Override public void pause() {
        super.pause();
        if (cell != null && step != Step.APPROACH) {
            discardCurrent();
            interrupted = true;
            step = Step.APPROACH;
        }
    }

    // 走到跟前后出手：先看这一格是不是已经空了（被打断前正好塌落完或清掉了），不空再问模组工具认不认领，否则连锁挖这一格。
    private ActionStatus clear() {
        if (!services.site().loaded(cell.pos()) || services.site().state(cell.pos()).isAir()) {
            return finishCell();
        }
        Optional<Action> dismantling = dismantling();
        if (dismantling.isPresent()) {
            hadFallingAbove = hasFallingAbove(cell.pos());
            step = Step.DISMANTLE;
            begin(dismantling.get(), "用工具拆下 " + cell.pos().toShortString());
            return ActionStatus.progressed();
        }
        return dig();
    }

    private Optional<Action> dismantling() {
        for (DismantleTool tool : services.dismantlers()) {
            if (tool.toolFor(before).isEmpty()) continue;
            Optional<Action> action = tool.dismantle(cell.pos());
            if (action.isPresent()) return action;
        }
        return Optional.empty();
    }

    // 触发格自己在挑格时已经核过；别的格要在待清的格里、加载了、挖得动、过许可。
    // 连锁挖按命中面一起挖一片，模组没法跳过某几格：有一格不该挖就整片不连锁，只挖触发格。
    private boolean clearable(BlockPos pos) {
        if (pos.equals(cell.pos())) return true;
        if (queue.stream().noneMatch(planned -> planned.pos().equals(pos))) return false;
        if (!services.site().loaded(pos) || services.site().unbreakable(pos)) return false;
        BlockState state = services.site().state(pos);
        return !state.isAir() && services.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, blockId(state)).isEmpty();
    }

    // 挖这一格：装了连锁挖就按命中面 3×3 连锁（整片都是要清的格才连）；挖之前记下四周还排着的格与上方是否压着重力方块，挖完复查。
    private ActionStatus dig() {
        around = queuedAround(cell.pos());
        hadFallingAbove = hasFallingAbove(cell.pos());
        // 带上这一趟还要清几格：身上没有对路的工具时按它算现做一把划不划算，做了什么记进施工结果。
        Optional<Action> digging = services.digs().digChained(cell.pos(), chainFace,
                BreakAccelerator.ChainShape.FACE_SQUARE, this::clearable,
                new DigsBlocks.ToolPrep(queue.size() + 1, false, permissions, records, digTool));
        if (digging.isEmpty()) {
            site.giveUp(cell.pos(), Problem.Kind.UNSUPPORTED, "挖掘没接上，清不了");
            cell = null;
            return nextCell();
        }
        step = Step.DIG;
        begin(digging.get(), "挖掉 " + cell.pos().toShortString());
        return ActionStatus.progressed();
    }

    // 装了连锁挖时先挑整片的中心：还没清的最上面一层里，同一层四周八格也都排着要清、都还在、头顶敞开的那一格，
    // 对准它的顶面挖，模组的命中面 3×3 正好是这一片。原先按队列从角上那格挖起，3×3 一半落在图纸外，
    // 整片不连锁（实机 10-13 两层 3×3 十八格只连了五格）。凑不出整片就照队列一格格来。
    private PlannedCell squareCentre() {
        Set<BlockPos> pending = new HashSet<>();
        int top = Integer.MIN_VALUE;
        for (PlannedCell planned : queue) {
            BlockPos pos = planned.pos();
            if (!services.site().loaded(pos) || services.site().state(pos).isAir()) continue;
            pending.add(pos);
            top = Math.max(top, pos.getY());
        }
        for (PlannedCell planned : queue) {
            BlockPos pos = planned.pos();
            if (pos.getY() != top || !pending.contains(pos) || pending.contains(pos.above())) continue;
            // 头顶长着草、花这类一碰就掉的也算敞开（野外草地上几乎格格都有）。
            if (!services.site().loaded(pos.above())) continue;
            BlockState above = services.site().state(pos.above());
            if (!above.isAir() && !above.canBeReplaced()) continue;
            boolean whole = true;
            for (int dx = -1; dx <= 1 && whole; dx++) {
                for (int dz = -1; dz <= 1 && whole; dz++) {
                    whole = pending.contains(pos.offset(dx, 0, dz));
                }
            }
            if (whole) return planned;
        }
        return null;
    }

    // 触发格四周（含斜角）一圈里还排着要清的格：命中面 3×3 不会超出这一圈。
    private Map<BlockPos, BlockState> queuedAround(BlockPos trigger) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (PlannedCell planned : queue) {
            BlockPos pos = planned.pos();
            if (Math.abs(pos.getX() - trigger.getX()) > 1 || Math.abs(pos.getY() - trigger.getY()) > 1
                    || Math.abs(pos.getZ() - trigger.getZ()) > 1) continue;
            if (!services.site().loaded(pos)) continue;
            BlockState state = services.site().state(pos);
            if (!state.isAir()) cells.put(pos, state);
        }
        return cells;
    }

    // 挖完或拆完按格复查：拆了方块还在就改用挖，不冒充清掉了；连锁顺带挖掉的格逐格结算、从队列里摘掉。
    // 挖掉后如果原格立刻又被上方塌下来的重力方块（黑沙、沙子、沙砾）填实，或正上方压着重力方块即将塌落，
    // 先等落定并把塌下来的方块一并清净，再把这一格标成已清，避免后续砌筑面对实心重占格反复碰壁。
    private ActionStatus settle() {
        if (step == Step.DISMANTLE && !services.site().state(cell.pos()).isAir()) {
            records.attempt("用工具拆下 " + cell.pos().toShortString(), "拆完方块还在，改用挖");
            return dig();
        }
        recordCleared(cell.pos(), before, "清障");
        if (step == Step.DIG) settleChained();
        ActionStatus followUp = checkGravityRefill();
        if (followUp != null) return followUp;
        return finishCell();
    }

    // 复检重力方块是否塌落重占原格：原格已被非目标实心方块重填则立即再挖；
    // 开挖前或此刻正上方压着重力方块则进入等待窗，避免单层重力方块刚转为下落实体（上方瞬间变空气）时漏检。
    private ActionStatus checkGravityRefill() {
        BlockPos pos = cell.pos();
        if (gravityReclears >= MAX_GRAVITY_RECLEARS || !services.site().loaded(pos)) return null;
        BlockState live = services.site().state(pos);
        if (!live.isAir() && !live.canBeReplaced() && !site.alreadyMatches(cell, services.site())) {
            return reclearRefilled(pos, live);
        }
        if (hadFallingAbove || hasFallingAbove(pos)) {
            hadFallingAbove = false;
            step = Step.SETTLE_FALL;
            settleWaitTicks = 0;
            return ActionStatus.running();
        }
        return null;
    }

    // 上方重力方块失去支撑后等满落定窗：下落中途正上方会因转为下落实体而瞬间变空气，不能据此提前退出；
    // 窗内一旦原格被实心重填立即转入重清，等满仍空才按清完收尾。
    private ActionStatus waitGravitySettle() {
        BlockPos pos = cell.pos();
        if (!services.site().loaded(pos)) return finishCell();
        BlockState live = services.site().state(pos);
        if (!live.isAir() && !live.canBeReplaced() && !site.alreadyMatches(cell, services.site())) {
            return reclearRefilled(pos, live);
        }
        if (++settleWaitTicks >= GRAVITY_SETTLE_TICKS) {
            return finishCell();
        }
        return ActionStatus.running();
    }

    // 原格被上方塌落方块重新填实：复核可挖性与许可后继续挖掉，不把还没清干净的格标成完成。
    private ActionStatus reclearRefilled(BlockPos pos, BlockState refilled) {
        if (services.site().unbreakable(pos)) {
            site.exclude(pos, CellEnding.IMPOSSIBLE, Problem.Kind.NOT_POSSIBLE_HERE, "挖不动（基岩或世界边界）");
            cell = null;
            return ActionStatus.progressed();
        }
        Optional<Problem> refused = services.guards().allows(PermissionCheck.WorldAction.DIG_BLOCK, pos, blockId(refilled));
        if (refused.isPresent()) {
            site.exclude(pos, CellEnding.PROTECTED, refused.get().kind(), refused.get().message());
            cell = null;
            return ActionStatus.progressed();
        }
        gravityReclears++;
        before = refilled;
        chainFace = null;
        return dig();
    }

    private boolean hasFallingAbove(BlockPos pos) {
        BlockPos above = pos.above();
        return services.site().loaded(above) && services.site().state(above).getBlock() instanceof FallingBlock;
    }

    private ActionStatus finishCell() {
        site.cleared(cell);
        deferred.changed(cell.pos());
        cell = null;
        return ActionStatus.progressed();
    }

    // 挖之前还在、现在空了的那几格：是这一下连锁一起挖掉的，按格结算；没挖掉的留在队列里照原办法挖。
    private void settleChained() {
        for (Map.Entry<BlockPos, BlockState> entry : around.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!services.site().state(pos).isAir()) continue;
            PlannedCell planned = take(pos);
            if (planned == null) continue;
            recordCleared(pos, entry.getValue(), "连锁清障");
            site.cleared(planned);
            deferred.changed(pos);
        }
        around = Map.of();
    }

    private PlannedCell take(BlockPos pos) {
        for (PlannedCell planned : queue) {
            if (planned.pos().equals(pos)) {
                queue.remove(planned);
                return planned;
            }
        }
        return null;
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        // 模组的工具没拆成：记一笔，这一格照原来的办法挖；走不到或挖不下去的记一笔接着清别的，不让一格卡住整个工地。
        records.attempt("清掉 " + cell.pos().toShortString(), failure.problem().message());
        if (step == Step.DISMANTLE) return dig();
        if (step == Step.APPROACH) {
            // 走不到跟前（包括走了很久没走到）先换个站位再走近一次；换过站位也不行就先清别的格，
            // 等人站高了一层、旁边清出了能走的路再回头。结果里只转述结论句，经过已经记进试过的办法。
            if (respots < MAX_RESPOTS) {
                respots++;
                begin(services.close().toward(digTarget(), permissions),
                        "换个站位再走 " + cell.pos().toShortString() + " 跟前");
                return ActionStatus.progressed();
            }
            deferred.defer(cell, DeferredCells.Reason.APPROACH_FAILED, services.site().feet(), now);
        } else {
            // 挖的那一下没成照它自己的种类和结论句记。
            site.giveUp(cell.pos(), failure.problem().kind(), failure.problem().headline());
        }
        around = Map.of();
        cell = null;
        return nextCell();
    }

    @Override public void close() {
        flushLowValueOwned();
        super.close();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    // 按材质分级记清障变化：别人放在家里的高价值方块（容器/机械/红石/矿块/耕地/木构/石砖/装饰）1 格即精确附归属说明；
    // 低价值天然地形（草方块、泥土、落沙、圆石等）暂存同任务计数，≤3 格静默豁免归属说明，≥4 格收尾合并汇总 1 条。
    private void recordCleared(BlockPos pos, BlockState clearedState, String prefix) {
        String id = blockId(clearedState);
        String owner = services.guards().othersAtHomeNote(pos);
        if (owner.isEmpty() || !LowValueTerrainBlocks.isLowValueTerrain(clearedState)) {
            String base = prefix + " " + pos.toShortString();
            records.change(new Change(Change.Kind.BLOCK_BROKEN, id, 1,
                    owner.isEmpty() ? base : base + "（" + owner + "）"));
            return;
        }
        BlockPos key = pos.immutable();
        lowValueOwned.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                .putIfAbsent(key, new LowValueCleared(key, id, prefix));
    }

    // 结清本任务暂存的低价值天然地形：≤3 格逐格记破块但不带归属说明（静默豁免），≥4 格合并汇总报 1 条带归属说明。
    private void flushLowValueOwned() {
        if (lowValueOwned.isEmpty()) return;
        for (Map.Entry<String, Map<BlockPos, LowValueCleared>> entry : lowValueOwned.entrySet()) {
            String owner = entry.getKey();
            Map<BlockPos, LowValueCleared> cells = entry.getValue();
            if (cells.size() < LowValueTerrainBlocks.REPORT_THRESHOLD) {
                for (LowValueCleared cleared : cells.values()) {
                    records.change(new Change(Change.Kind.BLOCK_BROKEN, cleared.blockId(), 1,
                            cleared.prefix() + " " + cleared.pos().toShortString()));
                }
            } else {
                LowValueCleared first = cells.values().iterator().next();
                records.change(new Change(Change.Kind.BLOCK_BROKEN, first.blockId(), cells.size(),
                        "清障 " + cells.size() + " 格天然地形（从 " + first.pos().toShortString() + " 起，" + owner + "）"));
            }
        }
        lowValueOwned.clear();
    }
}
