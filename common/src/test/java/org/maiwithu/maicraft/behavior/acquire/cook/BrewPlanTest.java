// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.StackKey;

/**
 * 酿一种药水的一整条：从替身酿造表里倒着找，身上够数的药水接得上就从它接着酿、接不上从水瓶开始，
 * 缺得少的那条先；一炉最多三瓶，要几瓶酿几瓶，超过三瓶分组；等这一炉的时候还要备的按剩下的炉数算。
 */
class BrewPlanTest {

    private static final StackKey WATER = BrewPlan.WATER_BOTTLE;
    private static final StackKey AWKWARD = potion("minecraft:awkward");
    private static final StackKey FIRE = potion("minecraft:fire_resistance");
    private static final StackKey LONG_FIRE = potion("minecraft:long_fire_resistance");
    private static final StackKey WEAKNESS = potion("minecraft:weakness");
    private static final StackKey SPLASH_WEAKNESS = new StackKey("minecraft:splash_potion", "minecraft:weakness");
    private static final StackKey MUNDANE = potion("minecraft:mundane");

    /** 替身酿造表：原版里的几步，外加一条走不通的支路（水瓶加红石变平凡的药水）。 */
    private static final List<BrewStep> TABLE = List.of(
            new BrewStep(WATER, "minecraft:nether_wart", AWKWARD),
            new BrewStep(WATER, "minecraft:redstone", MUNDANE),
            new BrewStep(AWKWARD, "minecraft:magma_cream", FIRE),
            new BrewStep(FIRE, "minecraft:redstone", LONG_FIRE),
            new BrewStep(WATER, "minecraft:fermented_spider_eye", WEAKNESS),
            new BrewStep(WEAKNESS, "minecraft:gunpowder", SPLASH_WEAKNESS));

    private final Map<String, Integer> carried = new HashMap<>();

    private static StackKey potion(String kind) {
        return new StackKey(StackKey.PLAIN_POTION, kind);
    }

    private int carried(String key) {
        return carried.getOrDefault(key, 0);
    }

    private BrewPlan plan(StackKey target, int bottles) {
        return BrewPlan.plan(TABLE, target, bottles, this::carried).orElseThrow();
    }

    @Test
    void 身上什么都没有_从水瓶起两步酿出抗火药水() {
        BrewPlan plan = plan(FIRE, 3);
        assertEquals(WATER, plan.start());
        assertEquals(List.of("minecraft:nether_wart", "minecraft:magma_cream"),
                plan.steps().stream().map(BrewStep::ingredient).toList());
        assertEquals(FIRE, plan.target());
        assertEquals(5, plan.missing(this::carried), "三瓶水、地狱疣、岩浆膏都缺");
    }

    @Test
    void 身上有够数的粗制的药水_从它接着酿() {
        carried.put(AWKWARD.written(), 3);
        BrewPlan plan = plan(FIRE, 3);
        assertEquals(AWKWARD, plan.start());
        assertEquals(1, plan.steps().size());
    }

    @Test
    void 粗制的药水不够这次要的瓶数_照旧从水瓶起() {
        carried.put(AWKWARD.written(), 1);
        assertEquals(WATER, plan(FIRE, 3).start());
    }

    @Test
    void 延长的和喷溅的也是表里的一步() {
        assertEquals(3, plan(LONG_FIRE, 1).steps().size());
        BrewPlan splash = plan(SPLASH_WEAKNESS, 1);
        assertEquals(List.of("minecraft:fermented_spider_eye", "minecraft:gunpowder"),
                splash.steps().stream().map(BrewStep::ingredient).toList());
    }

    @Test
    void 酿造表里没有一路酿得出的_给空() {
        assertTrue(BrewPlan.plan(TABLE, potion("minecraft:luck"), 1, this::carried).isEmpty());
    }

    @Test
    void 要几瓶酿几瓶_一炉最多三瓶_超过分组() {
        assertEquals(List.of(1), plan(FIRE, 1).groups());
        assertEquals(List.of(3, 3, 1), plan(FIRE, 7).groups());
        assertEquals(6, plan(FIRE, 7).brews(), "三组每组两步");
    }

    @Test
    void 等第一炉的时候_要备的是后面几步和后面几组的料() {
        BrewPlan plan = plan(FIRE, 4);
        // 第一组正酿第一步（地狱疣）：还要这一组的岩浆膏，第二组的一瓶水、地狱疣、岩浆膏。
        Map<String, Integer> want = plan.stillToUse(0, 1, -1);
        assertEquals(Map.of("minecraft:magma_cream", 2, WATER.written(), 1, "minecraft:nether_wart", 1), want);
    }

    @Test
    void 燃料条不够剩下的炉数才再备一个烈焰粉() {
        BrewPlan plan = plan(FIRE, 4);
        assertNull(plan.stillToUse(0, 1, 20).get(BrewPlan.BLAZE_POWDER), "还够二十炉不备");
        assertEquals(1, plan.stillToUse(0, 1, 2).get(BrewPlan.BLAZE_POWDER), "剩三炉、燃料只够两炉");
    }
}
