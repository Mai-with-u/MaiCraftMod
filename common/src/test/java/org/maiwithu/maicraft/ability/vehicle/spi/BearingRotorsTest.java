// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

/** 轴承转子：从转子头起顺着挨着的风帆类方块认，船身不算；转子头不在这片方块里的轴承没有转子。 */
class BearingRotorsTest {

    @Test
    void 转子头连着的风帆都跟着转_船身和不挨着的风帆不算() {
        BlockPos bearing = new BlockPos(0, 0, 0);
        BlockPos head = new BlockPos(0, 1, 0);
        BlockPos blade = new BlockPos(1, 1, 0);
        BlockPos tip = new BlockPos(2, 1, 0);
        BlockPos hull = new BlockPos(0, -1, 0);
        BlockPos wing = new BlockPos(5, 0, 0);
        Set<BlockPos> body = Set.of(bearing, head, blade, tip, hull, wing);
        Set<BlockPos> sails = Set.of(blade, tip, wing);
        List<BearingRotors.Rotor> rotors = BearingRotors.of(body,
                cell -> cell.equals(bearing) ? Optional.of(Direction.UP) : Optional.empty(), sails::contains);
        assertEquals(1, rotors.size());
        assertEquals(Set.of(head, blade, tip), rotors.get(0).cells());
        assertEquals(bearing, rotors.get(0).bearing());
        assertTrue(!rotors.get(0).cells().contains(wing), "不挨着转子的风帆不跟着转");
    }

    @Test
    void 转子头不在这片方块里就没有转子() {
        BlockPos bearing = new BlockPos(0, 0, 0);
        Map<BlockPos, Direction> bearings = Map.of(bearing, Direction.NORTH);
        List<BearingRotors.Rotor> rotors = BearingRotors.of(Set.of(bearing, new BlockPos(0, -1, 0)),
                cell -> Optional.ofNullable(bearings.get(cell)), cell -> true);
        assertTrue(rotors.isEmpty());
    }
}
