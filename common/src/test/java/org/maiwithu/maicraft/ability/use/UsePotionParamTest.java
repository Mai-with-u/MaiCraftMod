// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * use 点名药水种类：只给 potion 当普通药水瓶，拿在手上的是写着这种药水的那一瓶；
 * 配的不是药水瓶、药水种类写错，不进世界一次报全。注册表用替身。
 */
class UsePotionParamTest {

    /** 替身：本实例注册了抗火与虚弱两种药水。 */
    private record FakeIds() implements ReadsRegisteredIds {
        @Override public List<String> ids(IdKind kind) {
            return kind == IdKind.POTION ? List.of("minecraft:fire_resistance", "minecraft:weakness") : List.of();
        }

        @Override public List<String> tags(IdKind kind) {
            return List.of();
        }
    }

    // 只看参数怎么读，用不到读世界、备手这些服务。
    private final UseModule module = new UseModule(null, new FakeIds());

    private StepDecision decide(JsonObject params) {
        ParseResult parsed = module.spec().paramSpecs().parse(params);
        if (parsed.params() == null) throw new IllegalArgumentException("测试参数没过参数规格：" + parsed.errors());
        Goal goal = Goal.of("maicraft:use", null, parsed.params());
        return module.decide(new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                return null;
            }
        });
    }

    @Test
    void 潜行与写字同时给_不进世界() {
        // 潜行右键告示牌打不开编辑界面：两样一起给按参数用得不对报回。
        JsonObject params = new JsonObject();
        params.addProperty("sneak", true);
        params.addProperty("text", "你好");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decide(params));
        assertTrue(finish.result().problem().message().contains("sneak"));
    }

    @Test
    void 给了潜行_任务输入带上潜行() {
        JsonObject params = new JsonObject();
        params.addProperty("sneak", true);
        params.addProperty("item", "minecraft:golden_apple");
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(params));
        assertTrue(((UseInput) run.input()).sneak());
    }

    @Test
    void 只给药水种类_拿写着这种药水的普通药水瓶() {
        JsonObject params = new JsonObject();
        params.addProperty("potion", "minecraft:fire_resistance");
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(params));
        assertEquals("minecraft:potion[potion=minecraft:fire_resistance]", ((UseInput) run.input()).item());
    }

    @Test
    void 配喷溅药水瓶照样认() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:splash_potion");
        params.addProperty("potion", "minecraft:weakness");
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(params));
        assertEquals("minecraft:splash_potion[potion=minecraft:weakness]", ((UseInput) run.input()).item());
    }

    @Test
    void 配的不是药水瓶_药水种类写错_一次报全() {
        JsonObject params = new JsonObject();
        params.addProperty("item", "minecraft:torch");
        params.addProperty("potion", "minecraft:fire_resistence");
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class, decide(params));
        String message = finish.result().problem().message();
        assertTrue(message.contains("potion 只配药水瓶用"), message);
        assertTrue(message.contains("你是不是要 minecraft:fire_resistance"), message);
    }
}
