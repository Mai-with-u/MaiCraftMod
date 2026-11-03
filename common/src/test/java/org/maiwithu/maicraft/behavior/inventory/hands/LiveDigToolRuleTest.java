// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.interaction.DigToolPlan;
import org.maiwithu.maicraft.game.interaction.DigTool;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 点名工具：写 hand 就腾空主手；写物品 ID 就在快捷栏选中、在主背包换到主手、已经在主手不动；
 * 身上没有那一件就明说没有，不换成别的工具。
 */
class LiveDigToolRuleTest {

    private static final String STONE_PICKAXE = "minecraft:stone_pickaxe";

    /** 背包前 36 格：0~8 快捷栏、9~35 主背包，没写到的都是空格。 */
    private static List<String> slots(String... itemIds) {
        List<String> slots = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            slots.add(slot < itemIds.length ? itemIds[slot] : "");
        }
        return slots;
    }

    private static DigToolPlan plan(DigTool wanted, int selected, String... itemIds) {
        return LiveDigToolRule.named(wanted, selected, slots(itemIds), offhand);
    }

    /** 副手那格：默认空着。 */
    private static String offhand = "";

    @Test
    void handMeansBareHand() {
        assertEquals(DigToolPlan.bareHand(), plan(new DigTool.Hand(), 0));
    }

    @Test
    void itemInTheHotbarIsSelected() {
        // 快捷栏第 3 格有那把镐：选中它再挖，不换到别处。
        assertEquals(DigToolPlan.hold(3), plan(new DigTool.Item(STONE_PICKAXE), 0, "", "", "", STONE_PICKAXE));
    }

    @Test
    void itemAlreadyInTheMainHandIsNotMoved() {
        assertEquals(DigToolPlan.keep(), plan(new DigTool.Item(STONE_PICKAXE), 0, STONE_PICKAXE));
    }

    @Test
    void itemInTheMainInventoryIsMovedToTheMainHand() {
        // 第 9 格是主背包第一格：换到主手再挖，换手下一条动作由挖掘器来发。
        assertEquals(DigToolPlan.hold(9), plan(new DigTool.Item(STONE_PICKAXE), 0, "", "", "", "", "", "", "", "",
                "", STONE_PICKAXE));
    }

    @Test
    void itemInAOtherHotbarSlotIsSelectedRatherThanDuplicated() {
        // 身上有两把同样的镐：按格子顺序拿先找到的那把，选中它。
        assertEquals(DigToolPlan.hold(2), plan(new DigTool.Item(STONE_PICKAXE), 5, "", "", STONE_PICKAXE, "", "",
                STONE_PICKAXE));
    }

    @Test
    void missingItemSaysWhichOneWasMissing() {
        assertEquals(DigToolPlan.missing(STONE_PICKAXE, "身上没有"),
                plan(new DigTool.Item(STONE_PICKAXE), 0, "", "", "minecraft:wooden_pickaxe"));
    }

    @Test
    void itemInTheOffhandIsReportedRatherThanSwappedOver() {
        // 副手放着的镐挖不了方块，这一版也不自动换过来：说清它在副手上，不报成身上没有。
        offhand = STONE_PICKAXE;
        try {
            assertEquals(DigToolPlan.missing(STONE_PICKAXE, "它在副手上，不自动换过来"),
                    plan(new DigTool.Item(STONE_PICKAXE), 0, "", "minecraft:iron_pickaxe"));
        } finally {
            offhand = "";
        }
    }
}
