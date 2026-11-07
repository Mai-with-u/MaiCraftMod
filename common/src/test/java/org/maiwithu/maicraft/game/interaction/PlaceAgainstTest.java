// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 对着一格放东西要不要潜行：被点的那格会截走右键才蹲（往上垫脚、施工、插火把都按它）。
 * 没有方块实体也会截走右键的音符盒、中继器、蛋糕、拉杆、花盆这类同样要蹲；普通方块不蹲。
 */
class PlaceAgainstTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 有方块实体的和门按钮拉杆_点之前要蹲() {
        assertTrue(PlaceAgainst.takesRightClick(Blocks.CHEST.defaultBlockState()));
        assertTrue(PlaceAgainst.takesRightClick(Blocks.FURNACE.defaultBlockState()));
        assertTrue(PlaceAgainst.takesRightClick(Blocks.COMPARATOR.defaultBlockState()), "比较器有方块实体");
        assertTrue(PlaceAgainst.takesRightClick(Blocks.OAK_DOOR.defaultBlockState()));
        assertTrue(PlaceAgainst.takesRightClick(Blocks.OAK_TRAPDOOR.defaultBlockState()));
        assertTrue(PlaceAgainst.takesRightClick(Blocks.STONE_BUTTON.defaultBlockState()));
        assertTrue(PlaceAgainst.takesRightClick(Blocks.LEVER.defaultBlockState()));
    }

    @Test
    void 没有方块实体也截走右键的_音符盒中继器蛋糕花盆堆肥桶龙蛋重生锚_也要蹲() {
        assertTrue(PlaceAgainst.takesRightClick(Blocks.NOTE_BLOCK.defaultBlockState()), "右键调音");
        assertTrue(PlaceAgainst.takesRightClick(Blocks.REPEATER.defaultBlockState()), "右键调档");
        assertTrue(PlaceAgainst.takesRightClick(Blocks.REDSTONE_WIRE.defaultBlockState()), "右键换形");
        assertTrue(PlaceAgainst.takesRightClick(Blocks.CAKE.defaultBlockState()), "饿着就吃一口");
        assertTrue(PlaceAgainst.takesRightClick(Blocks.CANDLE_CAKE.defaultBlockState()));
        assertTrue(PlaceAgainst.takesRightClick(Blocks.FLOWER_POT.defaultBlockState()), "空花盆点了什么都不放");
        assertTrue(PlaceAgainst.takesRightClick(Blocks.COMPOSTER.defaultBlockState()));
        assertTrue(PlaceAgainst.takesRightClick(Blocks.DRAGON_EGG.defaultBlockState()), "龙蛋会瞬移");
        assertTrue(PlaceAgainst.takesRightClick(Blocks.RESPAWN_ANCHOR.defaultBlockState()), "主世界里点充过的重生锚会炸");
    }

    @Test
    void 普通方块不蹲() {
        assertFalse(PlaceAgainst.takesRightClick(Blocks.STONE.defaultBlockState()));
        assertFalse(PlaceAgainst.takesRightClick(Blocks.DIRT.defaultBlockState()));
        assertFalse(PlaceAgainst.takesRightClick(Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(PlaceAgainst.takesRightClick(Blocks.GLASS.defaultBlockState()));
    }
}
