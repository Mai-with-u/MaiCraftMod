// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import net.minecraft.core.BlockPos;

/**
 * 一格可以挖的方块：在哪、是什么、挖了掉什么、掉的机会多大。挖了掉什么以游戏真实的掉落规则为准，
 * 结论由扫描接缝的实现读出来，这里只带结论。
 *
 * @param pos        方块的格子
 * @param blockType  方块的注册 ID，例如 minecraft:coal_ore
 * @param droppedItem 挖掉后掉落的物品 ID，例如 minecraft:coal
 * @param odds       挖一格掉它的机会：矿石必掉，砾石出燧石这类是概率掉落
 */
public record MinableSpot(BlockPos pos, String blockType, String droppedItem, DropOdds odds) {

    public MinableSpot {
        if (pos == null) throw new IllegalArgumentException("可挖的方块必须有格子");
        if (blockType == null || blockType.isBlank()) throw new IllegalArgumentException("可挖的方块必须有方块类型");
        if (droppedItem == null || droppedItem.isBlank()) throw new IllegalArgumentException("可挖的方块必须写掉落物");
        if (odds == null) throw new IllegalArgumentException("可挖的方块必须写掉的机会");
    }

    /** 一挖就掉的方块：矿石、石头、原木这类。 */
    public MinableSpot(BlockPos pos, String blockType, String droppedItem) {
        this(pos, blockType, droppedItem, DropOdds.CERTAIN);
    }

    /** 挖一块不一定出：概率掉落，或线索点名、掉没掉说不准的方块。 */
    public boolean byChance() {
        return !odds.certain();
    }
}
