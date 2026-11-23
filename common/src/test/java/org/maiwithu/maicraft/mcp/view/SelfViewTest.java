// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;

/** 自身观察的背包视图：满耐久与无耐久物品照旧合并，受损工具按各自剩余耐久独立列出并带耐久字段。 */
class SelfViewTest {

    private static SceneSelf self() {
        return new SceneSelf(0.0, 64.0, 0.0, 0f, "南", 20f, 20, 300, 300, null, List.of(), List.of(),
                true, false, List.of(), null);
    }

    private static BackpackView backpack(List<BackpackStack> stacks) {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return stacks;
            }

            @Override public int usedSlots() {
                return stacks.size();
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
    }

    @Test
    void 受损工具按各自耐久独立列项_不与满耐久同类合并且满耐久不带耐久字段() {
        BackpackView view = backpack(List.of(
                new BackpackStack("minecraft:cobblestone", 64, 64, false, false, false, true),
                new BackpackStack("minecraft:cobblestone", 16, 64, false, false, false, true),
                new BackpackStack("minecraft:iron_pickaxe", 1, 1, true, false, false, false, null, 250, 250),
                new BackpackStack("minecraft:iron_pickaxe", 1, 1, true, false, false, false, null, 250, 250),
                new BackpackStack("minecraft:iron_pickaxe", 1, 1, true, false, false, false, null, 1, 250),
                new BackpackStack("minecraft:iron_pickaxe", 1, 1, true, false, false, false, null, 120, 250)));

        JsonObject json = SelfView.of(self(), "minecraft:overworld", view, null, null, null, false);
        JsonArray inventory = json.getAsJsonArray("inventory");
        assertEquals(4, inventory.size(), inventory.toString());

        JsonObject cobble = inventory.get(0).getAsJsonObject();
        assertEquals("minecraft:cobblestone", cobble.get("item").getAsString());
        assertEquals(80, cobble.get("count").getAsInt());
        assertFalse(cobble.has("durability"));
        assertFalse(cobble.has("max_durability"));

        JsonObject fullPicks = inventory.get(1).getAsJsonObject();
        assertEquals("minecraft:iron_pickaxe", fullPicks.get("item").getAsString());
        assertEquals(2, fullPicks.get("count").getAsInt());
        assertFalse(fullPicks.has("durability"));
        assertFalse(fullPicks.has("max_durability"));

        JsonObject nearlyBroken = inventory.get(2).getAsJsonObject();
        assertEquals("minecraft:iron_pickaxe", nearlyBroken.get("item").getAsString());
        assertEquals(1, nearlyBroken.get("count").getAsInt());
        assertEquals(1, nearlyBroken.get("durability").getAsInt());
        assertEquals(250, nearlyBroken.get("max_durability").getAsInt());

        JsonObject halfWorn = inventory.get(3).getAsJsonObject();
        assertEquals("minecraft:iron_pickaxe", halfWorn.get("item").getAsString());
        assertEquals(1, halfWorn.get("count").getAsInt());
        assertEquals(120, halfWorn.get("durability").getAsInt());
        assertEquals(250, halfWorn.get("max_durability").getAsInt());
    }

    @Test
    void 主手空副手拿盾时不写held只写offhand_两手都有物时各自独立写入() {
        SceneSelf offhandOnly = new SceneSelf(0.0, 64.0, 0.0, 0f, "南", 20f, 20, 300, 300,
                null, List.of(), List.of(), true, false, List.of(), "minecraft:shield");
        JsonObject offhandJson = SelfView.of(offhandOnly, "minecraft:overworld", null, null, null, null, false);
        assertFalse(offhandJson.has("held"), "主手空时不写 held，不拿副手盾牌顶替");
        assertEquals("minecraft:shield", offhandJson.get("offhand").getAsString());

        SceneSelf bothHands = new SceneSelf(0.0, 64.0, 0.0, 0f, "南", 20f, 20, 300, 300,
                "minecraft:potato", List.of(), List.of(), true, false, List.of(), "minecraft:shield");
        JsonObject bothJson = SelfView.of(bothHands, "minecraft:overworld", null, null, null, null, false);
        assertEquals("minecraft:potato", bothJson.get("held").getAsString());
        assertEquals("minecraft:shield", bothJson.get("offhand").getAsString());
    }
}
