// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.design;

import java.util.List;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 读现场的结果指着那一栋：长的那一边两头与中间，高过六格的再加顶；看图纸时没有可看的。 */
class DesignDetailsPointsTest {

    private static DesignDetails site(List<Integer> min, List<Integer> max, WorldPosition anchor) {
        return new DesignDetails("d1", null, 10, 10, 10, null, new DesignDetails.Bounds(min, max), null, null, null, null,
                anchor, 0, new JsonObject(), null, null, null);
    }

    @Test
    void 扫过那一栋的两头与中间_高的再看一眼顶() {
        WorldPosition anchor = new WorldPosition(100, 64, 200, "minecraft:overworld");
        // 东西长十格、南北宽四格、高四格：沿东西两头与中间。
        assertEquals(List.of(new WorldPosition(100, 65, 201, "minecraft:overworld"),
                        new WorldPosition(105, 65, 201, "minecraft:overworld"),
                        new WorldPosition(110, 65, 201, "minecraft:overworld")),
                site(List.of(0, 0, 0), List.of(10, 3, 3), anchor).pointsAt());
        // 南北长、高十格：沿南北两头与中间，中间之后抬头看顶。
        assertEquals(List.of(new WorldPosition(101, 68, 200, "minecraft:overworld"),
                        new WorldPosition(101, 68, 206, "minecraft:overworld"),
                        new WorldPosition(101, 73, 206, "minecraft:overworld"),
                        new WorldPosition(101, 68, 212, "minecraft:overworld")),
                site(List.of(0, 0, 0), List.of(3, 9, 12), anchor).pointsAt());
    }

    @Test
    void 看图纸时没有指着的地方() {
        DesignDetails drawing = new DesignDetails("d1", null, 10, 10, 10, null,
                new DesignDetails.Bounds(List.of(0, 0, 0), List.of(3, 3, 3)), null, null, null, null,
                null, null, null, null, null, null);
        assertTrue(drawing.pointsAt().isEmpty());
    }
}
