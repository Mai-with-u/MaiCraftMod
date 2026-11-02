// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.GearSlotName;

/**
 * 拿东西清点身上几件时连穿着的护甲一起数：穿回身上的那件照样算拿到了；脱下来放进背包件数不变；
 * 主手、副手那两格不在这里数（主手本来就在快捷栏里，副手另有清点）；还空几格照旧只看背包主格。
 */
class BackpackWithArmorTest {

    private static final String NETHERITE_CHESTPLATE = "minecraft:netherite_chestplate";

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final Map<GearSlotName, String> worn = new EnumMap<>(GearSlotName.class);
    private final BackpackWithArmor carried = new BackpackWithArmor(backpack,
            slot -> Optional.ofNullable(worn.get(slot)).map(item -> new BackpackStack(item, 1, 1, true, false, false, false)));

    private int count(String itemId) {
        return CarriedItems.matching(carried, null, WantedItem.ofItem(itemId), new FakeTags());
    }

    @Test
    void 穿在身上的护甲也算身上有的() {
        worn.put(GearSlotName.CHEST, NETHERITE_CHESTPLATE);
        backpack.addGear(NETHERITE_CHESTPLATE);

        assertEquals(2, count(NETHERITE_CHESTPLATE));
    }

    @Test
    void 脱下来放进背包_件数不变() {
        worn.put(GearSlotName.CHEST, NETHERITE_CHESTPLATE);
        int before = count(NETHERITE_CHESTPLATE);
        worn.remove(GearSlotName.CHEST);
        backpack.addGear(NETHERITE_CHESTPLATE);

        assertEquals(before, count(NETHERITE_CHESTPLATE));
    }

    @Test
    void 主手副手不在这里数_空格照旧只看背包主格() {
        worn.put(GearSlotName.OFFHAND, "minecraft:shield");
        worn.put(GearSlotName.HEAD, "minecraft:diamond_helmet");
        backpack.add("minecraft:cobblestone", 10);

        assertEquals(0, count("minecraft:shield"));
        assertEquals(35, carried.freeSlots());
    }
}
