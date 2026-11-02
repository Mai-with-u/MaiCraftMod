// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 直线段跑完能不能不减速接着跑下一段：同一高度、拐角不超过 60 度才跑着交接，否则先停准再转。 */
class MovementGroundStraightTest {

    // 向东跑 6 格的一段直线，脚底在 y=64。
    private static final Vec3 FROM = new Vec3(0.5, 64, 0.5);
    private static final Vec3 TARGET = new Vec3(6.5, 64, 0.5);

    @Test
    void 下一段同向或稍拐_跑着交接() {
        assertTrue(MovementGroundStraight.carriesThrough(FROM, TARGET, new BlockPos(6, 64, 0), new BlockPos(12, 64, 0)),
                "直线接直线不该在中间减速");
        assertTrue(MovementGroundStraight.carriesThrough(FROM, TARGET, new BlockPos(6, 64, 0), new BlockPos(7, 64, 1)),
                "斜走一格只拐 45 度，照样跑过去");
    }

    @Test
    void 急拐弯_先停准再转() {
        assertFalse(MovementGroundStraight.carriesThrough(FROM, TARGET, new BlockPos(6, 64, 0), new BlockPos(6, 64, 5)),
                "直角拐弯时疾跑惯性会冲出下一段");
        assertFalse(MovementGroundStraight.carriesThrough(FROM, TARGET, new BlockPos(6, 64, 0), new BlockPos(2, 64, 0)),
                "掉头更要停下");
    }

    @Test
    void 高度变了_不算平路() {
        assertFalse(MovementGroundStraight.carriesThrough(FROM, TARGET, new BlockPos(6, 65, 0), new BlockPos(9, 65, 0)),
                "下一段在高一格的地方，中间隔着台阶");
        assertFalse(MovementGroundStraight.carriesThrough(FROM, TARGET, new BlockPos(6, 64, 0), new BlockPos(7, 65, 0)),
                "下一段本身要上台阶");
    }
}
