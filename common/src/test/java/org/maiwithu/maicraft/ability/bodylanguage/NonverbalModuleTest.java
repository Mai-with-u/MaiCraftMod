// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 肢体动作能力的参数：插一下的执行方式、默认次数、每种的上限、扔东西要写扔什么。 */
class NonverbalModuleTest {

    private final NonverbalModule module = new NonverbalModule(new FakeNonverbalMoves());

    private StepDecision decide(String json) {
        ParseResult parsed = module.spec().paramSpecs().parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        Goal goal = new Goal("maicraft:body_language", null, null, parsed.params(), null, List.of(), null);
        return module.decide(new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        });
    }

    private static Problem refused(StepDecision decision) {
        return assertInstanceOf(StepDecision.Finish.class, decision).result().problem();
    }

    @Test
    void cutsInRatherThanReplacingTheGoal() {
        assertEquals(ExecutionMode.CUTS_IN, module.spec().mode());
    }

    // 不写次数按默认：蹲起 3 下，看着 2 秒。
    @Test
    void defaultsCountAndLookTime() {
        NonverbalInput crouch = (NonverbalInput) assertInstanceOf(StepDecision.Run.class,
                decide("{\"operation\":\"crouch\"}")).input();
        assertEquals(3, crouch.count());
        assertNull(crouch.target());
        NonverbalInput look = (NonverbalInput) assertInstanceOf(StepDecision.Run.class,
                decide("{\"operation\":\"look\",\"max_seconds\":5}")).input();
        assertEquals(100, look.lookTicks());
    }

    // 每种有自己的上限：挥手最多 6 下；扔东西不写 item 不知道扔什么。
    @Test
    void refusesOverLimitsAndTossWithoutItem() {
        Problem tooMany = refused(decide("{\"operation\":\"swing\",\"count\":7}"));
        assertEquals(Problem.Kind.INVALID_PARAMETER, tooMany.kind());
        assertTrue(tooMany.message().contains("最多 6 下"));
        assertEquals(Problem.Kind.INVALID_PARAMETER, refused(decide("{\"operation\":\"toss\"}")).kind());
    }
}
