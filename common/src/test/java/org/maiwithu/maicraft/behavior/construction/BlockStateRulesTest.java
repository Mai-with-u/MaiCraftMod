// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 抄现场时哪些方块属性算数：运行态的剔除、放法定下的保留，机器抄现场与建筑读现场共用同一份取舍。 */
class BlockStateRulesTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 运行态属性一律剔除() {
        // 亮灭、充能、开关、含水、伸出这些转起来就会变：机器抄现场不写进蓝图，写进去建完一比对全是状态不对
        assertTrue(BlockStateRules.isRuntimeProperty("lit"));
        assertTrue(BlockStateRules.isRuntimeProperty("powered"));
        assertTrue(BlockStateRules.isRuntimeProperty("open"));
        assertTrue(BlockStateRules.isRuntimeProperty("waterlogged"));
        assertTrue(BlockStateRules.isRuntimeProperty("extended"));
        assertTrue(BlockStateRules.isRuntimeProperty("state"));
    }

    @Test
    void 放置属性不算运行态() {
        // 两份取舍不打架：朝向、上下半这些放法定下的属性，机器侧也不当成运行态剔掉
        for (String name : new String[]{"facing", "half", "hinge", "part", "axis", "face", "rotation",
                "attachment", "hanging", "orientation"}) {
            assertFalse(BlockStateRules.isRuntimeProperty(name), name + " 是放法定下的，不是运行态");
        }
    }

    @Test
    void 放置属性照实记_其他不带() {
        BlockState furnace = Blocks.FURNACE.defaultBlockState();
        assertTrue(BlockStateRules.isPlacementProperty(furnace, property(furnace, "facing")), "朝向是放法定下的");
        assertFalse(BlockStateRules.isPlacementProperty(furnace, property(furnace, "lit")), "亮灭不是放法定下的");
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        assertTrue(BlockStateRules.isPlacementProperty(door, property(door, "hinge")), "铰链是放法定下的");
    }

    @Test
    void 半砖的上下片与双层算放置属性_别的方块的type不算() {
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState();
        assertTrue(BlockStateRules.isPlacementProperty(slab, property(slab, "type")), "半砖的上下片、双层是放法定下的");
        BlockState chest = Blocks.CHEST.defaultBlockState();
        assertFalse(BlockStateRules.isPlacementProperty(chest, property(chest, "type")), "箱子单双联的 type 不照抄");
    }

    private static Property<?> property(BlockState state, String name) {
        return state.getProperties().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }
}
