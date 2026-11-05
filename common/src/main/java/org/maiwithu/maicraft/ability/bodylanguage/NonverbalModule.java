// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
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
 * 肢体动作能力 body_language：看着一个人蹲起、挥手、点头、摇头、原地跳、拿出东西、扔东西给他、捡他扔来的、拍他一下，
 * 或者只是看着他。执行方式是"插一下"：几秒做完，不替换手上的目标，做完角色从原地接着干原来的活。
 *
 * <p>次数、时长在这里卡住：每种肢体动作各有上限，一次最长 15 秒；扔东西要写扔什么。
 * 拍一下点名就是许可，不要战斗许可，同一个人 10 秒内最多拍 2 下。
 */
public final class NonverbalModule implements AbilityModule {

    /** 看着：不写 max_seconds 看几秒、最多几秒。 */
    static final int DEFAULT_LOOK_SECONDS = 2;
    static final int MAX_LOOK_SECONDS = 10;

    private final NonverbalMoves moves;
    private final PokesGiven pokes = new PokesGiven();

    NonverbalModule(NonverbalMoves moves) {
        this.moves = Objects.requireNonNull(moves, "moves");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec("maicraft:body_language", "看着一个人做一个肢体动作（蹲起、挥手、点头、摇头、跳、递东西、拍一下）",
                AbilityDoc.forAbility("body_language"),
                ParamSpecs.of(
                        ParamSpec.of("operation", ParamType.CHOICE).required().choices(NonverbalOperation.params())
                                .doc("做哪种：look 看着 / crouch 蹲起 / swing 挥手 / nod 点头 / shake_head 摇头 / jump 原地跳"
                                        + " / hold 拿出 / toss 扔给他 / pick_up 捡起扔过来的 / poke 拍一下").build(),
                        ParamSpec.of("count", ParamType.INTEGER).range(1, 64)
                                .doc("做几下（crouch 默认 3 最多 10，swing 默认 2 最多 6，nod、shake_head、jump 默认 2 最多 5）；"
                                        + "toss 是扔几件，默认 1").build(),
                        ParamSpec.of("item", ParamType.ITEM_OR_TAG)
                                .doc("hold、toss 是拿出、扔出的东西；别的是做的时候手里拿着什么").build(),
                        ParamSpec.of("max_seconds", ParamType.INTEGER).range(1, MAX_LOOK_SECONDS)
                                .doc("只用于 look：看多久，默认 2 秒").build()),
                Set.of(TargetKind.PLAYER, TargetKind.SEEN), ExecutionMode.CUTS_IN, Set.of(), List.of(), Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        Optional<NonverbalOperation> parsed = NonverbalOperation.of(params.text("operation"));
        if (parsed.isEmpty()) return refuse("没有这种肢体动作：" + params.text("operation"), null);
        NonverbalOperation op = parsed.get();
        int count = params.has("count") ? (int) params.integer("count") : op.defaultCount;
        if (count > op.maxCount) {
            return refuse(op.words + "最多 " + op.maxCount + " 下，给的是 " + count, "count 写 1～" + op.maxCount);
        }
        String item = params.has("item") ? params.text("item") : null;
        if (op == NonverbalOperation.TOSS && item == null) return refuse("扔东西要写扔什么", "写上 item");
        int lookTicks = (params.has("max_seconds") ? (int) params.integer("max_seconds") : DEFAULT_LOOK_SECONDS) * 20;
        if (NonverbalRhythm.plannedTicks(op, count, lookTicks) > NonverbalRhythm.MAX_TICKS) {
            return refuse("一次肢体动作最长 15 秒", "把次数或时长改小，分几次做");
        }
        Target target = step.goal().target();
        return new StepDecision.Run(new NonverbalInput(op, count, op == NonverbalOperation.PICK_UP ? null : item,
                lookTicks, target));
    }

    private static StepDecision refuse(String why, String hint) {
        return new StepDecision.Finish(TaskResult.failed(why,
                Problem.of(Problem.Kind.INVALID_PARAMETER, why, hint)));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 拍过谁的账整个能力共用一份：连着下达几次拍一下也按同一本账限。
        factories.register(NonverbalInput.class, input -> new NonverbalTask(input, moves, pokes));
    }
}
