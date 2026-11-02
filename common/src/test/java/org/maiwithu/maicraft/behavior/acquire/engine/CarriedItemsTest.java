// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;

/** 身上清点：主背包加副手一起数；按标签要的时候匹配也走标签。 */
class CarriedItemsTest {

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();

    @Test
    void 主背包与副手一起数() {
        backpack.add("minecraft:coal", 12);
        offhand.hold(new BackpackStack("minecraft:coal", 3, 64, false, false, false, false));
        int carried = CarriedItems.matching(backpack, offhand,
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 20, "火把"), tags);
        assertEquals(15, carried);
    }

    @Test
    void 药水按种类数_水瓶不算抗火药水() {
        backpack.stacks.add(potion("minecraft:water"));
        backpack.stacks.add(potion("minecraft:fire_resistance"));
        offhand.hold(potion("minecraft:fire_resistance"));

        assertEquals(2, CarriedItems.matching(backpack, offhand,
                WantedItem.ofPotion("minecraft:potion", "minecraft:fire_resistance"), tags));
        assertEquals(3, CarriedItems.matching(backpack, offhand, WantedItem.ofItem("minecraft:potion"), tags),
                "不点名种类任一种药水都算");
        assertEquals(Map.of("minecraft:potion[potion=minecraft:water]", 1,
                        "minecraft:potion[potion=minecraft:fire_resistance]", 2),
                CarriedItems.byItem(backpack, offhand), "按种类分开记，说得出进包的是哪种");
    }

    private static BackpackStack potion(String kind) {
        return new BackpackStack("minecraft:potion", 1, 1, false, false, false, false, kind);
    }

    @Test
    void 按标签要时数上所有挂标签的() {
        tags.put("minecraft:oak_log", "minecraft:logs");
        tags.put("minecraft:birch_log", "minecraft:logs");
        backpack.add("minecraft:oak_log", 4);
        backpack.add("minecraft:birch_log", 2);
        backpack.add("minecraft:stone", 30);
        int carried = CarriedItems.matching(backpack, offhand,
                new ItemRequest(WantedItem.ofTag("minecraft:logs"), 10, "搭桥垫脚"), tags);
        assertEquals(6, carried);
    }

    @Test
    void 身上有够格的工具就不再准备() {
        backpack.addGear("minecraft:iron_pickaxe");
        // 替身规则：铁矿石需要镐、铁镐够格；钻石矿石需要钻石镐、铁镐不够格。
        ReadsToolRequirements ironRules = new ReadsToolRequirements() {
            @Override public Optional<String> toolRequired(String blockType) {
                return Optional.of("minecraft:iron_pickaxe");
            }
            @Override public boolean sufficient(String toolItemId, String blockType) {
                return toolItemId.equals("minecraft:iron_pickaxe");
            }
        };
        assertTrue(CarriedItems.hasToolThatSuffices(backpack, offhand, "minecraft:iron_ore", ironRules));
        assertFalse(CarriedItems.hasToolThatSuffices(backpack, offhand, "minecraft:diamond_ore",
                new ReadsToolRequirements() {
                    @Override public Optional<String> toolRequired(String blockType) {
                        return Optional.of("minecraft:diamond_pickaxe");
                    }
                    @Override public boolean sufficient(String toolItemId, String blockType) {
                        return toolItemId.equals("minecraft:diamond_pickaxe");
                    }
                }));
    }
}
