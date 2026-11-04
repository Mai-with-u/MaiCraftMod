// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** 岩浆出口：沿身体穿得过去的格子找最近的岸，墙后的不算，3 格内没有就是空；没岸时看哪格能垫。 */
class LavaExitsTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    // 地面顶在 y=65（y=64 是实心），挖了一个一格深的岩浆池：东岸离人两格，西、南、北岸都在四格外，人泡在 (0,64,0)。
    private static BurningCellsFake shallowPool() {
        return new BurningCellsFake().floor(-8, -8, 8, 8, 63).floor(-8, -8, 8, 8, 64).lava(-3, -3, 1, 3, 64);
    }

    @Test
    void aShallowPoolLeadsToTheNearestBankOneLevelUp() {
        // 出口要上一层：东边的岸顶在 y=65，游两格贴过去再上一层就是。
        List<BlockPos> exits = LavaExits.find(shallowPool(), FEET, LavaExits.NEAR);
        assertEquals(new BlockPos(2, 65, 0), exits.get(0));
        assertTrue(LavaExits.exit(shallowPool(), new BlockPos(2, 65, 0)));
    }

    @Test
    void aTwoDeepPoolFindsTheBankBySwimmingUp() {
        // 沉在两格深的岩浆底（地面顶在 y=66，岩浆在 y=64、65）：岸比脚高两格，往上游一格就看得见，不先喝药垫块。
        BurningCellsFake pool = new BurningCellsFake().floor(-8, -8, 8, 8, 62).floor(-8, -8, 8, 8, 63)
                .floor(-8, -8, 8, 8, 64).floor(-8, -8, 8, 8, 65).lava(-3, -3, 1, 3, 64).lava(-3, -3, 1, 3, 65);
        List<BlockPos> exits = LavaExits.find(pool, FEET, LavaExits.NEAR);
        assertEquals(new BlockPos(2, 66, 0), exits.get(0));
    }

    @Test
    void anExitStandingInFireIsSkipped() {
        // 最近那格岸上烧着火：上了岸还得再迈一步，不选它，换旁边不着火的岸。
        BurningCellsFake pool = shallowPool();
        pool.fire.add(new BlockPos(2, 65, 0));
        List<BlockPos> exits = LavaExits.find(pool, FEET, LavaExits.NEAR);
        assertFalse(exits.contains(new BlockPos(2, 65, 0)));
        assertFalse(exits.isEmpty());
    }

    @Test
    void noBankWithinThreeIsEmpty() {
        BurningCellsFake lake = new BurningCellsFake().floor(-8, -8, 8, 8, 63).lava(-8, -8, 8, 8, 64);
        assertTrue(LavaExits.find(lake, FEET, LavaExits.NEAR).isEmpty());
    }

    @Test
    void aBankBehindAWallIsNotAnExit() {
        // 东边两格有一块露头的石台，岩浆和它之间隔着一道三格高的墙：游不过去，不算出口。
        BurningCellsFake lake = new BurningCellsFake().floor(-8, -8, 8, 8, 63).lava(-8, -8, 8, 8, 64);
        lake.lava.remove(new BlockPos(2, 64, 0));
        lake.solid.add(new BlockPos(2, 64, 0));
        assertEquals(List.of(new BlockPos(2, 65, 0)), LavaExits.find(lake, FEET, LavaExits.NEAR));
        for (int z = -8; z <= 8; z++) {
            for (int y = 64; y <= 65; y++) {
                BlockPos wall = new BlockPos(1, y, z);
                lake.lava.remove(wall);
                lake.solid.add(wall);
            }
        }
        assertTrue(LavaExits.find(lake, FEET, LavaExits.NEAR).isEmpty());
    }

    @Test
    void aMagmaBankIsNotSomewhereToStand() {
        // 岸是岩浆块：站上去要烫，不算出口。
        BurningCellsFake pool = shallowPool();
        for (int z = -8; z <= 8; z++) {
            BlockPos cell = new BlockPos(2, 64, z);
            pool.solid.remove(cell);
            pool.hot.add(cell);
        }
        assertFalse(LavaExits.find(pool, FEET, LavaExits.NEAR).contains(new BlockPos(2, 65, 0)));
    }

    @Test
    void lavaCellsBesideTheBodyCanBecomeAFoothold() {
        // 湖中间：身边四格岩浆上面都空着，垫一块顶面比脚高一格，站得上去。
        BurningCellsFake lake = new BurningCellsFake().floor(-8, -8, 8, 8, 63).lava(-8, -8, 8, 8, 64);
        List<BlockPos> spots = LavaExits.footholds(lake, FEET);
        assertEquals(4, spots.size());
        assertTrue(spots.contains(new BlockPos(1, 64, 0)));
        // 头顶压着石头的那一格垫了也站不上去。
        lake.solid.add(new BlockPos(1, 66, 0));
        assertFalse(LavaExits.footholds(lake, FEET).contains(new BlockPos(1, 64, 0)));
    }
}
