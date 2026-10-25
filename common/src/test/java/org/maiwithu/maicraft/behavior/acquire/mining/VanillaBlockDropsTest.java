// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原版方块掉落对照：矿石对上矿、石头掉圆石、泥土对上自己，玻璃什么也不掉；概率掉落按原版的数，时运按工具算。 */
class VanillaBlockDropsTest {

    @Test
    void oresPointAtTheirOreBlocks() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:coal")
                .contains("minecraft:coal_ore"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:coal")
                .contains("minecraft:deepslate_coal_ore"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:raw_iron")
                .contains("minecraft:iron_ore"));
    }

    @Test
    void stoneFamilyDropsTheCobbledForm() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:cobblestone")
                .contains("minecraft:stone"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:cobbled_deepslate")
                .contains("minecraft:deepslate"));
    }

    @Test
    void commonUndergroundStonesAreWorthDiggingDownFor() {
        // 要圆石、安山岩这类：看不见就往下挖楼梯去找；原木、铁锭不是挖楼梯挖得到的。
        assertTrue(VanillaBlockDrops.buriedUnderfoot("minecraft:cobblestone"));
        assertTrue(VanillaBlockDrops.buriedUnderfoot("minecraft:andesite"));
        assertTrue(VanillaBlockDrops.buriedUnderfoot("minecraft:cobbled_deepslate"));
        assertFalse(VanillaBlockDrops.buriedUnderfoot("minecraft:oak_log"));
        assertFalse(VanillaBlockDrops.buriedUnderfoot("minecraft:iron_ingot"));
    }

    @Test
    void blocksWithTheSameNameFallBackToThemselves() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:dirt")
                .contains("minecraft:dirt"));
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:dirt")
                .contains("minecraft:grass_block"));
        assertEquals("minecraft:sand", VanillaBlockDrops.droppedBy("minecraft:sand"));
    }

    @Test
    void blocksThatDropSomethingElseDoNotDropThemselves() {
        // 要石头、要钻石矿石：挖石头掉圆石、挖矿石掉矿物，都不是这次要的东西。
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:stone").isEmpty());
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:diamond_ore").isEmpty());
    }

    @Test
    void glassDropsNothingAtAll() {
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:glass").isEmpty());
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:ice").isEmpty());
    }

    @Test
    void chanceDropsAreKeptApartFromCertainDrops() {
        // 必掉的对照里没有它们：燧石、种子不是"挖了就掉"的。
        assertTrue(VanillaBlockDrops.blocksDropping("minecraft:flint").stream().noneMatch("minecraft:gravel"::equals));
        assertEquals(List.of("minecraft:gravel"), blocksOf("minecraft:flint"));
        assertTrue(blocksOf("minecraft:wheat_seeds").containsAll(List.of("minecraft:short_grass", "minecraft:tall_grass",
                "minecraft:fern", "minecraft:large_fern")));
        assertTrue(blocksOf("minecraft:oak_sapling").contains("minecraft:oak_leaves"));
        assertTrue(blocksOf("minecraft:stick").contains("minecraft:mangrove_leaves"), "红树树叶不掉树苗、照样出木棍");
        assertEquals(List.of("minecraft:oak_leaves", "minecraft:dark_oak_leaves"), blocksOf("minecraft:apple"));
        assertTrue(VanillaBlockDrops.chanceSources("minecraft:iron_ingot").isEmpty());
    }

    @Test
    void chancesFollowVanillaLootTablesAndFortune() {
        assertEquals(0.1, odds("minecraft:gravel", "minecraft:flint", 0).chance(), 1e-6);
        assertEquals(0.142857, odds("minecraft:gravel", "minecraft:flint", 1).chance(), 1e-6);
        assertEquals(0.25, odds("minecraft:gravel", "minecraft:flint", 2).chance(), 1e-6);
        assertTrue(odds("minecraft:gravel", "minecraft:flint", 3).certain(), "时运 III 必出燧石");
        assertEquals(0.125, odds("minecraft:short_grass", "minecraft:wheat_seeds", 0).chance(), 1e-6);
        // 草出种子：时运不改机会、改件数，每级平均多一颗。
        assertEquals(0.125, odds("minecraft:short_grass", "minecraft:wheat_seeds", 2).chance(), 1e-6);
        assertEquals(3, odds("minecraft:short_grass", "minecraft:wheat_seeds", 2).meanCount(), 1e-6);
        assertEquals(0.05, odds("minecraft:oak_leaves", "minecraft:oak_sapling", 0).chance(), 1e-6);
        assertEquals(0.025, odds("minecraft:jungle_leaves", "minecraft:jungle_sapling", 0).chance(), 1e-6);
        assertEquals(0.02, odds("minecraft:birch_leaves", "minecraft:stick", 0).chance(), 1e-6);
        assertEquals(0.005, odds("minecraft:dark_oak_leaves", "minecraft:apple", 0).chance(), 1e-6);
        assertEquals("每块 12.5%", odds("minecraft:fern", "minecraft:wheat_seeds", 0).describe());
        assertEquals("每块 0.5%", odds("minecraft:oak_leaves", "minecraft:apple", 0).describe());
    }

    @Test
    void onlyGravelDropsItselfWhenTheChanceMisses() {
        assertTrue(VanillaBlockDrops.chanceSources("minecraft:flint").getFirst().elseDropsItself());
        assertTrue(VanillaBlockDrops.chanceDropsOf("minecraft:oak_leaves").stream().noneMatch(
                VanillaBlockDrops.ChanceDrop::elseDropsItself), "树叶不拿剪刀挖不掉自己");
    }

    private static List<String> blocksOf(String itemId) {
        return VanillaBlockDrops.chanceSources(itemId).stream().map(VanillaBlockDrops.ChanceDrop::blockType).toList();
    }

    private static DropOdds odds(String blockType, String itemId, int fortune) {
        return VanillaBlockDrops.chanceDropsOf(blockType).stream().filter(drop -> drop.itemId().equals(itemId))
                .findFirst().orElseThrow().odds(fortune);
    }
}
