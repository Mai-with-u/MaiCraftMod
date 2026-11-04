// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 现场扫到的容器和记得的用同一把尺子：按区块扫会扫进半径外、上下很远的格子（实机：radius 48 时
 * 坑底下方直线约 72 格的箱子照样被选中），直线距离超出半径的不要，正好在半径上的要。
 */
class ContainerRadiusTest {

    private static final BlockPos HERE = new BlockPos(0, 64, 0);

    @Test
    void chestInsideTheScannedChunksButBeyondTheStraightRadiusIsLeftOut() {
        // 水平 48 格（radius 48 时扫 4 个区块，在扫描范围里）、往下 54 格：直线约 72 格，超出半径。
        assertFalse(ClientSpotsContainers.withinRadius(new BlockPos(48, 10, 0), HERE, 48));
    }

    @Test
    void chestRightOnTheRadiusIsKept() {
        assertTrue(ClientSpotsContainers.withinRadius(new BlockPos(0, 16, 0), HERE, 48));
        assertTrue(ClientSpotsContainers.withinRadius(new BlockPos(3, 64, 4), HERE, 5));
    }
}
