// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/**
 * 寻路因为危险不踩、不进的一格：在哪、是什么方块、为什么不踩（烫脚、会烧伤、会招监守者、碰着掉血）。
 * 出行走不通时点名目的地旁边的这些格用。
 *
 * @param cell    这一格
 * @param blockId 方块 ID
 * @param why     为什么不踩，一句话
 */
public record HazardCell(BlockPos cell, String blockId, String why) {
    public HazardCell {
        cell = Objects.requireNonNull(cell, "cell").immutable();
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(why, "why");
    }
}
