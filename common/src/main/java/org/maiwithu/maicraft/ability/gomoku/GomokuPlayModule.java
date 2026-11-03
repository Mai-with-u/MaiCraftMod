// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gomoku;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.ability.RequiredMod;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 下五子棋的能力：点名一个五子棋盘（车万女仆模组摆在地上的棋盘），和坐在对面的女仆下棋——角色执黑先手，
 * 落一子再站着等她应手；或者点棋盒重开一局。参数形状在这里核完；记谱写法对不对、那一路有没有子由联动的办法负责。
 * 车万女仆没装时这个能力不上架。
 */
public final class GomokuPlayModule implements AbilityModule {

    /** 本能力需要的模组：车万女仆没装就不登记。 */
    static final String REQUIRED_MOD = "touhou_little_maid";

    /** 落下以后默认等她应手几秒：她在客户端算好一手要停 0.25 到 1.5 秒再发，15 秒足够。 */
    static final int DEFAULT_WAIT_SECONDS = 15;

    private final FindsBoard boards;

    public GomokuPlayModule(FindsBoard boards) {
        this.boards = Objects.requireNonNull(boards, "boards");
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:gomoku_play",
                "和坐在对面的女仆下五子棋：落一子并等她应手，或重开一局（车万女仆）",
                AbilityDoc.forAbility("gomoku_play"),
                ParamSpecs.of(
                        ParamSpec.of("move", ParamType.TEXT)
                                .doc("这一手下在哪：列 A–O（站在你这一侧从左到右）加行 1–15（离你最近的是 1），例如 H8 是天元；"
                                        + "落子时必填").build(),
                        ParamSpec.of("operation", ParamType.CHOICE).defaultValue("move")
                                .choices("move", "reset")
                                .doc("move 落一子（默认）；reset 点棋盘角上的棋盒重开一局").build(),
                        ParamSpec.of("max_seconds", ParamType.INTEGER).range(0, 30)
                                .doc("落下以后最多站着等她应手几秒：默认 15，最多 30；轮到她时先等她下完也按这个").build()),
                Set.of(TargetKind.SEEN),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(RequiredMod.of(REQUIRED_MOD)),
                List.of(),
                Listing.LISTED);
    }

    // 形状在这里核完：target 必须是一格方块的 b#，落子要给 move；记谱写法对不对到现场问棋盘。
    @Override public StepDecision decide(StepContext step) {
        if (!(step.goal().target() instanceof Target.Seen seen) || !seen.id().startsWith("b")) {
            return invalid("target 要给棋盘的观察编号 b#（observe 的 mod_things 里 gomoku_board 那一项）");
        }
        ParamValues params = step.goal().params();
        boolean reset = params.has("operation") && "reset".equals(params.text("operation"));
        String move = params.has("move") ? params.text("move").trim() : null;
        if (!reset && (move == null || move.isEmpty())) {
            return invalid("落子要给 move，例如 H8；重开一局写 operation=reset");
        }
        int maxSeconds = params.has("max_seconds") ? (int) params.integer("max_seconds") : DEFAULT_WAIT_SECONDS;
        return new StepDecision.Run(new GomokuPlayInput(seen.id(), reset, reset ? null : move, maxSeconds,
                step.goal().permissions()));
    }

    private static StepDecision invalid(String why) {
        return new StepDecision.Finish(TaskResult.failed("下棋的写法不对",
                Problem.of(Problem.Kind.INVALID_PARAMETER, why, null)));
    }

    @Override public void registerTasks(TaskFactories factories) {
        // 每次运行新建下棋任务；找棋盘的办法经闭包交给任务。
        factories.register(GomokuPlayInput.class, input -> new GomokuPlayTask(input, boards));
    }
}
