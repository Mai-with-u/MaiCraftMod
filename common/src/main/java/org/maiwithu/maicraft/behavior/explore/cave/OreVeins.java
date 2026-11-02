// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * 矿脉分组：看见的同种矿挨着（斜着挨着也算）的算一条矿脉，记块数和离角色最近的那一格。
 * 只数看见的那几块：埋在里面的不知道有多少，挖的时候连锁挖、整脉挖再说。纯判断。
 */
public final class OreVeins {

    private OreVeins() {}

    /**
     * 一条矿脉。
     *
     * @param blockId 矿的方块注册 ID
     * @param blocks  看见了几块
     * @param nearest 离角色最近的那一格
     */
    public record Vein(String blockId, int blocks, BlockPos nearest) {}

    /** 把看见的矿分成一条一条矿脉，离 from 近的在前。 */
    public static List<Vein> group(Map<BlockPos, String> seen, BlockPos from) {
        Set<BlockPos> taken = new HashSet<>();
        List<Vein> veins = new ArrayList<>();
        for (Map.Entry<BlockPos, String> start : seen.entrySet()) {
            if (!taken.add(start.getKey())) continue;
            String blockId = start.getValue();
            Deque<BlockPos> pending = new ArrayDeque<>(List.of(start.getKey()));
            int blocks = 0;
            BlockPos nearest = start.getKey();
            while (!pending.isEmpty()) {
                BlockPos cell = pending.poll();
                blocks++;
                if (cell.distSqr(from) < nearest.distSqr(from)) nearest = cell;
                for (BlockPos near : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1))) {
                    BlockPos candidate = near.immutable();
                    if (blockId.equals(seen.get(candidate)) && taken.add(candidate)) pending.add(candidate);
                }
            }
            veins.add(new Vein(blockId, blocks, nearest));
        }
        veins.sort(Comparator.comparingDouble(vein -> vein.nearest().distSqr(from)));
        return veins;
    }
}
