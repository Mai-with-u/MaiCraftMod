// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 一个读端在扫描服务里登记过的目标方块：按维度记，每次查询前只补登记这个维度还没登记过的。
 *
 * <p>扫描服务的索引按维度分开，查询只认登记过的方块：只在第一次用到时登记一次的话，
 * 换个维度或换一种要找的方块就永远查不到，读端会把"没登记"误报成"附近没有"。
 * 账本不能只信自己这边：离开世界时索引整体清空，账本却跟着读端活到下一个世界，
 * 所以补登记前还要问索引一声还认不认得，对不上的重新登记。
 * 登记是计数式的，读端活多久就占多久。
 */
public final class ScanTargets {

    /** 读端要找的方块都交这里登记与核对；索引只认登记过的方块。 */
    private final RegistersScanTargets scans;
    private final Map<ResourceKey<Level>, Set<Block>> registered = new HashMap<>();

    public ScanTargets(RegistersScanTargets scans) {
        this.scans = Objects.requireNonNull(scans, "scans");
    }

    /** 查询前调用：这个维度还没登记过、或索引已经不认得的目标方块补登记，登记过且索引认得的不再重复计数。 */
    public void ensure(ClientLevel level, Collection<Block> blocks) {
        ensure(level.dimension(), blocks);
    }

    /** 同上，直接给维度键：账本与索引都按维度分开，世界对象只是键的来源。 */
    public void ensure(ResourceKey<Level> dimension, Collection<Block> blocks) {
        Set<Block> done = registered.computeIfAbsent(dimension, ignored -> new HashSet<>());
        // 账本记着登记过还不算数：换世界时索引整体清空，账本不跟着清，对不上的要补登记，
        // 否则索引里没有这种方块，查询把"没登记"报成"附近没有"。
        List<Block> fresh = blocks.stream()
                .filter(block -> !done.contains(block) || !scans.registered(dimension, block))
                .distinct().toList();
        if (fresh.isEmpty()) {
            return;
        }
        scans.register(dimension, fresh);
        done.addAll(fresh);
    }
}
