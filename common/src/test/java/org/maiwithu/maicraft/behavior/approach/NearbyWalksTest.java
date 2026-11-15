// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 短途直走判断：三格内平地、沿途一条直线逐格站得稳、不踩不许踩的格才判得过；
 * 挡一格、深落差、液体、岩浆、受保护、没加载、距离或高差出界，各判不过交给寻路。
 */
class NearbyWalksTest {

    private final StubWorld world = new StubWorld();
    private final StubGuarded guarded = new StubGuarded();
    private final BlockPos 出发 = new BlockPos(0, 64, 0);

    @Test
    void 平地一二三格判过() {
        assertTrue(NearbyWalks.passable(出发, new BlockPos(1, 64, 0), world, guarded));
        assertTrue(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded));
        assertTrue(NearbyWalks.passable(出发, new BlockPos(3, 64, 0), world, guarded), "三格是上限，正好压线判过");
        assertTrue(NearbyWalks.passable(出发, new BlockPos(2, 64, 2), world, guarded), "斜着的直线也判");
    }

    @Test
    void 差一格跳上去与低一格走下去都判过() {
        assertTrue(NearbyWalks.passable(出发, new BlockPos(1, 65, 0), world, guarded));
        assertTrue(NearbyWalks.passable(出发, new BlockPos(2, 63, 0), world, guarded));
    }

    @Test
    void 水平超出三格判不过() {
        assertFalse(NearbyWalks.passable(出发, new BlockPos(4, 64, 0), world, guarded));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 3), world, guarded), "斜着量超三格也不行");
    }

    @Test
    void 高差超过一跳判不过() {
        assertFalse(NearbyWalks.passable(出发, new BlockPos(1, 66, 0), world, guarded));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(1, 61, 0), world, guarded));
    }

    @Test
    void 中途一格站不稳就判不过() {
        world.noFloor.add(new BlockPos(1, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded), "中途脚下悬空");
        world.noFloor.clear();
        world.lowCeiling.add(new BlockPos(1, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded), "中途头顶没空间");
    }

    @Test
    void 终点站不稳判不过() {
        world.noFloor.add(new BlockPos(2, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded));
    }

    @Test
    void 沿途泡水或挨着岩浆判不过() {
        world.fluids.add(new BlockPos(1, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded), "中途泡水");
        world.fluids.clear();
        world.fluids.add(new BlockPos(2, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded), "终点泡水");
        world.fluids.clear();
        world.lavas.add(new BlockPos(2, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded), "终点旁边有岩浆");
    }

    @Test
    void 沿途挨着深落差判不过() {
        world.deepDrops.add(new BlockPos(2, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(1, 64, 0), world, guarded), "终点旁边三格以上没落脚面");
    }

    @Test
    void 受保护或不许踩的格判不过() {
        guarded.add(new BlockPos(1, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded));
    }

    @Test
    void 格子没加载判不过() {
        world.unloaded.add(new BlockPos(1, 64, 0));
        assertFalse(NearbyWalks.passable(出发, new BlockPos(2, 64, 0), world, guarded));
    }

    @Test
    void 出发或终点缺格子信息判不过() {
        assertFalse(NearbyWalks.passable(null, new BlockPos(1, 64, 0), world, guarded));
        assertFalse(NearbyWalks.passable(出发, null, world, guarded));
    }
}
