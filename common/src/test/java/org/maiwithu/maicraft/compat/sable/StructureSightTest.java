// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/** 结构上一格的瞄准点：没给面时是外形中心加六个面的中心；给了面就在那一面上找中心和往里收的四角，外形空的按整格。 */
class StructureSightTest {

    private static final BlockPos CELL = new BlockPos(20_000_000, 100, 20_000_000);

    @Test
    void 没给面_外形中心加六个面的中心() {
        List<Vec3> points = StructureSight.aimPoints(CELL, List.of(new AABB(0, 0, 0, 1, 0.5, 1)), null);
        assertEquals(7, points.size());
        assertEquals(new Vec3(20_000_000.5, 100.25, 20_000_000.5), points.getFirst(), "半砖的中心在下半格");
        assertTrue(points.contains(new Vec3(20_000_000.5, 100.5, 20_000_000.5)), "顶面在半格高");
    }

    @Test
    void 给了面_那一面上的中心和四角() {
        List<Vec3> points = StructureSight.aimPoints(CELL, List.of(), Direction.UP);
        assertEquals(5, points.size(), "外形空的按整格：一个中心四个角");
        assertTrue(points.stream().allMatch(point -> point.y == 101.0), "都在顶面上");
        assertTrue(points.contains(new Vec3(20_000_000.25, 101.0, 20_000_000.75)));
    }
}
