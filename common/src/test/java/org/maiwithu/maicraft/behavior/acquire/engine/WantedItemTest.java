// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.StackKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;

/**
 * 想要的东西：具体物品按 ID 匹配，标签按物品挂着的标签匹配；写法与游戏里的注册 ID 一致。
 * 点名了药水种类的只认写着同一种的，分不出种类的不算；没点名的带种类写法照样按物品 ID 认。
 */
class WantedItemTest {

    @Test
    void 具体物品按ID相等匹配() {
        WantedItem wanted = WantedItem.ofItem("minecraft:coal");
        assertFalse(wanted.isTag());
        assertTrue(wanted.matches("minecraft:coal", Set.of()));
        assertFalse(wanted.matches("minecraft:charcoal", Set.of("minecraft:coals")));
    }

    @Test
    void 标签按物品挂着的标签匹配() {
        WantedItem wanted = WantedItem.ofTag("minecraft:logs");
        assertTrue(wanted.isTag());
        assertEquals("minecraft:logs", wanted.tagId());
        assertTrue(wanted.matches("minecraft:oak_log", Set.of("minecraft:logs", "minecraft:wood")));
        assertFalse(wanted.matches("minecraft:stone", Set.of()));
    }

    @Test
    void 点名了药水种类_只认写着同一种的() {
        WantedItem wanted = WantedItem.ofPotion("minecraft:potion", "minecraft:fire_resistance");
        assertEquals("minecraft:potion", wanted.itemId());
        assertEquals("minecraft:fire_resistance", wanted.potion());
        assertEquals("minecraft:potion[potion=minecraft:fire_resistance]", wanted.specifier());
        assertTrue(wanted.matches("minecraft:potion[potion=minecraft:fire_resistance]", Set.of()));
        assertFalse(wanted.matches("minecraft:potion[potion=minecraft:water]", Set.of()), "水瓶不算抗火药水");
        assertFalse(wanted.matches("minecraft:potion", Set.of()), "分不出种类的不算");
        assertFalse(wanted.matches("minecraft:splash_potion[potion=minecraft:fire_resistance]", Set.of()),
                "喷溅的不算普通的");
    }

    @Test
    void 没点名种类_带种类的写法照样按物品ID认() {
        WantedItem wanted = WantedItem.ofItem("minecraft:potion");
        assertTrue(wanted.matches("minecraft:potion[potion=minecraft:water]", Set.of()));
        assertTrue(wanted.matches(new BackpackStack("minecraft:potion", 1, 1, false, false, false, false,
                "minecraft:awkward"), Set.of()));
    }

    @Test
    void 带种类的写法拆得开_写坏的整串当物品ID() {
        StackKey key = StackKey.parse("minecraft:potion[potion=minecraft:long_fire_resistance]");
        assertEquals(new StackKey("minecraft:potion", "minecraft:long_fire_resistance"), key);
        assertEquals("minecraft:potion[potion=minecraft:long_fire_resistance]", key.written());
        assertEquals(new StackKey("minecraft:coal", null), StackKey.parse("minecraft:coal"));
        assertEquals("minecraft:potion[potion=", StackKey.parse("minecraft:potion[potion=").itemId());
    }

    @Test
    void 写给人看的一句话() {
        assertEquals("minecraft:coal", WantedItem.ofItem("minecraft:coal").describe());
        assertEquals("#minecraft:logs 里的任一物品", WantedItem.ofTag("minecraft:logs").describe());
    }
}
