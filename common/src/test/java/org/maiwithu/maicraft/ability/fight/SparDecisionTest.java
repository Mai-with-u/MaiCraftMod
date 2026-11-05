// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.combat.SparPartner;
import org.maiwithu.maicraft.behavior.combat.Sparring;
import org.maiwithu.maicraft.behavior.survival.SuicideWayOut;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** fight 的切磋：只点一位玩家（玩家名或观察编号），停手线默认 6 点，点名即许可、走自己的任务。 */
class SparDecisionTest {

    private final FightModule module = new FightModule(null, null, null, null, SuicideWayOut.NONE, null, new SparPartner());

    private StepDecision decide(String json, Target target) {
        ParseResult parsed = module.spec().paramSpecs().parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        Goal goal = new Goal("maicraft:fight", null, target, parsed.params(), null, List.of(), null);
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

    @Test
    void sparNamesOnePlayerWithTheDefaultLine() {
        SparInput input = (SparInput) assertInstanceOf(StepDecision.Run.class,
                decide("{\"spar\":true}", new Target.Player("Alex"))).input();
        assertEquals(Sparring.DEFAULT_STOP_AT, input.stopAt());
        SparInput toTheEnd = (SparInput) assertInstanceOf(StepDecision.Run.class,
                decide("{\"spar\":true,\"stop_at_health\":0}", new Target.Seen("e4"))).input();
        assertEquals(0, toTheEnd.stopAt());
    }

    @Test
    void sparWithoutAPlayerIsRefused() {
        StepDecision.Finish refused = assertInstanceOf(StepDecision.Finish.class, decide("{\"spar\":true}", null));
        assertEquals(Problem.Kind.INVALID_PARAMETER, refused.result().problem().kind());
    }
}
