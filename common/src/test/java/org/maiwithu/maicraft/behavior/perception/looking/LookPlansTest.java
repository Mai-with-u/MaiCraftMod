// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.container;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.openable;
import static org.maiwithu.maicraft.behavior.perception.looking.LookingDoubles.spot;

/** 刚读了什么 → 这一回怎么看：每条读信息的路径对应的看法、开箱的五条、只能转头时不开界面。 */
class LookPlansTest {

    private final Random random = new Random(5);

    @Test
    void 读自己打开背包两三秒_只能转头时不做() {
        LookPlans.Plan plan = LookPlans.self(true, random);
        assertEquals(1, plan.steps().size());
        assertInstanceOf(LookPlans.Screen.Inventory.class, plan.steps().get(0).screen());
        assertInstanceOf(LookTarget.Standing.class, plan.steps().get(0).target(), "看背包不转头");
        int hold = plan.steps().get(0).holdTicks();
        assertTrue(hold >= 40 && hold <= 60, "两三秒：" + hold);
        assertTrue(LookPlans.self(false, random).empty(), "目标在等回答时不开背包");
    }

    @Test
    void 读周围挑两三样方向不同的东西从左到右看() {
        // 角色朝南（偏航 0）：东边（-X 是西，+X 是东）在左手，西边在右手。
        List<LookingWorld.Sighted> spots = List.of(spot("a", 5, 0, 5), spot("b", 5, 0, 6), spot("c", -5, 0, 5),
                spot("d", 0, 0, -8), spot("e", 8, 0, 0));
        for (int i = 0; i < 30; i++) {
            LookPlans.Plan plan = LookPlans.surroundings(spots, 0f, random);
            assertFalse(plan.lookAround());
            assertTrue(plan.steps().size() >= 2 && plan.steps().size() <= 3, "两三样：" + plan.steps().size());
            List<Float> yaws = new ArrayList<>();
            for (LookPlans.Step step : plan.steps()) {
                LookTarget.Spot target = (LookTarget.Spot) step.target();
                yaws.add(Aim.between(0, LookingDoubles.EYE_Y, 0, target.x(), target.y(), target.z()).yaw());
                assertTrue(step.holdTicks() >= 16 && step.holdTicks() <= 30, "每样零点八到一点五秒");
            }
            for (int j = 1; j < yaws.size(); j++) {
                assertTrue(yaws.get(j) > yaws.get(j - 1), "从左到右：" + yaws);
                assertTrue(yaws.get(j) - yaws.get(j - 1) >= 30f, "方向不同：" + yaws);
            }
        }
    }

    @Test
    void 周围什么都没有就原地环顾一圈() {
        LookPlans.Plan plan = LookPlans.surroundings(List.of(), 0f, random);
        assertTrue(plan.lookAround());
        assertFalse(plan.empty());
    }

    @Test
    void 手边的容器五条都满足才开_一条不满足就只看() {
        assertInstanceOf(LookPlans.Screen.Container.class,
                LookPlans.seen(container("b1", 1, 0, 1, openable(1, 0, 1)), true, random).steps().get(0).screen());
        WorldPosition at = WorldPosition.here(1, 0, 1);
        List<LookingWorld.ContainerFacts> refused = List.of(
                new LookingWorld.ContainerFacts(at, "minecraft:chest", false, true, false, false),
                new LookingWorld.ContainerFacts(at, "minecraft:chest", true, false, false, false),
                new LookingWorld.ContainerFacts(at, "minecraft:trapped_chest", true, true, true, false),
                new LookingWorld.ContainerFacts(at, "minecraft:chest", true, true, false, true));
        for (LookingWorld.ContainerFacts facts : refused) {
            LookPlans.Plan plan = LookPlans.seen(container("b1", 1, 0, 1, facts), true, random);
            assertInstanceOf(LookPlans.Screen.None.class, plan.steps().get(0).screen(), facts.toString());
            assertInstanceOf(LookTarget.Spot.class, plan.steps().get(0).target(), "只看过去");
        }
        assertInstanceOf(LookPlans.Screen.None.class,
                LookPlans.seen(container("b1", 1, 0, 1, openable(1, 0, 1)), false, random).steps().get(0).screen(),
                "目标在等回答时容器只看");
    }

    @Test
    void 看告示牌两到三秒_看别的一点五到二点五秒() {
        LookingWorld.Sighted at = spot("f2", 3, 0, 3);
        LookingWorld.Sighted sign = new LookingWorld.Sighted("f2", at.target(), LookingWorld.Kind.SIGN, at.yaw(), 4, null);
        for (int i = 0; i < 30; i++) {
            int signHold = LookPlans.seen(sign, true, random).steps().get(0).holdTicks();
            assertTrue(signHold >= 40 && signHold <= 60, "告示牌：" + signHold);
            int thingHold = LookPlans.seen(at, true, random).steps().get(0).holdTicks();
            assertTrue(thingHold >= 30 && thingHold <= 50, "别的：" + thingHold);
        }
    }

    @Test
    void 一片机器从左到右最多扫五台_找到的东西盯着第一个看() {
        List<LookingWorld.Sighted> machines = new ArrayList<>();
        for (int i = 0; i < 7; i++) machines.add(spot("m" + i, 6 - 2 * i, 0, 6));
        LookPlans.Plan sweep = LookPlans.places(machines, true, 0f, random);
        assertEquals(5, sweep.steps().size(), "最多五台");
        for (LookPlans.Step step : sweep.steps()) {
            assertTrue(step.holdTicks() >= 12 && step.holdTicks() <= 20, "每台零点六到一秒");
        }
        double firstX = ((LookTarget.Spot) sweep.steps().get(0).target()).x();
        double lastX = ((LookTarget.Spot) sweep.steps().get(4).target()).x();
        assertTrue(firstX > lastX, "朝南时 +X 在左手，先看左边");

        LookPlans.Plan found = LookPlans.places(machines, false, 0f, random);
        assertEquals(1, found.steps().size());
        assertEquals(machines.get(0).target(), found.steps().get(0).target(), "只看最近的那个");
        assertTrue(LookPlans.places(List.of(), true, 0f, random).empty());
    }

    @Test
    void 查配方装了查看器翻配方页_没装开背包两秒_只能转头时不做() {
        LookPlans.Plan page = LookPlans.recipe("minecraft:torch", true, true, true, random);
        LookPlans.Screen.Recipe recipe = (LookPlans.Screen.Recipe) page.steps().get(0).screen();
        assertEquals("minecraft:torch", recipe.itemId());
        assertTrue(recipe.uses());
        int hold = page.steps().get(0).holdTicks();
        assertTrue(hold >= 60 && hold <= 80, "三四秒：" + hold);

        LookPlans.Plan noViewer = LookPlans.recipe("minecraft:torch", false, false, true, random);
        assertInstanceOf(LookPlans.Screen.Inventory.class, noViewer.steps().get(0).screen());
        assertEquals(40, noViewer.steps().get(0).holdTicks());

        assertTrue(LookPlans.recipe("minecraft:torch", false, true, false, random).empty());
    }

    @Test
    void 六十四格外算远() {
        assertFalse(LookPlans.far(64));
        assertTrue(LookPlans.far(64.5));
    }
}
