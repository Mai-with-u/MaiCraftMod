// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 眼前视野纯几何判断：正前方、偏角边缘、背后盲区、超出距离与近身大体型横跨画面。 */
class FieldOfViewTest {

    private static final Vec3 EYE = new Vec3(0.5, 65.6, 0.5);
    private static final Vec3 LOOK_SOUTH = new Vec3(0.0, 0.0, 1.0);

    @Test
    void 正前方与宽屏视角内的目标算在视野里_背后与正侧面不算() {
        // 正前方 8 格的矿石格在视野里
        assertTrue(FieldOfView.inFront(EYE, LOOK_SOUTH, new BlockPos(0, 65, 8), 16.0));
        // 偏左约 45 度仍在 60 度半视角内
        assertTrue(FieldOfView.inFront(EYE, LOOK_SOUTH, new BlockPos(6, 65, 8), 16.0));
        // 正背后 5 格的骷髅不在画面里
        assertFalse(FieldOfView.inFront(EYE, LOOK_SOUTH, new AABB(0.2, 64.0, -5.8, 0.8, 66.0, -5.2), 32.0));
        // 正侧面 90 度外不在画面里
        assertFalse(FieldOfView.inFront(EYE, LOOK_SOUTH, new BlockPos(10, 65, 0), 16.0));
    }

    @Test
    void 超出可见距离上限的不算_贴脸横跨画面的大实体算在视野里() {
        assertFalse(FieldOfView.inFront(EYE, LOOK_SOUTH, new BlockPos(0, 65, 20), 16.0));
        // 大体型实体贴在前方 1 格，八角虽偏出 60 度但视线正指着它身体中央
        AABB hugeBoss = new AABB(-8.0, 60.0, 1.2, 9.0, 72.0, 6.0);
        assertTrue(FieldOfView.inFront(EYE, LOOK_SOUTH, hugeBoss, 64.0));
    }
}
