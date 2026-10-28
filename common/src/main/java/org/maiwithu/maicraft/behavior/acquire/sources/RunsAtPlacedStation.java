// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import org.maiwithu.maicraft.behavior.acquire.cook.RecipeRuns;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 到刚摆下的那台设施上做：动手时才从世界记忆里认它的位置。附近本来没有这台设施，
 * 它的记录是摆台那一步做完才写下的；串动作还没开始跑时就查，查到的永远是空，
 * 于是只剩摆台、不做东西，结果还像做完了。认不出位置（摆台的动作没记下）就如实失败，由引擎换路。
 */
final class RunsAtPlacedStation implements Action {

    private final RecipeRuns runs;
    private final WorkstationRecipe recipe;
    private final int times;
    private final Permissions permissions;
    /** 发起任务的记账口；null 表示这次不记。 */
    private final TaskRecords records;
    /** 认出位置后在那台设施上做的动作；还没认出为 null。 */
    private Action run;

    RunsAtPlacedStation(RecipeRuns runs, WorkstationRecipe recipe, int times, Permissions permissions,
            TaskRecords records) {
        this.runs = runs;
        this.recipe = recipe;
        this.times = times;
        this.permissions = permissions;
        this.records = records;
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (run == null) {
            run = runs.runAtRememberedStation(recipe, times, permissions, records).orElse(null);
            if (run == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        recipe.kind().displayName() + "摆下了，但记忆里没有它的位置，做不了"
                                + recipe.result().describe()));
            }
        }
        return run.tick(tick);
    }

    @Override public void pause() {
        if (run != null) run.pause();
    }

    @Override public void close() {
        if (run != null) run.close();
    }

    @Override public Interruptibility interruptibility() {
        return run == null ? Interruptibility.WORKING : run.interruptibility();
    }

    @Override public String describe() {
        return run == null
                ? "到刚摆下的" + recipe.kind().displayName() + "跟前做" + recipe.result().describe()
                : run.describe();
    }
}
