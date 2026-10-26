// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.util.SpotScan;
import org.maiwithu.maicraft.behavior.travel.modes.WaterCrossing;

/**
 * 挑对岸的靠岸点（纯函数）：实地里紧挨着能划的水面、离目的地近的那几格。
 * 划船只送到水边，上岸后走到目的地由行程接手，所以靠岸点按离目的地近先排；
 * 一处靠不上去（被墙挡、站不下）就换下一个，不用回头重划。
 */
public final class BoatLandings {

    /** 靠岸点在岸柱附近多大范围里找（格）。 */
    static final int SEARCH_RADIUS = 8;
    /** 最多备几个靠岸点：一个被挡换下一个，备太多也没用。 */
    static final int LIMIT = 4;

    private BoatLandings() {}

    /**
     * 在对岸柱附近挑靠岸点，离目的地近的排前面。
     *
     * @param read        读一格方块；没加载的格给空
     * @param shore       勘测得出的对岸柱（地表一点）
     * @param destination 目的地：靠岸点按离它近先排
     */
    public static List<BlockPos> around(Function<BlockPos, Optional<BlockState>> read, BlockPos shore,
            BlockPos destination) {
        // 环扫交给内核：这一柱挑得出干地脚位、又紧挨着能划的水面才算靠岸点（挨水的要求折进列函数里）；
        // 按离目的地近先排，只留前 LIMIT 个备换。
        return SpotScan.columns(shore.getX() - SEARCH_RADIUS, shore.getZ() - SEARCH_RADIUS,
                shore.getX() + SEARCH_RADIUS, shore.getZ() + SEARCH_RADIUS, 1,
                (x, z) -> WaterCrossing.standFeet(read, x, z, shore.getY()).stream()
                        .mapToObj(feetY -> new BlockPos(x, feetY, z)).findFirst()
                        .filter(feet -> touchesWater(read, feet)),
                Comparator.comparingDouble(spot -> spot.distSqr(destination)), LIMIT);
    }

    /** 这一格紧挨着能划的水面：四邻的柱子里有一柱顶上是活水（没冻住），水面低在脚下一两格也算挨着。 */
    static boolean touchesWater(Function<BlockPos, Optional<BlockState>> read, BlockPos feet) {
        for (BlockPos side : List.of(feet.north(), feet.south(), feet.west(), feet.east())) {
            Optional<BlockState> state = read.apply(side);
            if (state.isEmpty() || WaterCrossing.kindOf(state.get()) != WaterCrossing.Kind.WATER) continue;
            return true;
        }
        // 脚边没有齐平的水：往脚下方看两格，岸坡比水面高一步时也下得去。
        for (int down = 1; down <= 2; down++) {
            BlockPos lower = feet.below(down);
            for (BlockPos side : List.of(lower.north(), lower.south(), lower.west(), lower.east())) {
                Optional<BlockState> state = read.apply(side);
                if (state.isPresent() && WaterCrossing.kindOf(state.get()) == WaterCrossing.Kind.WATER) return true;
            }
        }
        return false;
    }
}
