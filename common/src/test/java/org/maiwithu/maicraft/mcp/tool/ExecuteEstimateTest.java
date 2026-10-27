// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate.Effort;
import org.maiwithu.maicraft.kernel.ability.GoalEstimates;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * execute 带估算：dry_run 回一份估算；sequence 逐步估，下达 sequence 也附一份；
 * 附近还没扫完时 dry_run 隔一会儿再估；不在世界里如实说估不了，检查照常通过。
 */
class ExecuteEstimateTest {

    /** 测试用的本刻上下文：只有刻号。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    private final ToolTestAbility use = ToolTestAbility.use();
    private final ToolTestAbility sleep = ToolTestAbility.sleep();
    private boolean inWorld = true;
    private final ToolDispatcher tools;
    private final AbilityRegistry registry = new AbilityRegistry(new TaskFactories());

    ExecuteEstimateTest() {
        registry.register(use);
        registry.register(sleep);
        registry.register(ToolTestAbility.sequence());
        GoalRunTable table = new GoalRunTable(registry,
                new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), new TaskEventLog()),
                (name, position) -> { }, new ControlLoop(List.of()), new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() {
                        return true;
                    }

                    @Override public void requestControl() {
                    }
                });
        ClientThread direct = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                if (!inWorld) throw new NotInWorld();
                return work.apply(new Tick(100));
            }
        };
        tools = new ToolDispatcher(List.of(new ExecuteTool(registry, table, direct)));
    }

    private JsonObject execute(String arguments) {
        JsonObject reply = tools.call("execute", JsonParser.parseString(arguments).getAsJsonObject());
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("data");
    }

    private static GoalEstimate ready(String what) {
        return new GoalEstimate(Effort.READY, what, "身上的料就够", null, 10, List.of(), false);
    }

    private static final String SEQUENCE = "{\"ability\": \"sequence\", \"steps\": ["
            + "{\"ability\": \"use\", \"purpose\": \"做木剑\"},"
            + "{\"ability\": \"sleep\"},"
            + "{\"ability\": \"sequence\", \"steps\": [{\"ability\": \"use\"}]}]}";

    @Test
    void dryRunCarriesTheEstimateOfTheGoal() {
        use.estimates.add(new GoalEstimate(Effort.NEARBY, "minecraft:torch ×8", "自己做；缺木棍", 14.4, 25, List.of(), false));

        JsonObject estimate = execute("{\"goal\": {\"ability\": \"use\"}, \"dry_run\": true}").getAsJsonObject("estimate");

        assertEquals("nearby", estimate.get("effort").getAsString());
        assertEquals("minecraft:torch ×8", estimate.get("what").getAsString());
        assertEquals("自己做；缺木棍", estimate.get("how").getAsString());
        assertEquals(14, estimate.get("farthest_blocks").getAsInt());
        assertEquals(25, estimate.get("rough_seconds").getAsInt());
    }

    @Test
    void abilitiesWithoutAnEstimateSayTheyAreNotEstimated() {
        JsonObject estimate = execute("{\"goal\": {\"ability\": \"sleep\"}, \"dry_run\": true}").getAsJsonObject("estimate");

        assertEquals("unknown", estimate.get("effort").getAsString());
        assertEquals("这项能力还不估", estimate.get("how").getAsString());
        assertFalse(estimate.has("rough_seconds"), "估不了不编秒数");
    }

    @Test
    void dryRunOfASequenceEstimatesEveryStepOnItsOwn() {
        use.estimates.add(ready("minecraft:wooden_sword ×1"));

        JsonArray steps = execute("{\"goal\": " + SEQUENCE + ", \"dry_run\": true}")
                .getAsJsonObject("estimate").getAsJsonArray("steps");

        assertEquals(3, steps.size());
        JsonObject first = steps.get(0).getAsJsonObject();
        assertEquals(1, first.get("step").getAsInt());
        assertEquals("做木剑", first.get("purpose").getAsString());
        assertEquals("ready", first.get("effort").getAsString());
        assertEquals("unknown", steps.get(1).getAsJsonObject().get("effort").getAsString());
        JsonObject nested = steps.get(2).getAsJsonObject();
        assertFalse(nested.has("effort"), "套在里面的 sequence 整体不给档位");
        assertEquals(1, nested.getAsJsonArray("steps").size());
    }

    @Test
    void launchingASequenceAlsoCarriesTheEstimate() {
        use.estimates.add(ready("minecraft:wooden_sword ×1"));

        JsonObject data = execute("{\"goal\": " + SEQUENCE + "}");

        assertTrue(data.has("goal_id"));
        assertEquals(3, data.getAsJsonObject("estimate").getAsJsonArray("steps").size());
    }

    @Test
    void launchingASingleGoalCarriesNoEstimate() {
        assertFalse(execute("{\"goal\": {\"ability\": \"use\"}}").has("estimate"));
    }

    @Test
    void dryRunWaitsWhileTheSurroundingsAreStillBeingScanned() {
        use.estimates.add(GoalEstimate.stillLooking("minecraft:oak_log ×4", "附近还没看完"));
        use.estimates.add(ready("minecraft:oak_log ×4"));

        JsonObject estimate = execute("{\"goal\": {\"ability\": \"use\"}, \"dry_run\": true}").getAsJsonObject("estimate");

        assertEquals("ready", estimate.get("effort").getAsString());
        assertEquals(2, use.estimatedTimes, "隔一会儿再估了一次");
    }

    @Test
    void readyAfterListsOnlyLaterStepsThatNeedNoWalking() {
        // 做完第 1 步：第 2 步 use 估成不用离开，列出来；第 3 步 sleep 不估、第 4 步套着的 sequence 整体不列。
        use.estimates.add(ready("minecraft:wooden_sword ×1"));
        Goal step = Goal.of("maicraft:use", null, ParamValues.EMPTY);
        Goal nested = new Goal("maicraft:sequence", null, null, ParamValues.EMPTY, null, List.of(step), null);
        Goal sequence = new Goal("maicraft:sequence", null, null, ParamValues.EMPTY, null,
                List.of(step, step, Goal.of("maicraft:sleep", null, ParamValues.EMPTY), nested), null);

        List<GoalEstimates.StepEstimate> ready = new GoalEstimates(registry).readyAfter(sequence, 0, new Tick(100));

        assertEquals(1, ready.size());
        assertEquals(1, ready.getFirst().index());
    }

    @Test
    void outsideTheWorldTheCheckStillPassesButTheEstimateCannotBeMade() {
        inWorld = false;

        JsonObject data = execute("{\"goal\": {\"ability\": \"use\"}, \"dry_run\": true}");

        assertTrue(data.has("plan_id"));
        assertEquals("unknown", data.getAsJsonObject("estimate").get("effort").getAsString());
        assertTrue(data.getAsJsonObject("estimate").get("how").getAsString().startsWith("此刻估不了"));
    }
}
