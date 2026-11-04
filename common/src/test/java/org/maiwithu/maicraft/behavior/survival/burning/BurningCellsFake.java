// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;

/** 着火测试用的小世界：岩浆、水、火、实心方块、烫脚的实心方块（岩浆块）各一份格子，其余都是空气。 */
final class BurningCellsFake implements BurningSteps.Cells {

    final Set<BlockPos> lava = new HashSet<>();
    final Set<BlockPos> water = new HashSet<>();
    final Set<BlockPos> fire = new HashSet<>();
    final Set<BlockPos> solid = new HashSet<>();
    final Set<BlockPos> hot = new HashSet<>();

    @Override public boolean lava(BlockPos cell) {
        return lava.contains(cell);
    }

    @Override public boolean open(BlockPos cell) {
        return !solid.contains(cell) && !hot.contains(cell);
    }

    @Override public boolean firm(BlockPos cell) {
        return solid.contains(cell);
    }

    @Override public boolean water(BlockPos cell) {
        return water.contains(cell);
    }

    @Override public boolean fire(BlockPos cell) {
        return fire.contains(cell);
    }

    /** 从 (x0,y,z0) 到 (x1,y,z1) 铺一层实心方块。 */
    BurningCellsFake floor(int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) solid.add(new BlockPos(x, y, z));
        }
        return this;
    }

    /** 在 (x0,y,z0) 到 (x1,y,z1) 灌一层岩浆（先把那里的实心方块挖掉）。 */
    BurningCellsFake lava(int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos cell = new BlockPos(x, y, z);
                solid.remove(cell);
                lava.add(cell);
            }
        }
        return this;
    }
}
