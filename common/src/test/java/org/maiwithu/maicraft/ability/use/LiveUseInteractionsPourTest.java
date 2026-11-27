// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;

/** 流体桶交互路由校验：工作盆与炼药锅等可直倒流体方块走方块右键本体，普通实心方块仍走往外侧邻格倒水。 */
class LiveUseInteractionsPourTest {

    private static final BlockPos FEET = new BlockPos(12, 64, 12);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 炼药锅直接接收流体桶_普通实心方块不直接接收() {
        assertTrue(LiveUseInteractions.acceptsFluidDirectly(Blocks.CAULDRON.defaultBlockState(), "minecraft:water_bucket"),
                "原版空炼药锅应通过方块右键直接接收水桶");
        assertTrue(LiveUseInteractions.acceptsFluidDirectly(Blocks.WATER_CAULDRON.defaultBlockState(), "minecraft:water_bucket"),
                "原版水炼药锅应走方块右键而非往旁格泼水");
        assertFalse(LiveUseInteractions.acceptsFluidDirectly(Blocks.STONE.defaultBlockState(), "minecraft:water_bucket"),
                "普通石头不直接接收流体，应继续往外侧邻格倒水");
    }

    @Test
    void 可直倒流体目标不走朝邻格泼水_普通实心方块与空桶舀水保持原路由() {
        ItemUseAim.Around directFluidBlock = new ItemUseAim.Around(FEET, Set.of(), Set.of(),
                ItemUseAim.AboveCell.OPEN, false, false, true);
        ItemUseAim.Around plainBlock = new ItemUseAim.Around(FEET, Set.of(), Set.of(),
                ItemUseAim.AboveCell.OPEN, false, false, false);

        assertFalse(LiveUseInteractions.usesHeldItemToward(ItemUseAim.Gesture.POUR, directFluidBlock),
                "持流体桶点工作盆或炼药锅时必须走 useBlock/useBlockFace，严禁走 useHeldItemToward 往邻格泼水");
        assertTrue(LiveUseInteractions.usesHeldItemToward(ItemUseAim.Gesture.POUR, plainBlock),
                "持流体桶点普通实心方块仍走朝效果邻格倒水");
        assertTrue(LiveUseInteractions.usesHeldItemToward(ItemUseAim.Gesture.SCOOP, plainBlock),
                "空桶舀流体源格仍走物品射线交互");
        assertTrue(LiveUseInteractions.usesHeldItemToward(ItemUseAim.Gesture.OWN_USE, plainBlock),
                "放船等自带落点物品仍走使用手里物品");
    }

    @Test
    void 显式潜行持流体桶点炼药锅时保留原版绕过方块行为语义() {
        assertTrue(LiveUseInteractions.acceptsFluidDirectly(
                        Blocks.CAULDRON.defaultBlockState(), "minecraft:water_bucket", false),
                "非潜行点炼药锅应走方块右键直接接收流体");
        assertFalse(LiveUseInteractions.acceptsFluidDirectly(
                        Blocks.CAULDRON.defaultBlockState(), "minecraft:water_bucket", true),
                "显式潜行点炼药锅应绕过直接接收流体，保留原版潜行倒水语义");
    }
}
