// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.reserve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 预留能力：只改预留表、当场完成；写岔的物品、写成 count 的件数一次报全，一条都不写进去。 */
class ReserveAbilityTest {

    private static final Set<String> KNOWN = Set.of("minecraft:oak_planks", "minecraft:iron_ingot", "minecraft:diamond");

    private final ReservedItems reserved = new ReservedItems(new FakeTags(), () -> Map.of("minecraft:oak_planks", 22));
    private final ReserveAbility ability = new ReserveAbility(reserved, new ReadsItemRegistry() {
        @Override public boolean itemExists(String itemId) {
            return KNOWN.contains(itemId);
        }

        @Override public boolean tagHasItems(String tagId) {
            return tagId.equals("minecraft:planks");
        }

        @Override public String missing(String itemIdOrTag) {
            return "物品 " + itemIdOrTag + " 不存在";
        }
    });

    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    private static final TickContext NOW = new TickContext() {
        @Override public long gameTick() {
            return 100;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("预留不应碰到角色对象");
        }
    };

    private StepDecision decide(String json) {
        ParseResult parsed = ability.spec().paramSpecs().parse(JsonParser.parseString(json).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        ParamValues params = parsed.params();
        return ability.decide(step(new Goal("maicraft:reserve", null, null, params, null, List.of(), null)));
    }

    private TaskResult run(String json) {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(json));
        ReserveTask task = new ReserveTask((ReserveInput) run.input(), reserved);
        task.start(NOW);
        return assertInstanceOf(TickResult.Finished.class, task.tick(NOW)).result();
    }

    @Test
    void 不占身体_只改记忆() {
        assertEquals("maicraft:reserve", ability.spec().id());
        assertEquals(ExecutionMode.MEMORY_ONLY, ability.spec().mode());
    }

    @Test
    void 写下几样_不写件数是全都留着_结果带身上件数() {
        TaskResult result = run("{\"items\":[{\"item\":\"minecraft:oak_planks\",\"at_least\":40},\"minecraft:diamond\"]}");

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(40, reserved.keep("minecraft:oak_planks"));
        assertEquals(Integer.MAX_VALUE, reserved.keep("minecraft:diamond"));
        ReserveTask.ReserveDetails details = assertInstanceOf(ReserveTask.ReserveDetails.class, result.details());
        assertEquals(new ReserveTask.ReservedNow("minecraft:oak_planks", 40, null, 22), details.reserved().get(0));
        assertEquals(new ReserveTask.ReservedNow("minecraft:diamond", null, true, 0), details.reserved().get(1));
    }

    @Test
    void 写岔的物品与写成count的件数一次报全_一条都不写() {
        StepDecision decision = decide("{\"items\":[{\"item\":\"minecraft:oak_plank\",\"at_least\":4},"
                + "{\"item\":\"minecraft:iron_ingot\",\"count\":23},{\"item\":\"minecraft:diamond\",\"at_least\":0}]}");

        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decision);
        Problem problem = finish.result().problem();
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
        assertTrue(problem.message().contains("minecraft:oak_plank 不存在"), problem.message());
        assertTrue(problem.message().contains("at_least（身上留够几件），不是 count"), problem.message());
        assertTrue(problem.message().contains("at_least 至少为 1"), problem.message());
        assertTrue(reserved.list().isEmpty());
    }

    @Test
    void 写下时必须给items() {
        assertInstanceOf(StepDecision.Finish.class, decide("{}"));
    }

    @Test
    void 撤掉几样_没预留的如实说_不给items就全撤() {
        run("{\"items\":[{\"item\":\"minecraft:oak_planks\",\"at_least\":40},{\"item\":\"minecraft:iron_ingot\",\"at_least\":23}]}");

        TaskResult some = run("{\"operation\":\"cancel\",\"items\":[\"minecraft:oak_planks\",\"minecraft:diamond\"]}");
        assertTrue(some.summary().contains("minecraft:diamond 本来就没有预留"), some.summary());
        assertEquals(0, reserved.keep("minecraft:oak_planks"));
        assertEquals(23, reserved.keep("minecraft:iron_ingot"));

        run("{\"operation\":\"cancel\"}");
        assertTrue(reserved.list().isEmpty());
    }

    @Test
    void 标签要下面真有物品() {
        TaskResult ok = run("{\"items\":[{\"item\":\"#minecraft:planks\",\"at_least\":40}]}");
        assertEquals(TaskResult.Status.DONE, ok.status());
        assertInstanceOf(StepDecision.Finish.class, decide("{\"items\":[\"#minecraft:nothing_here\"]}"));
    }
}
