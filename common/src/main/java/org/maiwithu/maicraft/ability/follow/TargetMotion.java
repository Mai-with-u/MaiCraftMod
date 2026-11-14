// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import net.minecraft.world.phys.Vec3;

/**
 * 跟踪被跟对象的水平移动方向与速度：平滑掉单刻位置抖动，传送或上下车猛跳很远时清零重算。
 */
final class TargetMotion {

    private double velocityX;
    private double velocityZ;
    private Vec3 previousFeet;

    /** 记下目标这一刻的脚位并更新平滑移动速度。 */
    void observe(Vec3 feet) {
        if (previousFeet != null) {
            double dx = feet.x - previousFeet.x;
            double dz = feet.z - previousFeet.z;
            if (Math.hypot(dx, dz) > 8.0) {
                velocityX = 0;
                velocityZ = 0;
            } else {
                velocityX = velocityX * 0.5 + dx * 0.5;
                velocityZ = velocityZ * 0.5 + dz * 0.5;
            }
        }
        previousFeet = feet;
    }

    double velocityX() {
        return velocityX;
    }

    double velocityZ() {
        return velocityZ;
    }

    /** 找人时朝他离开的方向往前看 8 格；原本没在走就看最后看见他眼睛的位置。 */
    Vec3 lookOutFrom(Vec3 lastEye) {
        double speed = Math.hypot(velocityX, velocityZ);
        return speed < AsideSpot.MOVING ? lastEye : lastEye.add(velocityX / speed * 8, 0, velocityZ / speed * 8);
    }
}
