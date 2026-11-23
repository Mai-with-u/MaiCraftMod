// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.StructureProtection;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 一次往下挖楼梯（或长程挖矿、或挖到够深为止）的逐刻动作：开挖前检查是否需要 2×1 双格安全穿沙井，
 * 进入实岩或常规地形后按「看清下一级 → 从上往下挖开 → 等上方重力方块落定重清 → 走下去（被回填则退回重挖） → 捡掉落物」
 * 循环推进，直到挖够想要的方块、下到停点或遇到危险/受阻停下。
 */
final class StairsDownDigging implements Action {

    private enum Stage { LOOK, WALK_OUT, SHIFT_SHAFT, SAND_SHAFT, DIG, SETTLE_FALL, FILL, STEP_DOWN, PICK_UP, MINE }

    private final ClientStairsDown.Site site;
    private final DigsBlocks digs;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;
    private final Campground campground;
    private final Predicate<String> yields;
    private final int wantedCells;
    private final Permissions permissions;
    /** 长程挖矿往哪挖；挖楼梯找石头时为空。 */
    private final DigsStairsDown.Tunnel tunnel;
    /** 站到这一格（脚的位置）就算下够了，提前停；按挖的格数停的挖法为空。 */
    private final Predicate<BlockPos> reached;
    /** 这一段大概还要挖几格：按格数停的挖法给 wantedCells，够深就停的挖法给规划时估的格数。 */
    private final int plannedCells;
    /** 挖掉的方块按种类记进这份变化；不给账本（采掘自己数）的挖法为空。 */
    private final TaskRecords records;

    private Stage stage = Stage.LOOK;
    private Direction heading;
    private BlockPos feet;
    private Action step;
    /** 手上正挖的这一格在哪、是什么：挖完记账、判掉不掉想要的东西与单格耐心超时都用它。 */
    private BlockPos diggingCell;
    private String diggingType;
    /** 手上正挖的这一格挖掉会不会掉想要的东西：挖完才算数。 */
    private boolean diggingYields;
    private int digTicks;
    private int digsThisStep;
    /** 本级是否遇到过重力方块塌落、是否待落定、已落定及走步失败回退重挖次数。 */
    private boolean gravityPending;
    private boolean settledThisStep;
    private boolean hadGravityThisStep;
    private int settleWaitTicks;
    private int stepRetriesThisStep;
    /** 穿沙井状态：已完成切入阶梯、本趟禁用（安全中止或无覆盖层）、已横移重选、中止过的 B 列与进行中的井动作。 */
    private boolean shaftFinished;
    private boolean shaftDisabled;
    private boolean shaftShifted;
    private final Set<BlockPos> excludedShaftB = new HashSet<>();
    private SandShaftDigging activeShaft;
    /** 同一趟楼梯或通道内的连锁拒绝记忆：前方未加载、紧挨流体或工具挖不下只试一次，同区块同朝向不再每格白等。 */
    private final ChainSkipMemory chainSkip = new ChainSkipMemory();
    private int yielded;
    private int steps;
    /** 长程挖矿横着走了几步。 */
    private int levelSteps;
    /** 正走的这一步是横着的。 */
    private boolean steppingLevel;
    /** 正走的这一步一次走过几格：横着挖、前面连着空着时不止一格。 */
    private int stepRun = 1;
    /** 已经开始横着挖了：台阶挖到那一层时转一次向。 */
    private boolean levelStarted;
    private Set<Integer> dropsBefore = Set.of();
    /** 这一级要走到哪一格落脚：一般是下一级，脚下空一两格时是再往下的实地上面。 */
    private BlockPos stepTarget;
    /** 刚连锁挖的那一行：触发格以外排着的格和各是什么，挖完数一数顺带挖到了几格想要的。 */
    private BlockPos chainTrigger;
    private Map<BlockPos, String> chainedRow = Map.of();
    private ActionStatus ended;
    /** 被打断过（夜里去睡觉、躲怪）：回来时人可能已经不在原来那一级上。 */
    private boolean paused;
    /** 来时的方向（上一级在这一边）：拐弯不往回挖，免得挖掉来时踩的那一级；第一级为空。 */
    private Direction back;
    /** 这一级已经在下一级脚下垫过一块：垫完还是空的就不再垫。走下一级、被打断回来就清掉。 */
    private boolean filledThisStep;
    /** 这一级已经挖不了、挖砸了的方向：换方向时不再试。走下一级就清空。 */
    private final Set<Direction> refusedHere = EnumSet.noneOf(Direction.class);
    /** 这一级第一个挖不了的原因：几个方向都不行时照它说。 */
    private Problem firstRefusal;
    /** 挖掉的方块按种类计数：一段台阶几十格石头，结果里合成一条，不一格一条。 */
    private final Map<String, Integer> dugByType = new LinkedHashMap<>();
    private boolean booked;
    /** 开挖前看过一次在不在自己家里（在就先走出去了）：只看一次，走出去以后不再找。 */
    private boolean leftHome;
    /** 被叫先到安全处再停、手上那一下（走下一级、挖、垫、捡）说要先收尾：只推进它到停稳；暂停时清掉。 */
    private boolean stopping;

    StairsDownDigging(ClientStairsDown.Site site, DigsBlocks digs, PicksUpDrops drops, PermissionCheck permission,
            Campground campground, Predicate<String> yields, int wantedCells, Permissions permissions,
            DigsStairsDown.Tunnel tunnel) {
        this(site, digs, drops, permission, campground, yields, wantedCells, permissions, tunnel, null, null, wantedCells);
    }

    StairsDownDigging(ClientStairsDown.Site site, DigsBlocks digs, PicksUpDrops drops, PermissionCheck permission,
            Campground campground, Predicate<BlockPos> reached, int plannedCells, Permissions permissions,
            TaskRecords records) {
        this(site, digs, drops, permission, campground, null, 0, permissions, null, reached, records, plannedCells);
    }

    private StairsDownDigging(ClientStairsDown.Site site, DigsBlocks digs, PicksUpDrops drops, PermissionCheck permission,
            Campground campground, Predicate<String> yields, int wantedCells, Permissions permissions,
            DigsStairsDown.Tunnel tunnel, Predicate<BlockPos> reached, TaskRecords records, int plannedCells) {
        this.site = site;
        this.digs = digs;
        this.drops = drops;
        this.permission = permission;
        this.campground = campground;
        this.yields = yields;
        this.wantedCells = wantedCells;
        this.permissions = permissions;
        this.tunnel = tunnel;
        this.reached = reached;
        this.records = records;
        this.plannedCells = plannedCells;
    }

    @Override
    public ActionStatus tick(TickContext context) {
        if (ended != null) return ended;
        // 收尾途中：只把手上那一下推进到停稳（走下一级跳在半空的先落地），落地后原地等外层停下，
        // 不把落地当成走下了这一级，也不挖下一格。
        if (stopping) {
            if (step != null && !step.stoppedSafely()) step.tick(context);
            return ActionStatus.running();
        }
        // 被打断后回来：手上挖到一半、走到一半的都收掉，按此刻站的位置重新看下一级，
        // 不对着原来那几格接着挖（人可能被带走了，够不着也看不见）。
        if (paused) {
            paused = false;
            closeStep();
            digTicks = 0;
            stage = Stage.LOOK;
            filledThisStep = false;
            // 还没挖第一级就被叫走：回来时重新看一次在不在家里。
            if (steps == 0) leftHome = false;
        }
        if (step != null) return tickActiveStep(context);
        if (stage == Stage.LOOK) return look(context);
        return stage == Stage.SETTLE_FALL ? settleFall(context) : digNext(context);
    }

    private ActionStatus tickActiveStep(TickContext context) {
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running running) {
            if (running.progressed()) {
                digTicks = 0;
            } else if (stage == Stage.DIG && ++digTicks > DigsBlocks.DIG_PATIENCE_TICKS) {
                // 单格连续十五秒都没挖通（视线被卡住或够不着）：不傻等到外层一分钟判卡死，换个方向接着挖。
                closeStep();
                digTicks = 0;
                return turnOrFinish(Problem.of(Problem.Kind.STUCK,
                        "往下挖楼梯：" + at(diggingCell) + " 挖了十五秒都没挖下来，换个方向再挖"));
            }
            return status;
        }
        if (stage == Stage.DIG && step instanceof TellsToolUse tells && tells.notChainedReason() != null) {
            chainSkip.noteRefusal(tells.notChainedReason(), feet, heading, down(), diggingType);
        }
        digTicks = 0;
        closeStep();
        return switch (stage) {
            case WALK_OUT -> walkedOut(status);
            case SHIFT_SHAFT -> shiftedForShaft(status);
            case SAND_SHAFT -> afterSandShaft();
            case DIG -> dug(context, status);
            case FILL -> filled(status);
            case STEP_DOWN -> steppedDown(context, status);
            // 捡没捡全不停楼梯：掉进缝里的一两件不值得为它停手，够不够由引擎重新清点。
            case PICK_UP -> nextStep();
            // 看见的矿挖完了：长程挖矿到此收场，够不够由引擎重新清点。
            case MINE -> finish(status instanceof ActionStatus.Failed failed
                    ? Problem.of(failed.problem().kind(), "长程挖矿在 " + at(feet) + " 看见了要的矿，去挖的时候："
                            + failed.problem().message(), failed.problem().suggestion())
                    : null);
            case LOOK, SETTLE_FALL -> throw new IllegalStateException("看下一级或等落沙时手上不该有动作");
        };
    }

    // 这一步往下还是横着：挖楼梯找石头一直往下；长程挖矿到了要挖的那一层就横着。
    private boolean down() {
        return tunnel == null || feet.getY() > tunnel.targetY();
    }

    // 挖下一级之前：够数就收工；先站稳，再看这一级能不能挖。第一级先朝脸对着的方向，
    // 那边挖不了就转个方向，四面都挖不了才放弃；之后沿同一个方向一路往下。
    private ActionStatus look(TickContext context) {
        if (reached == null && yielded >= wantedCells) return finish(null);
        if (!site.onGround(context)) return ActionStatus.running();
        Optional<BlockPos> standing = site.feet(context);
        if (standing.isEmpty()) return ActionStatus.running();
        feet = standing.get();
        if (reached != null && reached.test(feet)) return finish(null);
        if (reached == null && steps == 0 && !leftHome) {
            leftHome = true;
            if (atHome(context, feet)) return walkOut(context);
        }
        ActionStatus limitStop = checkStepLimits();
        if (limitStop != null) return limitStop;
        // 台阶挖到那一层：转九十度再横着挖。连锁挖的斜线越过了这一层还往下伸，顺着原方向横挖会一路掉进那几条斜线里。
        if (tunnel != null && !down() && !levelStarted) {
            levelStarted = true;
            if (heading != null) turnAside(context);
        }
        // 下掘阶段遇连续重力覆盖层且 16 格内有安全实岩顶面时，优先走 2×1 双格安全穿沙井直达实岩下 3 格再切回阶梯。
        if (down() && !shaftFinished && !shaftDisabled) {
            ActionStatus shaftStatus = tryStartSandShaft(context);
            if (shaftStatus != null) return shaftStatus;
        }
        Optional<Problem> stop = chooseHeading(context);
        if (stop.isPresent()) return finish(stop.get());
        dropsBefore = drops.nearby();
        digsThisStep = 0;
        gravityPending = false;
        settledThisStep = false;
        hadGravityThisStep = false;
        settleWaitTicks = 0;
        stepRetriesThisStep = 0;
        stage = Stage.DIG;
        return ActionStatus.progressed();
    }

    private ActionStatus checkStepLimits() {
        if (tunnel == null) {
            if (steps >= ClientStairsDown.MAX_STEPS) {
                return finish(Problem.of(Problem.Kind.STUCK, "往下挖了 " + steps + " 级楼梯，先停在这里"));
            }
            if (reached == null && yielded == 0 && steps >= ClientStairsDown.MAX_DRY_STEPS) {
                return finish(Problem.of(Problem.Kind.NOT_FOUND, "往下挖了 " + steps
                        + " 级楼梯还没挖到想要的方块，这一带地下可能是很厚的沙子或陶瓦，换个地方再挖"));
            }
            return null;
        }
        if (tunnel.inSight().test(feet)) return mineInSight();
        if (!down() && levelSteps >= ClientStairsDown.LEVEL_STEPS) {
            return finish(Problem.of(Problem.Kind.NOT_FOUND, "在 Y=" + feet.getY() + " 横着挖了 " + levelSteps
                    + " 格通道还没看见要的矿，停在通道尽头"));
        }
        if (steps >= ClientStairsDown.MAX_TUNNEL_STEPS) {
            return finish(Problem.of(Problem.Kind.STUCK, "长程挖矿走了 " + steps + " 步，先停在这里"));
        }
        return null;
    }

    // 评估并启动 2×1 穿沙井：当前位下方遇流体/空腔时尝试横移 2 格重选井位，无安全井位则关闭穿沙井回退常规阶梯。
    private ActionStatus tryStartSandShaft(TickContext context) {
        // 穿沙井同样避开自家营地与结构保护区（已登记建筑、在役机器），防止垂直掘进挖穿地下设施。
        Predicate<BlockPos> forbidden = cell -> (reached == null && atHome(context, cell))
                || permission.structureBlockAt(position(context, cell)).protects();
        Predicate<BlockPos> digAllowed = cell -> permission.blockAllowed(permissions,
                PermissionCheck.WorldAction.DIG_BLOCK, position(context, cell), site.blockType(context, cell)).isEmpty();
        Direction preferred = heading != null ? heading : site.facing(context);
        Optional<SandShaftPlan> plan = SandShaftPlan.evaluate(site, context, feet, preferred, forbidden,
                digAllowed, excludedShaftB);
        if (plan.isPresent()) {
            activeShaft = new SandShaftDigging(site, digs, drops, permission, permissions, plan.get(), yields,
                    wantedCells, reached, tunnel, forbidden, this::toolPrep);
            step = activeShaft;
            stage = Stage.SAND_SHAFT;
            return ActionStatus.progressed();
        }
        if (!shaftShifted && SandShaftPlan.hasGravityOverburden(site, context, feet)) {
            Optional<BlockPos> shifted = SandShaftPlan.findShiftedStart(site, context, feet, preferred,
                    forbidden, digAllowed);
            if (shifted.isPresent()) {
                shaftShifted = true;
                stage = Stage.SHIFT_SHAFT;
                stepTarget = shifted.get();
                step = site.stepTo(stepTarget);
                return ActionStatus.progressed();
            }
        }
        shaftDisabled = true;
        return null;
    }

    // 横移重选安全井位结束：走到了就回到 LOOK 尝试开井，走不过去就关闭穿沙井回退常规阶梯。
    private ActionStatus shiftedForShaft(ActionStatus status) {
        if (status instanceof ActionStatus.Failed) shaftDisabled = true;
        return nextStep();
    }

    // 2×1 穿沙井收尾：合并产出与挖块账；顺利进入实岩下 3 格则平滑切回标准阶梯，中途遇险中止则记下坏 B 列并重选或回退。
    private ActionStatus afterSandShaft() {
        SandShaftDigging done = activeShaft;
        activeShaft = null;
        yielded += done.yieldedCount();
        if (records != null) done.dugByType().forEach((type, count) -> dugByType.merge(type, count, Integer::sum));
        if (done.exit() == SandShaftDigging.ShaftExit.HANDOFF_TO_STAIRS) {
            shaftFinished = true;
            back = null;
            heading = null;
            refusedHere.clear();
            firstRefusal = null;
        } else {
            excludedShaftB.add(SandShaftPlan.columnKey(done.abortedColumn()));
        }
        return nextStep();
    }

    // 这一级朝哪边挖：原方向挖得了就照原方向；挖不了就换——第一级四面都试，之后往左、往右拐，不往回挖。
    private Optional<Problem> chooseHeading(TickContext context) {
        for (Direction tried : StairStepShape.headingOrder(heading, site.facing(context))) {
            if (tried == back || refusedHere.contains(tried)) continue;
            Optional<Problem> why = refusal(context, tried);
            if (why.isEmpty()) {
                heading = tried;
                return Optional.empty();
            }
            refusedHere.add(tried);
            if (firstRefusal == null) firstRefusal = why.get();
        }
        return Optional.of(firstRefusal);
    }

    // 挖这一级挖到一半挖不了（上面掉下沙砾一直挖不通、挖不动、不许动）：这个方向记作挖不了，回去重新挑方向。
    private ActionStatus turnOrFinish(Problem why) {
        refusedHere.add(heading);
        if (firstRefusal == null) firstRefusal = why;
        stage = Stage.LOOK;
        return ActionStatus.progressed();
    }

    // 往右拐，右边挖不了往左拐；两边都不行就照原方向。
    private void turnAside(TickContext context) {
        for (Direction side : List.of(heading.getClockWise(), heading.getCounterClockWise())) {
            if (refusal(context, side).isEmpty()) {
                heading = side;
                return;
            }
        }
    }

    // 这一级为什么不能挖；能挖为空。要挖的格看能不能挖、是否在结构保护区、许不许动，四周看有没有水或岩浆。
    private Optional<Problem> refusal(TickContext context, Direction toward) {
        StairStepShape.Heading way = new StairStepShape.Heading(toward, down());
        for (BlockPos cell : StairStepShape.stepCells(feet, toward, way.down())) {
            if (reached == null && atHome(context, cell)) return Optional.of(underHome(cell));
            ClientStairsDown.Ground ground = site.ground(context, cell);
            if (ground == ClientStairsDown.Ground.OPEN) continue;
            if (ground != ClientStairsDown.Ground.DIGGABLE) return Optional.of(blocked(context, cell, ground));
            WorldPosition where = position(context, cell);
            // 前方剖面落入已登记建筑或在役世界机器的结构保护区时，不挖穿它，交给选向拐弯避开或停下。
            StructureProtection structure = permission.structureBlockAt(where);
            if (structure.protects()) return Optional.of(protectedStructure(cell, structure));
            Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                    where, site.blockType(context, cell));
            if (refused.isPresent()) return refused;
        }
        for (BlockPos cell : StairStepShape.surroundings(feet, way)) {
            if (site.ground(context, cell) == ClientStairsDown.Ground.FLUID) {
                return Optional.of(Problem.of(Problem.Kind.DANGER, "往下挖楼梯：前面挨着 " + at(cell) + " 的 "
                        + site.blockType(context, cell) + "，挖开会灌进来，停在这里"));
            }
        }
        BlockPos floor = StairStepShape.nextFeet(feet, way).below();
        if (site.hurtsToStandOn(context, floor)) return Optional.of(hotFloor(context, floor));
        return Optional.empty();
    }

    // 挑家外最近、站得住的一格走过去；找不到、走不过去就如实停下，不在家里挖。
    private ActionStatus walkOut(TickContext context) {
        Optional<BlockPos> spot = StairsOutsideHome.pick(feet, cell -> atHome(context, cell),
                cell -> site.standable(context, cell));
        if (spot.isEmpty()) {
            return finish(Problem.of(Problem.Kind.NOT_FOUND, "人在自己家里，" + StairsOutsideHome.RADIUS
                    + " 格内找不到家外能站的天然地面，不在自家底下往下挖楼梯", "先走出营地到空地上再要"));
        }
        stage = Stage.WALK_OUT;
        stepTarget = spot.get();
        step = site.stepTo(stepTarget);
        return ActionStatus.progressed();
    }

    // 走到了家外那一格：从这里重新看第一级；走不过去如实停下。
    private ActionStatus walkedOut(ActionStatus status) {
        if (status instanceof ActionStatus.Failed failed) {
            return finish(Problem.of(Problem.Kind.UNREACHABLE, "人在自己家里，要先走出家门到 " + at(stepTarget)
                    + " 再挖楼梯，走不过去：" + failed.problem().message()));
        }
        return nextStep();
    }

    // 自家或床、箱子、工作台附近的营地范围：开挖前先走出这片地面再挖，楼梯也不挖进营地底下。
    private boolean atHome(TickContext context, BlockPos cell) {
        WorldPosition where = position(context, cell);
        return permission.atOwnHome(where) || campground.covers(where);
    }

    private Problem underHome(BlockPos cell) {
        return Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 在自己家里，不在自家底下挖");
    }

    // 前方格属于已登记建筑或在役机器的结构保护区：返回需确认问题，驱动楼梯自动拐弯避开或停下。
    private Problem protectedStructure(BlockPos cell, StructureProtection structure) {
        return Problem.of(Problem.Kind.NEED_APPROVAL,
                "往下挖楼梯：前面 " + at(cell) + structure.describe() + "，不为挖楼梯或通道拆它，停在这里",
                "换个方向避开建筑与机器，或确实要拆时用 gather 点名那一格");
    }

    private Problem hotFloor(TickContext context, BlockPos floor) {
        return Problem.of(Problem.Kind.DANGER, "往下挖楼梯：下一级 " + at(floor.above()) + " 脚下是 "
                + site.blockType(context, floor) + "，站上去会烫脚，不往这边走");
    }

    // 从上往下挖这一级还没通的格子：挖空后若正上方存在重力方块或下落实体，先原地等落定再走下一级。
    private ActionStatus digNext(TickContext context) {
        StairStepShape.Heading way = new StairStepShape.Heading(heading, down());
        List<BlockPos> cells = StairStepShape.stepCells(feet, heading, way.down());
        int maxDigs = hadGravityThisStep ? ClientStairsDown.MAX_GRAVITY_DIGS_PER_STEP : ClientStairsDown.MAX_DIGS_PER_STEP;
        for (int row = 0; row < cells.size(); row++) {
            BlockPos cell = cells.get(row);
            ClientStairsDown.Ground ground = site.ground(context, cell);
            if (ground == ClientStairsDown.Ground.OPEN) continue;
            if (ground != ClientStairsDown.Ground.DIGGABLE) return turnOrFinish(blocked(context, cell, ground));
            if (digsThisStep >= maxDigs) {
                return turnOrFinish(Problem.of(Problem.Kind.STUCK, "往下挖楼梯：" + at(cell) + " 挖了 "
                        + digsThisStep + " 下还没挖通，上面可能一直在往下掉沙砾"));
            }
            WorldPosition where = position(context, cell);
            // 逐格开挖前复核结构保护区，防止落沙后露出或档案刚刷新进入的建筑/机器格被挖穿。
            StructureProtection structure = permission.structureBlockAt(where);
            if (structure.protects()) return turnOrFinish(protectedStructure(cell, structure));
            String blockType = site.blockType(context, cell);
            Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                    where, blockType);
            if (refused.isPresent()) return turnOrFinish(refused.get());
            boolean aboveGravity = site.fallingBlock(context, cell.above())
                    || site.hasFallingEntityAbove(context, cell);
            if (aboveGravity || site.fallingBlock(context, cell)) hadGravityThisStep = true;
            if (aboveGravity) gravityPending = true;
            Optional<Action> dig = chainDig(context, cell, row, way);
            if (dig.isEmpty()) {
                return finish(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作没接上"));
            }
            step = dig.get();
            diggingCell = cell;
            diggingType = blockType;
            digTicks = 0;
            settledThisStep = false;
            diggingYields = yields == null || yields.test(blockType);
            digsThisStep++;
            return ActionStatus.progressed();
        }
        // 当前剖面已全部挖空：若正上方有重力方块或下落实体，先原地等满落定窗确认不再塌落，再迈入下一级。
        BlockPos topCell = cells.getFirst();
        if (!settledThisStep && (gravityPending || site.fallingBlock(context, topCell.above())
                || site.hasFallingEntityAbove(context, topCell))) {
            stage = Stage.SETTLE_FALL;
            settleWaitTicks = 0;
            return ActionStatus.running();
        }
        return prepareStepDown(context, way);
    }

    // 原地等落沙沉降：窗内一旦剖面任一格被落沙/沙砾重新填实立刻退回 DIG 重清，等满仍空才迈步。
    private ActionStatus settleFall(TickContext context) {
        StairStepShape.Heading way = new StairStepShape.Heading(heading, down());
        List<BlockPos> cells = StairStepShape.stepCells(feet, heading, way.down());
        for (BlockPos cell : cells) {
            if (site.ground(context, cell) != ClientStairsDown.Ground.OPEN) {
                gravityPending = false;
                settledThisStep = false;
                stage = Stage.DIG;
                return digNext(context);
            }
        }
        BlockPos topCell = cells.getFirst();
        boolean stillFalling = site.hasFallingEntityAbove(context, topCell);
        if (++settleWaitTicks >= ClientStairsDown.GRAVITY_SETTLE_TICKS
                && (!stillFalling || settleWaitTicks >= ClientStairsDown.GRAVITY_SETTLE_TICKS * 2)) {
            gravityPending = false;
            settledThisStep = true;
            stage = Stage.DIG;
            return prepareStepDown(context, way);
        }
        return ActionStatus.running();
    }

    // 剖面清净且落沙已稳：检查落脚实心、处理空洞垫块或热地面拐弯，然后交给只走不改地形的走步动作。
    private ActionStatus prepareStepDown(TickContext context, StairStepShape.Heading way) {
        BlockPos landing = StairStepShape.nextFeet(feet, way);
        ClientStairsDown.Ground floor = site.ground(context, landing.below());
        for (int extra = 0; floor == ClientStairsDown.Ground.OPEN && extra < ClientStairsDown.EXTRA_DROP; extra++) {
            landing = landing.below();
            floor = site.ground(context, landing.below());
        }
        if (floor == ClientStairsDown.Ground.OPEN || floor == ClientStairsDown.Ground.FLUID) {
            if (tunnel != null && tunnel.inSight().test(feet)) return mineInSight();
            if (floor == ClientStairsDown.Ground.OPEN) return fillUnder(context, StairStepShape.nextFeet(feet, way).below());
            return turnOrFinish(Problem.of(Problem.Kind.DANGER, "往下挖楼梯：下一级 " + at(StairStepShape.nextFeet(feet, way))
                    + " 脚下落下去是 " + site.blockType(context, landing.below()) + "，不往这边走"));
        }
        if (site.hurtsToStandOn(context, landing.below())) return turnOrFinish(hotFloor(context, landing.below()));
        stage = Stage.STEP_DOWN;
        stepTarget = landing;
        steppingLevel = !way.down();
        stepRun = 1;
        if (steppingLevel && digsThisStep == 0 && landing.equals(StairStepShape.nextFeet(feet, way))) {
            while (stepRun < ClientStairsDown.OPEN_RUN && levelSteps + stepRun < ClientStairsDown.LEVEL_STEPS) {
                BlockPos further = stepTarget.relative(heading);
                if (!StairStepShape.openStretch(site, context, further)) break;
                stepTarget = further;
                stepRun++;
            }
        }
        step = site.stepTo(stepTarget);
        return ActionStatus.progressed();
    }

    // 下一级脚下往下三格都空：许可准放、身上有垫料就在下一级脚下垫一块，垫好回去重新看这一级再走下去。
    private ActionStatus fillUnder(TickContext context, BlockPos support) {
        String hole = "往下挖楼梯：下一级 " + at(support.above()) + " 脚下是空的（往下三格都没有实地）";
        if (filledThisStep) return finish(Problem.of(Problem.Kind.NOT_FOUND, hole + "，垫过一块还是空的，不往下走了"));
        List<String> fillers = site.fillers(context);
        if (fillers.isEmpty()) {
            return finish(Problem.of(Problem.Kind.NOT_FOUND, hole + "，身上没有能垫的废料（泥土、圆石这类），没法垫，不往下走了",
                    "带些泥土、圆石再来，或换个地方挖"));
        }
        String filler = fillers.getFirst();
        Optional<Problem> refused = permission.blockAllowed(permissions, PermissionCheck.WorldAction.PLACE_BLOCK,
                position(context, support), filler);
        if (refused.isPresent()) {
            return finish(Problem.of(refused.get().kind(), hole + "，这一格不许垫方块，没法垫：" + refused.get().message(),
                    refused.get().suggestion()));
        }
        Optional<Action> place = site.placeFiller(context, support, filler);
        if (place.isEmpty()) {
            return finish(Problem.of(Problem.Kind.NOT_FOUND, hole + "，旁边也没有能对着垫的实地，没法垫，不往下走了"));
        }
        filledThisStep = true;
        stage = Stage.FILL;
        step = place.get();
        return ActionStatus.progressed();
    }

    private ActionStatus filled(ActionStatus status) {
        if (status instanceof ActionStatus.Failed failed) {
            return finish(Problem.of(failed.problem().kind(), "往下挖楼梯：下一级脚下是空的，垫一块没垫成："
                    + failed.problem().message(), failed.problem().suggestion()));
        }
        stage = Stage.DIG;
        return ActionStatus.progressed();
    }

    // 往下挖楼梯或长程挖矿挖一行：往下对准迎面（最下一行对准顶面）用斜着往下的连锁，横着用直着往前的连锁。
    private Optional<Action> chainDig(TickContext context, BlockPos trigger, int row, StairStepShape.Heading way) {
        Optional<String> skip = chainSkip.skipReason(feet, heading, way.down(), site.blockType(context, trigger));
        if (skip.isPresent()) {
            chainTrigger = null;
            chainedRow = Map.of();
            return digs.digSkippingChain(trigger, toolPrep(), skip.get());
        }
        Direction face = way.down() && row == 2 ? Direction.UP : heading.getOpposite();
        Map<BlockPos, String> line = lineAhead(context, trigger, way.down());
        chainTrigger = trigger;
        chainedRow = line;
        return digs.digChained(trigger, face, way.down() ? BreakAccelerator.ChainShape.DOWNWARD_LINE
                : BreakAccelerator.ChainShape.STRAIGHT_LINE, line::containsKey, toolPrep());
    }

    private DigsBlocks.ToolPrep toolPrep() {
        int left = tunnel != null && feet != null ? 3 * Math.max(1, feet.getY() - tunnel.targetY())
                : Math.max(3, plannedCells - yielded);
        return new DigsBlocks.ToolPrep(left, false,
                permissions == null ? Permissions.DEFAULT : permissions, records, null);
    }

    private Map<BlockPos, String> lineAhead(TickContext context, BlockPos trigger, boolean down) {
        Map<BlockPos, String> line = new LinkedHashMap<>();
        for (int k = 0; k < ClientStairsDown.CHAIN_REACH; k++) {
            BlockPos cell = trigger.relative(heading, k).below(down ? k : 0);
            WorldPosition where = position(context, cell);
            // 连锁沿线遇到自家营地、已登记建筑或在役世界机器时立即截断，不把受保护结构连带挖掉。
            if (k > 0 && (atHome(context, cell) || permission.structureBlockAt(where).protects())) break;
            if (site.ground(context, cell) != ClientStairsDown.Ground.DIGGABLE) break;
            String blockType = site.blockType(context, cell);
            if (permission.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                    where, blockType).isPresent()) break;
            if (StairStepShape.touchesFluid(site, context, cell)) break;
            line.put(cell, blockType);
        }
        return line;
    }

    private ActionStatus dug(TickContext context, ActionStatus status) {
        Map<BlockPos, String> row = chainedRow;
        BlockPos trigger = chainTrigger;
        chainedRow = Map.of();
        chainTrigger = null;
        if (status instanceof ActionStatus.Failed failed) return turnOrFinish(failed.problem());
        if (records != null) dugByType.merge(diggingType, 1, Integer::sum);
        if (diggingYields) yielded++;
        for (Map.Entry<BlockPos, String> cell : row.entrySet()) {
            if (cell.getKey().equals(trigger) || site.ground(context, cell.getKey()) != ClientStairsDown.Ground.OPEN) continue;
            if (records != null) dugByType.merge(cell.getValue(), 1, Integer::sum);
            if (yields == null || yields.test(cell.getValue())) yielded++;
        }
        return ActionStatus.progressed();
    }

    // 走下一级：走步失败且落脚格或头顶格被落沙回填成可挖方块时自动退回 DIG 重挖（每级最多 3 次）。
    private ActionStatus steppedDown(TickContext context, ActionStatus status) {
        if (status instanceof ActionStatus.Failed failed) {
            if (stepRetriesThisStep < ClientStairsDown.MAX_STEP_DIG_RETRIES
                    && StairStepShape.stepRefilled(site, context, stepTarget, feet, heading, !steppingLevel)) {
                stepRetriesThisStep++;
                hadGravityThisStep = true;
                gravityPending = false;
                settledThisStep = false;
                stage = Stage.DIG;
                return ActionStatus.progressed();
            }
            return finish(Problem.of(Problem.Kind.UNREACHABLE, "往下挖楼梯：走不下下一级 "
                    + at(stepTarget) + "：" + failed.problem().message()));
        }
        steps += stepRun;
        if (steppingLevel) levelSteps += stepRun;
        back = heading.getOpposite();
        filledThisStep = false;
        stepRetriesThisStep = 0;
        hadGravityThisStep = false;
        gravityPending = false;
        settledThisStep = false;
        refusedHere.clear();
        firstRefusal = null;
        stage = Stage.PICK_UP;
        step = drops.pickUpNewSince(dropsBefore);
        return ActionStatus.progressed();
    }

    private ActionStatus mineInSight() {
        Optional<Action> mining = tunnel.mineInSight().apply(feet);
        if (mining.isEmpty()) {
            return finish(Problem.of(Problem.Kind.NEED_APPROVAL, "长程挖矿在 " + at(feet)
                    + " 看见了要的矿，可看见的那几格都不许挖"));
        }
        stage = Stage.MINE;
        step = mining.get();
        return ActionStatus.progressed();
    }

    private ActionStatus nextStep() {
        stage = Stage.LOOK;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem why) {
        book();
        ended = why == null || yielded > 0 ? ActionStatus.done() : ActionStatus.failed(why);
        return ended;
    }

    private void book() {
        if (records == null || booked) return;
        booked = true;
        dugByType.forEach((type, count) -> records.change(new Change(Change.Kind.BLOCK_BROKEN, type, count,
                "往下挖台阶挖掉的（" + steps + " 级）")));
    }

    private Problem blocked(TickContext context, BlockPos cell, ClientStairsDown.Ground ground) {
        String what = site.blockType(context, cell);
        return switch (ground) {
            case FLUID -> Problem.of(Problem.Kind.DANGER, "往下挖楼梯：前面 " + at(cell) + " 是 " + what + "，停在这里");
            case FACILITY -> Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 是设施 " + what
                    + "，不为挖楼梯拆它，停在这里");
            case BUILT -> Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 的 " + what
                    + " 是搭出来的（平台、地板这类），不为挖楼梯拆它；到天然地面上再挖", "先走到天然的地面上");
            default -> Problem.of(Problem.Kind.NOT_FOUND, "往下挖楼梯：前面 " + at(cell) + " 的 " + what
                    + " 挖不动，停在这里");
        };
    }

    private WorldPosition position(TickContext context, BlockPos cell) {
        return new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), site.dimension(context));
    }

    private static String at(BlockPos cell) {
        return "(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")";
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
        if (stage == Stage.SAND_SHAFT && activeShaft != null) return activeShaft.describe();
        if (reached != null) return "往下挖台阶（第 " + (steps + 1) + " 级）";
        if (tunnel == null) return "往下挖楼梯（第 " + (steps + 1) + " 级，已挖到 " + yielded + "/" + wantedCells + " 格）";
        if (stage == Stage.MINE) return "长程挖矿：去挖看见的矿";
        if (feet != null && !down()) {
            return "长程挖矿：在 Y=" + feet.getY() + " 横着挖通道（第 " + (levelSteps + 1) + "/" + ClientStairsDown.LEVEL_STEPS + " 格）";
        }
        return "长程挖矿：往下挖台阶到 Y=" + tunnel.targetY() + "（第 " + (steps + 1) + " 级）";
    }
}
