// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.acquire.cook.RegistryToolRequirements.ToolCandidate;

/** 缺工具时去弄哪一把：每把都够格给整类标签；有不够格的给够格里档位最低（耐久最低）的那把。 */
class RegistryToolRequirementsTest {

    private static final String PICKAXES = "#minecraft:pickaxes";

    // 原版六把镐的耐久；够不够格按这次要挖的方块填。
    private static List<ToolCandidate> pickaxes(boolean wood, boolean stone, boolean iron, boolean gold,
            boolean diamond, boolean netherite) {
        return List.of(new ToolCandidate("minecraft:wooden_pickaxe", 59, wood),
                new ToolCandidate("minecraft:stone_pickaxe", 131, stone),
                new ToolCandidate("minecraft:iron_pickaxe", 250, iron),
                new ToolCandidate("minecraft:golden_pickaxe", 32, gold),
                new ToolCandidate("minecraft:diamond_pickaxe", 1561, diamond),
                new ToolCandidate("minecraft:netherite_pickaxe", 2031, netherite));
    }

    @Test
    void 挖石头每把都够格_给整类标签由引擎挑最省的() {
        assertEquals(PICKAXES, RegistryToolRequirements.lowestSufficient(PICKAXES,
                pickaxes(true, true, true, true, true, true)));
    }

    @Test
    void 挖远古残骸_要钻石镐_不是随便一把镐() {
        // 身上只有铁镐时要残骸：要的若是整类标签，引擎会做出一把木镐，下去挖了也不掉。
        assertEquals("minecraft:diamond_pickaxe", RegistryToolRequirements.lowestSufficient(PICKAXES,
                pickaxes(false, false, false, false, true, true)));
    }

    @Test
    void 挖钻石矿要铁镐_挖铁矿要石镐_金镐耐久最低也不会被挑上() {
        assertEquals("minecraft:iron_pickaxe", RegistryToolRequirements.lowestSufficient(PICKAXES,
                pickaxes(false, false, true, false, true, true)));
        assertEquals("minecraft:stone_pickaxe", RegistryToolRequirements.lowestSufficient(PICKAXES,
                pickaxes(false, true, true, false, true, true)));
    }

    @Test
    void 一把都不够格_问不出来_照旧给整类标签() {
        assertEquals(PICKAXES, RegistryToolRequirements.lowestSufficient(PICKAXES,
                pickaxes(false, false, false, false, false, false)));
        assertEquals(PICKAXES, RegistryToolRequirements.lowestSufficient(PICKAXES, List.of()));
    }
}
