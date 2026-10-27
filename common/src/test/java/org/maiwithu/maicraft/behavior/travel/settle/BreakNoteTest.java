// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.OthersBlockBroken;

/** 结果变化里挖掉的一格怎么写：被困时挖开的别人的方块写明是谁放的，其余照旧写开路挖掉的。 */
class BreakNoteTest {

    @Test
    void 被困挖开的别人的方块写明谁放的() {
        ReadsPlacedBlocks walk = new ReadsPlacedBlocks() {
            @Override public List<BlockPos> placedDuringCurrentWalk() {
                return List.of();
            }

            @Override public List<OthersBlockBroken> othersBlocksBrokenDuringCurrentWalk() {
                return List.of(new OthersBlockBroken("6, 64, 5", "minecraft:dirt", "甲"));
            }
        };
        assertEquals("被困脱身挖开的，甲 放的", walk.breakNote("6, 64, 5 的 minecraft:dirt"));
        assertEquals("开路挖掉的", walk.breakNote("16, 64, 5 的 minecraft:stone"), "坐标前缀不能把 16 当成 6");
        assertEquals("开路挖掉的", walk.breakNote("7, 64, 5 的 minecraft:stone"));
    }
}
