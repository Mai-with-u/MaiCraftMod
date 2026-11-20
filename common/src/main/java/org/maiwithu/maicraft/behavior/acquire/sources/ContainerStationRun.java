// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.behavior.acquire.cook.LeftStationBatch;
import org.maiwithu.maicraft.behavior.acquire.cook.StationsLeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedStation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在一座箱式加工站里跑完一趟加工或回取：
 * <ul>
 *   <li>回取路径（账本已有同目标物在跑）：先去出料箱取货，取够即完成并摘计时；取部分按剩余量续等或更新计时；
 *       若 0 产出则核对进料箱剩余原料，原料未减少或到点仍无产出时如实失败报机器未运转或堵料；</li>
 *   <li>新投料路径：到进料箱投入备齐的原料；少量需求（不超 {@value #SHORT_WAIT_MAX_ITEMS} 件）到出料箱短等轮询
 *       最多约 23 秒取出；大批量或短等后未出齐则登记在跑账本并自动挂计时，干净收场让角色去做别的事。</li>
 * </ul>
 */
final class ContainerStationRun implements Action, ReportsUnconfirmed {

    /** 少量需求阈值：不超过此件数时投完料在出料箱短等轮询取出，超过则直接挂计时离开。 */
    static final int SHORT_WAIT_MAX_ITEMS = 8;
    /** 短等首次去出料箱查看前的间隔（刻，3 秒）。 */
    static final long SHORT_WAIT_FIRST_POLL_TICKS = 60L;
    /** 短等每轮去出料箱轮询的间隔（刻，5 秒）。 */
    static final long SHORT_WAIT_POLL_INTERVAL_TICKS = 100L;
    /** 短等总时长上限（刻，23 秒，落在 20–25 秒窗口内）。 */
    static final long SHORT_WAIT_MAX_TICKS = 460L;
    private static final long TICKS_PER_SECOND = 20L;

    private enum Stage { COLLECT_RUNNING, FEED, WAIT_POLL, POLL_OUTPUT, PEEK_INPUT, DONE }

    private final RememberedStation station;
    private final WorkstationRecipe recipe;
    private String ingredientItemId;
    private String ingredientName;
    private final int wantCount;
    private final int plannedFeedCount;
    private final Supplier<Optional<String>> resolveIngredient;
    private final ToIntFunction<String> carriedCount;
    private final ContainerStationOps ops;
    private final StationsLeftCooking leftCooking;
    private final Permissions permissions;
    /** 这次任务能否投料挂计时离开：只有 obtain 及其备料能留场，其余能力（如 build 备料）需原地有界等完不打断主任务。 */
    private final boolean leavesLongCooking;
    /** 正在回取的在跑批次；新投料路径为 null。 */
    private final LeftStationBatch runningBatch;

    private Stage stage;
    private ContainerStationOps.FeedAction feedAction;
    private ContainerStationOps.TakeAction takeAction;
    private ContainerStationOps.PeekAction peekAction;
    private int fedCount;
    private int inputCountAfterFeed;
    private int expectedOut;
    private int collected;
    private long waitStartTick = -1;
    private long nextPollTick = -1;
    private long syncDeadlineTick = -1;
    private boolean checkedInputInSyncWait;
    private boolean checkingRunningZero;
    private OptionalInt lastSameContainerIngredient = OptionalInt.empty();
    private List<String> unconfirmed = List.of();

    /** 回取已在跑的批次。 */
    static ContainerStationRun collect(RememberedStation station, WorkstationRecipe recipe, LeftStationBatch batch,
            int wantCount, ContainerStationOps ops, StationsLeftCooking leftCooking, Permissions permissions,
            boolean leavesLongCooking) {
        return new ContainerStationRun(station, recipe, batch.ingredientItemId(), batch.ingredientName(),
                wantCount, 0, () -> Optional.of(batch.ingredientItemId()), id -> 0, ops, leftCooking, permissions,
                leavesLongCooking, batch);
    }

    /** 新投料（调用方已先备齐原料；开始投料时解析背包中匹配的具体原料物品 ID 并按实有件数封顶）。 */
    static ContainerStationRun feedAndRun(RememberedStation station, WorkstationRecipe recipe,
            String ingredientDisplay, int wantCount, int plannedFeedCount,
            Supplier<Optional<String>> resolveIngredient, ToIntFunction<String> carriedCount,
            ContainerStationOps ops, StationsLeftCooking leftCooking, Permissions permissions,
            boolean leavesLongCooking) {
        return new ContainerStationRun(station, recipe, null, ingredientDisplay,
                wantCount, plannedFeedCount, resolveIngredient, carriedCount, ops, leftCooking, permissions,
                leavesLongCooking, null);
    }

    private ContainerStationRun(RememberedStation station, WorkstationRecipe recipe, String ingredientItemId,
            String ingredientName, int wantCount, int plannedFeedCount,
            Supplier<Optional<String>> resolveIngredient, ToIntFunction<String> carriedCount,
            ContainerStationOps ops, StationsLeftCooking leftCooking, Permissions permissions,
            boolean leavesLongCooking, LeftStationBatch runningBatch) {
        this.station = Objects.requireNonNull(station, "station");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.ingredientItemId = ingredientItemId;
        this.ingredientName = ingredientName == null ? (ingredientItemId == null ? "原料" : ingredientItemId) : ingredientName;
        this.wantCount = wantCount;
        this.plannedFeedCount = plannedFeedCount;
        this.resolveIngredient = Objects.requireNonNull(resolveIngredient, "resolveIngredient");
        this.carriedCount = Objects.requireNonNull(carriedCount, "carriedCount");
        this.ops = Objects.requireNonNull(ops, "ops");
        this.leftCooking = Objects.requireNonNull(leftCooking, "leftCooking");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.leavesLongCooking = leavesLongCooking;
        this.runningBatch = runningBatch;
        if (runningBatch != null) {
            this.stage = Stage.COLLECT_RUNNING;
            this.fedCount = runningBatch.loaded();
            this.inputCountAfterFeed = runningBatch.inputCountAfterFeed();
            this.expectedOut = runningBatch.makes();
        } else {
            this.stage = Stage.FEED;
        }
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case COLLECT_RUNNING -> tickCollectRunning(tick);
            case FEED -> tickFeed(tick);
            case WAIT_POLL -> tickWaitPoll(tick);
            case POLL_OUTPUT -> tickPollOutput(tick);
            case PEEK_INPUT -> tickPeekInput(tick);
            case DONE -> ActionStatus.done();
        };
    }

    // 回取路径：先去出料箱取成品，取够即结清账本并摘计时；0 产出则转去核对进料箱剩余原料。
    private ActionStatus tickCollectRunning(TickContext tick) {
        if (takeAction == null) {
            takeAction = ops.takeOutput(station.output(), station.name(), recipe.result(), wantCount,
                    station.sameContainer() ? ingredientItemId : null, permissions);
        }
        ActionStatus status = takeAction.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        captureUnconfirmed(takeAction);
        if (status instanceof ActionStatus.Failed failed) {
            takeAction.close();
            takeAction = null;
            leftCooking.clear(station.name(), resultId());
            return ActionStatus.failed(wrapFailure("无法打开加工站「" + station.name() + "」的出料箱", failed.problem()));
        }
        int taken = takeAction.taken();
        lastSameContainerIngredient = takeAction.remainingIngredient();
        takeAction.close();
        takeAction = null;
        collected += taken;
        if (collected >= wantCount) {
            leftCooking.clear(station.name(), resultId());
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        if (taken > 0) {
            int remainingExpected = Math.max(0, runningBatch.makes() - taken);
            if (remainingExpected == 0) {
                leftCooking.clear(station.name(), resultId());
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            // 本批还在陆续出货：允许留场且缺口较大时续挂计时离开，否则（少量缺口或非 obtain 同步备料）继续轮询收齐。
            if (!leavesLongCooking || wantCount - collected <= SHORT_WAIT_MAX_ITEMS) {
                expectedOut = collected + remainingExpected;
                startShortWait(tick.gameTick());
                return ActionStatus.progressed();
            }
            leaveAndHangTimer(tick.gameTick(), Math.max(1, remainingExpected), runningBatch.inputCountAfterFeed(),
                    estimateWaitTicks(remainingExpected));
            return ActionStatus.done();
        }
        // 到出料箱 0 产出：同箱直接看刚才读到的原料剩余，异箱再去进料箱核对机器是否在抽料。
        checkingRunningZero = true;
        if (station.sameContainer() && lastSameContainerIngredient.isPresent()) {
            return judgeZeroOutput(lastSameContainerIngredient.getAsInt(), tick.gameTick());
        }
        stage = Stage.PEEK_INPUT;
        return ActionStatus.progressed();
    }

    // 新投料路径：解析身上实有的具体原料物品与件数往进料箱投入，少量转短等轮询，大批量直接记账挂计时离开。
    private ActionStatus tickFeed(TickContext tick) {
        if (feedAction == null) {
            Optional<String> concrete = resolveIngredient.get();
            if (concrete.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "身上没有可投进加工站「" + station.name() + "」的原料 " + ingredientName, null));
            }
            ingredientItemId = concrete.get();
            ingredientName = ingredientItemId;
            int toFeed = Math.min(plannedFeedCount, Math.max(0, carriedCount.applyAsInt(ingredientItemId)));
            if (toFeed <= 0) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "身上没有可投进加工站「" + station.name() + "」的原料 " + ingredientName, null));
            }
            feedAction = ops.feed(station.input(), station.name(), ingredientItemId, toFeed, permissions);
        }
        ActionStatus status = feedAction.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        captureUnconfirmed(feedAction);
        if (status instanceof ActionStatus.Failed failed) {
            feedAction.close();
            feedAction = null;
            return ActionStatus.failed(wrapFailure("无法向加工站「" + station.name() + "」进料箱投料", failed.problem()));
        }
        fedCount = feedAction.delivered();
        inputCountAfterFeed = feedAction.remainingInInput();
        feedAction.close();
        feedAction = null;
        if (fedCount <= 0) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "加工站「" + station.name() + "」进料箱一件 " + ingredientName + " 都没放进去（容器可能已满或无法放入）",
                    "请清理进料箱或改用熔炉烧炼"));
        }
        expectedOut = fedCount * recipe.resultCount();
        // 大批量需求且允许留场时投完即登记在跑账本并挂计时离开；非 obtain 同步备料（leavesLongCooking=false）原地有界轮询。
        if (leavesLongCooking && (wantCount > SHORT_WAIT_MAX_ITEMS || expectedOut > SHORT_WAIT_MAX_ITEMS)) {
            leaveAndHangTimer(tick.gameTick(), expectedOut, inputCountAfterFeed, estimateWaitTicks(expectedOut));
            return ActionStatus.done();
        }
        startShortWait(tick.gameTick());
        return ActionStatus.progressed();
    }

    // 少量需求短等间隔：关着界面等待下一次出料箱轮询，期间允许生存需求插进来。
    private ActionStatus tickWaitPoll(TickContext tick) {
        if (tick.gameTick() >= nextPollTick) {
            stage = Stage.POLL_OUTPUT;
            return ActionStatus.progressed();
        }
        return ActionStatus.running();
    }

    // 少量需求轮询出料箱：取齐立即完成；超时未出齐时有产出则挂计时离开，0 产出则核对进料箱判堵料。
    private ActionStatus tickPollOutput(TickContext tick) {
        int stillWant = Math.max(1, Math.min(wantCount, expectedOut) - collected);
        if (takeAction == null) {
            takeAction = ops.takeOutput(station.output(), station.name(), recipe.result(), stillWant,
                    station.sameContainer() ? ingredientItemId : null, permissions);
        }
        ActionStatus status = takeAction.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        captureUnconfirmed(takeAction);
        if (status instanceof ActionStatus.Failed failed) {
            takeAction.close();
            takeAction = null;
            if (runningBatch != null) leftCooking.clear(station.name(), resultId());
            String prefix = runningBatch != null
                    ? "无法打开加工站「" + station.name() + "」的出料箱"
                    : "已向加工站「" + station.name() + "」投入 " + fedCount + " 个" + ingredientName + "，但无法打开出料箱";
            return ActionStatus.failed(wrapFailure(prefix, failed.problem()));
        }
        collected += takeAction.taken();
        lastSameContainerIngredient = takeAction.remainingIngredient();
        takeAction.close();
        takeAction = null;
        if (collected >= Math.min(wantCount, expectedOut)) {
            if (runningBatch != null) leftCooking.clear(station.name(), resultId());
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        return afterPollShortfall(tick.gameTick());
    }

    // 轮询出料箱未凑齐时的流转：允许留场时 23 秒满即挂计时或查堵料；同步备料时做一次进料核对后有界等完，绝不留场打断外层任务。
    private ActionStatus afterPollShortfall(long nowTick) {
        long elapsed = nowTick - waitStartTick;
        if (elapsed < SHORT_WAIT_MAX_TICKS) {
            nextPollTick = Math.min(waitStartTick + SHORT_WAIT_MAX_TICKS, nowTick + SHORT_WAIT_POLL_INTERVAL_TICKS);
            stage = Stage.WAIT_POLL;
            return ActionStatus.progressed();
        }
        if (!leavesLongCooking) {
            return continueOrFinishSyncWait(nowTick);
        }
        // 短等 23 秒已到但仍未出齐：已出部分说明机器正常运转，登记剩余并挂计时；0 产出则先查进料箱。
        if (collected > 0) {
            int remainingExpected = Math.max(1, expectedOut - collected);
            leaveAndHangTimer(nowTick, remainingExpected, inputCountAfterFeed, 25L * TICKS_PER_SECOND);
            return ActionStatus.done();
        }
        return startZeroOutputCheck(nowTick);
    }

    private ActionStatus continueOrFinishSyncWait(long nowTick) {
        if (collected == 0 && !checkedInputInSyncWait) {
            checkedInputInSyncWait = true;
            return startZeroOutputCheck(nowTick);
        }
        if (nowTick < syncDeadlineTick) {
            nextPollTick = Math.min(syncDeadlineTick, nowTick + SHORT_WAIT_POLL_INTERVAL_TICKS);
            stage = Stage.WAIT_POLL;
            return ActionStatus.progressed();
        }
        if (collected > 0) {
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        if (runningBatch != null) leftCooking.clear(station.name(), resultId());
        return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                "加工站「" + station.name() + "」等待超时仍未见" + recipe.result().describe() + "产出",
                "请检查加工站运转或出料连接，确认后再试"));
    }

    private ActionStatus startZeroOutputCheck(long nowTick) {
        checkingRunningZero = false;
        if (station.sameContainer() && lastSameContainerIngredient.isPresent()) {
            return judgeZeroOutput(lastSameContainerIngredient.getAsInt(), nowTick);
        }
        stage = Stage.PEEK_INPUT;
        return ActionStatus.progressed();
    }

    // 异箱且出料箱 0 产出时，去进料箱核对原料是否减少。
    private ActionStatus tickPeekInput(TickContext tick) {
        if (peekAction == null) {
            peekAction = ops.peekInput(station.input(), station.name(), ingredientItemId, permissions);
        }
        ActionStatus status = peekAction.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        if (status instanceof ActionStatus.Failed failed) {
            peekAction.close();
            peekAction = null;
            if (runningBatch != null) leftCooking.clear(station.name(), resultId());
            return ActionStatus.failed(wrapFailure("加工站「" + station.name() + "」出料箱无产出且无法查看进料箱",
                    failed.problem()));
        }
        int remaining = peekAction.remainingIngredient();
        peekAction.close();
        peekAction = null;
        return judgeZeroOutput(remaining, tick.gameTick());
    }

    // 依据出料箱 0 产出时进料箱里所投原料是否减少，判断机器未运转/堵料还是仍在正常加工中。
    private ActionStatus judgeZeroOutput(int remainingInInput, long nowTick) {
        if (inputCountAfterFeed > 0 && remainingInInput >= inputCountAfterFeed) {
            if (runningBatch != null) leftCooking.clear(station.name(), resultId());
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "加工站「" + station.name() + "」出料箱仍无" + recipe.result().describe()
                            + "产出，且进料箱里所投原料（" + ingredientName + " " + remainingInInput + " 个）未减少：机器可能未运转或堵料",
                    "请检查加工站运转与进料连接，或改用熔炉烧炼"));
        }
        if (checkingRunningZero && runningBatch != null && nowTick >= runningBatch.readyAtGameTick()) {
            leftCooking.clear(station.name(), resultId());
            String inputFact = remainingInInput > 0
                    ? "进料箱还剩 " + remainingInInput + " 个" + ingredientName
                    : "进料箱已空，原料可能中途堵料或未通向出料箱";
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "加工站「" + station.name() + "」到点检查出料箱仍无" + recipe.result().describe()
                            + "产出（" + inputFact + "）",
                    "请检查加工站运转、动力或出料连接，确认后再试"));
        }
        // 原料确实已减少（机器正在抽料加工），只是尚未出齐：同步备料继续有界轮询，允许留场时按件数短等或挂计时离开。
        if (!leavesLongCooking) {
            inputCountAfterFeed = remainingInInput;
            if (syncDeadlineTick < 0) {
                syncDeadlineTick = nowTick + estimateWaitTicks(Math.max(1, expectedOut - collected)) + SHORT_WAIT_MAX_TICKS;
            }
            if (nowTick < syncDeadlineTick) {
                nextPollTick = Math.min(syncDeadlineTick, nowTick + SHORT_WAIT_POLL_INTERVAL_TICKS);
                stage = Stage.WAIT_POLL;
                return ActionStatus.progressed();
            }
            if (runningBatch != null) leftCooking.clear(station.name(), resultId());
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "加工站「" + station.name() + "」等待超时仍未见" + recipe.result().describe() + "产出",
                    "请检查加工站运转或出料连接，确认后再试"));
        }
        if (checkingRunningZero && wantCount <= SHORT_WAIT_MAX_ITEMS) {
            inputCountAfterFeed = remainingInInput;
            startShortWait(nowTick);
            return ActionStatus.progressed();
        }
        leaveAndHangTimer(nowTick, Math.max(1, expectedOut - collected), remainingInInput, 25L * TICKS_PER_SECOND);
        return ActionStatus.done();
    }

    private void startShortWait(long nowTick) {
        waitStartTick = nowTick;
        if (syncDeadlineTick < 0) {
            syncDeadlineTick = nowTick + estimateWaitTicks(Math.max(1, expectedOut - collected)) + SHORT_WAIT_MAX_TICKS;
        }
        nextPollTick = nowTick + SHORT_WAIT_FIRST_POLL_TICKS;
        stage = Stage.WAIT_POLL;
    }

    private void leaveAndHangTimer(long nowTick, int makes, int inputRemaining, long waitTicks) {
        leftCooking.leave(new LeftStationBatch(station.name(), station.via(), station.input(), station.output(),
                recipe.id(), resultId(), recipe.result().describe(), ingredientItemId, ingredientName,
                Math.max(1, fedCount), Math.max(0, inputRemaining), Math.max(1, makes),
                nowTick, nowTick + Math.max(TICKS_PER_SECOND, waitTicks), null));
        stage = Stage.DONE;
    }

    // 估算挂计时的等待时长（刻）：按批次大小给 20~180 秒的合理回查时间，既不空跑也不过久。
    static long estimateWaitTicks(int makes) {
        long seconds = Math.max(20L, Math.min(180L, ((Math.max(1, makes) + 15L) / 16L) * 15L));
        return seconds * TICKS_PER_SECOND;
    }

    private String resultId() {
        return recipe.result().itemId();
    }

    private static Problem wrapFailure(String prefix, Problem inner) {
        return Problem.of(inner.kind(), prefix + "：" + inner.message(), inner.suggestion());
    }

    private void captureUnconfirmed(Action action) {
        if (action instanceof ReportsUnconfirmed reporting && !reporting.unconfirmedFacts().isEmpty()) {
            unconfirmed = reporting.unconfirmedFacts();
        }
    }

    @Override
    public List<String> unconfirmedFacts() {
        return unconfirmed;
    }

    @Override
    public void pause() {
        if (feedAction != null) feedAction.pause();
        if (takeAction != null) takeAction.pause();
        if (peekAction != null) peekAction.pause();
    }

    @Override
    public void close() {
        if (feedAction != null) { feedAction.close(); feedAction = null; }
        if (takeAction != null) { takeAction.close(); takeAction = null; }
        if (peekAction != null) { peekAction.close(); peekAction = null; }
    }

    @Override
    public Interruptibility interruptibility() {
        if (feedAction != null) return feedAction.interruptibility();
        if (takeAction != null) return takeAction.interruptibility();
        if (peekAction != null) return peekAction.interruptibility();
        return stage == Stage.WAIT_POLL ? Interruptibility.BETWEEN_ACTIONS : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return switch (stage) {
            case COLLECT_RUNNING -> "回加工站「" + station.name() + "」取" + recipe.result().describe();
            case FEED -> "向加工站「" + station.name() + "」投入" + ingredientName;
            case WAIT_POLL, POLL_OUTPUT -> "在加工站「" + station.name() + "」出料箱等" + recipe.result().describe();
            case PEEK_INPUT -> "核对加工站「" + station.name() + "」进料箱剩余原料";
            case DONE -> "加工站「" + station.name() + "」处理完毕";
        };
    }
}
