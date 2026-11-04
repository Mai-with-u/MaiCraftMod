// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

/**
 * 右键实体时准星点在它身上的哪一点：盔甲架空手取装备按这一点的高低选哪一件，
 * 所以平视点中的是胸口那一面、往下看点中的是脚边那一面，眼睛在框里就是眼睛本身，
 * 没对准就退到框的中心。
 */
class EntityClickPointTest {

    /** 一个立在原点的盔甲架的点选框：宽 0.5、高 1.975。 */
    private static final AABB ARMOR_STAND = new AABB(-0.25, 0.0, -0.25, 0.25, 1.975, 0.25);
    private static final double EPS = 1.0e-6;

    // 站在两格外平视：射线打在朝向角色的那一面，高度就是眼睛的高度。
    @Test
    void levelLookHitsTheNearFaceAtEyeHeight() {
        Vec3 eye = new Vec3(0.0, 1.0, -2.0);
        Vec3 point = FirstPersonInteractionTargeting.entityClickPoint(eye, new Vec3(0.0, 0.0, 1.0), ARMOR_STAND);
        assertVec(new Vec3(0.0, 1.0, -0.25), point);
    }

    // 往下看它的脚边：射线在近面上落得低，盔甲架会按这个高度取下靴子而不是头盔。
    @Test
    void lookingDownHitsLowOnTheNearFace() {
        Vec3 eye = new Vec3(0.0, 1.62, -2.0);
        Vec3 view = new Vec3(0.0, 0.2, 0.0).subtract(eye);
        Vec3 point = FirstPersonInteractionTargeting.entityClickPoint(eye, view, ARMOR_STAND);
        assertVec(new Vec3(0.0, 1.62 - 1.42 * 0.875, -0.25), point);
    }

    // 角色贴得太近、眼睛已经在点选框里：和原版准星一样，点中的就是眼睛所在的那一点。
    @Test
    void eyeInsideTheBoxClicksAtTheEye() {
        Vec3 eye = new Vec3(0.1, 1.5, 0.1);
        Vec3 point = FirstPersonInteractionTargeting.entityClickPoint(eye, new Vec3(1.0, 0.0, 0.0), ARMOR_STAND);
        assertVec(eye, point);
    }

    // 视线背对着它也被叫来出手：不编框外的位置，退到框的中心。
    @Test
    void lookingAwayFallsBackToTheBoxCenter() {
        Vec3 eye = new Vec3(0.0, 1.0, -2.0);
        Vec3 point = FirstPersonInteractionTargeting.entityClickPoint(eye, new Vec3(0.0, 0.0, -1.0), ARMOR_STAND);
        assertVec(ARMOR_STAND.getCenter(), point);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPS, "x");
        assertEquals(expected.y, actual.y, EPS, "y");
        assertEquals(expected.z, actual.z, EPS, "z");
    }
}
