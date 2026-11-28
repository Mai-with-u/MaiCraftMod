// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.WalkProgress;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战后打扫：打完一仗先走回要回到的点，再把这一仗新掉出来的东西捡起来。
 * 点名战斗回最后一只倒下的地方、自卫走回打点，用的是同一套做法，收在这里只写一遍：
 * 算没算回到按水平距离比半径；长回程的进展从走到运行的脚位读（走到进展 {@link WalkProgress}），
 * 测试里的替身动作不是走到运行时退回按动作状态记；收尾途中落地报的「已停下」不算走到，
 * 站着等外层停稳，也不另起一趟，接着做时再按离点多远重新起步。
 *
 * <p>这里每刻只走一步：回程盯不盯威胁（自卫逐刻问战斗引擎）、走不回去怎么交代
 * （自卫发事件说明停在哪，点名战斗走不到也照样在原地捡）、捡到什么怎么入账，都是调用方的事。
 * 实现了 {@link Action} 只为让调用方把它登记进手上：暂停、先停稳、收尾逐个转给在做的走路与捡拾。
 */
public final class AfterFight implements Action {

    /** 要走回去的那个点：圆心、离它多近算回到（格）、寻路目标多近算走到（格）。两个半径分开给，不悄悄统一。 */
    public record Spot(double[] center, double arriveRadius, double walkRadius) {
        public Spot {
            center = center.clone();
        }
    }

    /** 走回的一刻到哪一步了。 */
    public enum BackStep {
        /** 已经在半径内。 */
        NEAR,
        /** 路还在走。 */
        WALKING,
        /** 收尾途中：落地报了「已停下」或还没起路，站着等外层停稳。 */
        WAITING_TO_STOP,
        /** 这趟走到正常走完了（人还没进半径）；再一刻按离点多远看要不要重新起路。 */
        WALK_ENDED,
        /** 走不到（寻路失败）；怎么交代由调用方定。 */
        WALK_FAILED
    }

    /** 捡拾的一刻到哪一步了。 */
    public enum PickStep {
        /** 还在捡。 */
        PICKING,
        /** 捡拾动作结束了（捡成、捡不到都算）；失败原因问 {@link #pickupProblem}。 */
        PICKED
    }

    private final BattleMoves moves;
    /** 进展怎么记：走到脚位挪一格记一句，替身动作有进展记一句。 */
    private final Consumer<String> progress;
    /** 此刻是不是在收尾（先到安全处再停）：收尾途中不开新的一段。 */
    private final BooleanSupplier stopping;

    /** 正在走回的那一趟；没在走为 null。 */
    private Action walk;
    /** 走回这一趟的进展观察：脚位挪一格记一次前进。 */
    private final WalkProgress walkProgress = new WalkProgress();
    /** 正在做的捡拾；没在捡为 null。 */
    private Action pickup;
    /** 最近一次捡拾动作失败的原因；没失败为 null。 */
    private String pickupProblem;

    public AfterFight(BattleMoves moves, Consumer<String> progress, BooleanSupplier stopping) {
        this.moves = moves;
        this.progress = progress;
        this.stopping = stopping;
    }

    /**
     * 走回一个点，推进一步。已经在半径内、或这趟走到结束（含走不到）都会给明确的一步；
     * 收尾途中落地报的「已停下」给 {@code WAITING_TO_STOP}，不算走到了。
     *
     * @param label        进展记录里这一趟的叫法（「回到倒下的地方」「走回打点」）
     * @param stubProgress 替身动作（不是走到运行）有进展时记的那句话
     */
    public BackStep walkBack(TickContext context, Spot spot, String label, Supplier<String> stubProgress) {
        double[] self = moves.selfPosition(context);
        // 算没算回到按水平距离比半径：掉下高一格、站上高一层不算离开。
        if (Math.hypot(self[0] - spot.center()[0], self[2] - spot.center()[2]) <= spot.arriveRadius()) {
            dropWalk();
            return BackStep.NEAR;
        }
        if (walk == null) {
            // 收尾途中不另起一趟：站着等外层停稳，接着做时再按离点多远重新起步。
            if (stopping.getAsBoolean()) return BackStep.WAITING_TO_STOP;
            BlockPos cell = BlockPos.containing(spot.center()[0], spot.center()[1], spot.center()[2]);
            walk = moves.walk(GoalCompiler.near(cell, spot.walkRadius()));
        }
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Failed) {
            if (stopping.getAsBoolean()) return BackStep.WAITING_TO_STOP;
            dropWalk();
            return BackStep.WALK_FAILED;
        }
        if (status instanceof ActionStatus.Done) {
            if (stopping.getAsBoolean()) return BackStep.WAITING_TO_STOP;
            dropWalk();
            return BackStep.WALK_ENDED;
        }
        noteWalkProgress(status, label, stubProgress);
        return BackStep.WALKING;
    }

    // 长回程的进展从走到运行的脚位读：走到在路上不逐刻自报进展，不读的话走得好好的也会被停滞判定按卡住收掉。
    // 测试里的替身动作不是走到运行，退回按动作状态记进展。
    private void noteWalkProgress(ActionStatus status, String label, Supplier<String> stubProgress) {
        if (walk instanceof WalkRun run) {
            walkProgress.note(run).ifPresent(feet -> progress.accept(label + "：走到 " + feet.toShortString()));
        } else if (status instanceof ActionStatus.Running running && running.progressed()) {
            progress.accept(stubProgress.get());
        }
    }

    /** 正在走回的那一趟的一句话；没在走为 null。 */
    public String walkDescribe() {
        return walk != null ? walk.describe() : null;
    }

    /**
     * 捡这一仗新掉出来的东西，推进一步；捡拾动作由调用方现给（各家的掉落来源不同）。
     * 同一次捡拾沿用：同一件事不每刻重建。
     */
    public PickStep pickUp(TickContext context, Supplier<Action> make) {
        if (pickup == null) pickup = make.get();
        ActionStatus status = pickup.tick(context);
        if (status instanceof ActionStatus.Running running) {
            if (running.progressed()) progress.accept(pickup.describe());
            return PickStep.PICKING;
        }
        // 捡没捡成都在这里结束：捡不到的原因留给调用方如实交代。
        pickupProblem = status instanceof ActionStatus.Failed failed ? failed.problem().message() : null;
        pickup.close();
        pickup = null;
        return PickStep.PICKED;
    }

    /** 最近一次捡拾动作失败的原因；捡成了、还没捡为 null。 */
    public String pickupProblem() {
        return pickupProblem;
    }

    /** 此刻手上那件事（捡拾、走回）能不能被打断；两件都没在做为 null，调用方去问别的。 */
    public Interruptibility currentInterruptibility() {
        if (pickup != null) return pickup.interruptibility();
        return walk != null ? walk.interruptibility() : null;
    }

    /** 此刻在做的哪件事的一句话（捡拾在前）；两件都没在做为 null。 */
    public String currentDescribe() {
        if (pickup != null) return pickup.describe();
        return walkDescribe();
    }

    private void dropWalk() {
        if (walk != null) {
            walk.close();
            walk = null;
        }
    }

    @Override public ActionStatus tick(TickContext context) { return ActionStatus.running(); }

    @Override public void pause() {
        if (walk != null) walk.pause();
        if (pickup != null) pickup.pause();
    }

    // 悬在半空的走回先落地：转给在做的走路与捡拾，有一件要收尾就先收尾。
    @Override public boolean stopSafely() {
        boolean walking = walk != null && walk.stopSafely();
        boolean picking = pickup != null && pickup.stopSafely();
        return walking || picking;
    }

    @Override public boolean stoppedSafely() {
        return (walk == null || walk.stoppedSafely()) && (pickup == null || pickup.stoppedSafely());
    }

    @Override public void close() {
        dropWalk();
        if (pickup != null) {
            pickup.close();
            pickup = null;
        }
        pickupProblem = null;
    }

    @Override public String describe() {
        String doing = currentDescribe();
        return doing != null ? doing : "战后打扫";
    }
}
