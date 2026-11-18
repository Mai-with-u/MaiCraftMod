// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.PickedUpItems;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackStack;

/** 捡到的判断：核验拾取只数背包主格的新增；副手与护甲栏不吃原版拾取物，不在其中。 */
class PickedUpItemsTest {

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeTags tags = new FakeTags();

    @Test
    void 比基线多出来的算捡到的_少了的不算() {
        backpack.add("minecraft:coal", 10);
        Map<String, Integer> before = PickedUpItems.byItem(backpack);
        backpack.add("minecraft:coal", 5);
        backpack.add("minecraft:stick", 3);
        backpack.take("minecraft:coal", 2);

        assertEquals(Map.of("minecraft:coal", 3, "minecraft:stick", 3),
                PickedUpItems.gainedSince(before, backpack), "捡到的按新增记：现在有 13 件、开工 10 件，捡到 3 件");
    }

    @Test
    void 药水不拆种_捡到哪种都算这种物品() {
        Map<String, Integer> before = PickedUpItems.byItem(backpack);
        backpack.stacks.add(new BackpackStack(
                "minecraft:potion", 1, 1, false, false, false, false, "minecraft:fire_resistance"));

        assertEquals(Map.of("minecraft:potion", 1), PickedUpItems.gainedSince(before, backpack),
                "确认的是捡到了药水，不是哪种药水");
        assertEquals(1, PickedUpItems.countOf(backpack, "minecraft:potion"));
    }

    @Test
    void 要的东西按标签匹配_主背包之外不算() {
        tags.put("minecraft:oak_log", "minecraft:logs");
        backpack.add("minecraft:oak_log", 4);
        backpack.add("minecraft:stone", 30);
        assertEquals(4, PickedUpItems.matching(backpack, WantedItem.ofTag("minecraft:logs"), tags));
        assertEquals(4, PickedUpItems.matching(backpack, WantedItem.ofItem("minecraft:oak_log"), tags));
        assertEquals(0, PickedUpItems.matching(backpack, WantedItem.ofItem("minecraft:coal"), tags));
    }

    @Test
    void 什么都没捡到时差是空的() {
        Map<String, Integer> before = PickedUpItems.byItem(backpack);
        assertTrue(PickedUpItems.gainedSince(before, backpack).isEmpty());
    }
}
