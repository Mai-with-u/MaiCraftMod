// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.travel.planning.HazardCell;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目的地旁边因为危险没踩的格怎么说：按种类和原因分组，写几处、最近一处的坐标、方位和原因。 */
class DestinationHazardsTest {

    private static final BlockPos DESTINATION = new BlockPos(0, 64, 0);

    @Test
    void groupsByKindAndReasonNearestFirst() {
        // 两堆点着的营火、一块岩浆块：分两组，离目的地最近的那组先说，每组只报最近一处的方位。
        List<HazardCell> cells = List.of(
                new HazardCell(new BlockPos(3, 64, 0), "minecraft:campfire", "点着了，站上去会烧伤"),
                new HazardCell(new BlockPos(-2, 64, 0), "minecraft:campfire", "点着了，站上去会烧伤"),
                new HazardCell(new BlockPos(0, 63, 1), "minecraft:magma_block", "站上去烫脚"));

        Optional<String> line = DestinationHazards.describe(DESTINATION, cells);

        assertEquals(Optional.of("目的地旁边因为危险没踩的："
                + "minecraft:magma_block 1 处，最近一处在 (0, 63, 1)、目的地南 1.0 格、低 1 格（站上去烫脚）；"
                + "minecraft:campfire 2 处，最近一处在 (-2, 64, 0)、目的地西 2.0 格、同高（点着了，站上去会烧伤）"), line);
    }

    @Test
    void sameColumnSaysDirectlyAboveOrBelow() {
        // 就在目的地脚下的岩浆块：说"正下方"，不说"原地 0.0 格"。
        Optional<String> line = DestinationHazards.describe(DESTINATION,
                List.of(new HazardCell(new BlockPos(0, 63, 0), "minecraft:magma_block", "站上去烫脚")));

        assertTrue(line.orElseThrow().contains("目的地正下方 1 格（站上去烫脚）"), line::orElseThrow);
    }

    @Test
    void nothingToNameGivesEmpty() {
        assertEquals(Optional.empty(), DestinationHazards.describe(DESTINATION, List.of()));
    }

    @Test
    void suggestionKeepsTheOriginalAfterHazardAdvice() {
        // 原来的建议（比如放宽许可）照留在后面，危险格的处理办法先说。
        assertTrue(DestinationHazards.suggestion(null).startsWith("目的地旁边有因为危险没踩的格"));
        String merged = DestinationHazards.suggestion("许可动土再走同一路线");
        assertTrue(merged.startsWith("目的地旁边有因为危险没踩的格"), merged);
        assertTrue(merged.endsWith("；许可动土再走同一路线"), merged);
    }
}
