// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.DigTool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 读 dig_tool：hand 与物品 ID 两种写法；不带命名空间的按 minecraft 补，大小写整理后说明一次。 */
class DigToolReaderTest {

    private final RequestCheck check = new RequestCheck();

    private DigTool read(String json) {
        return DigToolReader.read(JsonParser.parseString(json), "goal.dig_tool", null, check);
    }

    @Test
    void namespacedItemIsKeptAsWritten() {
        assertEquals(new DigTool.Item("minecraft:stone_pickaxe"), read("\"minecraft:stone_pickaxe\""));
        assertTrue(check.ok(), check.errors()::toString);
        assertTrue(check.notes().isEmpty(), check.notes()::toString);
    }

    @Test
    void bareItemIdGetsTheMinecraftNamespace() {
        // 小模型常写 stone_pickaxe：补上命名空间，并按最终取到的 ID 说一声。
        assertEquals(new DigTool.Item("minecraft:stone_pickaxe"), read("\"stone_pickaxe\""));
        assertTrue(check.ok(), check.errors()::toString);
        assertEquals(1, check.notes().size(), check.notes()::toString);
        assertTrue(check.notes().getFirst().contains("minecraft:stone_pickaxe"), check.notes()::toString);
    }

    @Test
    void itemIdIsLowercasedAndSaidSo() {
        assertEquals(new DigTool.Item("minecraft:diamond_pickaxe"), read("\"Minecraft:Diamond_Pickaxe\""));
        assertTrue(check.ok(), check.errors()::toString);
        assertTrue(check.notes().getFirst().contains("\"Minecraft:Diamond_Pickaxe\""), check.notes()::toString);
    }

    @Test
    void handIsTheBareHand() {
        assertEquals(new DigTool.Hand(), read("\"hand\""));
        assertTrue(check.ok(), check.errors()::toString);
    }

    @Test
    void handIgnoresCaseAndSaysSo() {
        assertEquals(new DigTool.Hand(), read("\"HAND\""));
        assertTrue(check.ok(), check.errors()::toString);
        assertEquals(1, check.notes().size(), check.notes()::toString);
    }

    @Test
    void absentValueKeepsWhatTheWholeThingSaid() {
        DigTool inherited = new DigTool.Item("minecraft:iron_pickaxe");
        DigTool read = DigToolReader.read(JsonParser.parseString("null"), "goal.dig_tool", inherited, check);

        assertEquals(inherited, read);
        assertTrue(check.ok(), check.errors()::toString);
    }

    @Test
    void blankValueIsAnError() {
        read("\"   \"");

        assertFalse(check.ok());
        assertEquals("goal.dig_tool", check.errors().getFirst().field());
        assertTrue(check.errors().getFirst().message().contains("不能为空"), check.errors()::toString);
    }

    @Test
    void nonTextValueIsAnError() {
        read("3");

        assertFalse(check.ok());
        assertEquals("goal.dig_tool", check.errors().getFirst().field());
        assertTrue(check.errors().getFirst().expected().contains("minecraft:stone_pickaxe"),
                check.errors()::toString);
    }
}
