// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Collection;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 扫描目标的登记处：读端查询前按维度补登记目标方块，也能问索引现在还认不认得某个方块。
 *
 * <p>索引的生命周期比读端短：离开世界时扫描服务整体清空，读端各持的登记账本不作数了。
 * 登记前先问一声还认不认得，账本说登记过而索引不认得时就补登记，读端不会把"没登记"
 * 误报成"附近没有"。生产实现在方块扫描服务里，索引只认登记过的方块种类。
 */
public interface RegistersScanTargets {

    /** 这个维度现在还登记着这个方块吗：索引被整体清空后对不上了，读端要重新登记。 */
    boolean registered(ResourceKey<Level> dimension, Block block);

    /** 登记一批目标方块（计数式，重复登记同一种会叠引用数，读端自己别重复提交）。 */
    void register(ResourceKey<Level> dimension, Collection<Block> blocks);
}
