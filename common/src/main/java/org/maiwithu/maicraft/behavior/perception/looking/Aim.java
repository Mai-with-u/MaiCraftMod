// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

/**
 * 从眼睛看向一个点要转到的偏航与俯仰：偏航 0 朝南、顺时针增大，俯仰正数是低头，和游戏的视角角一样。
 */
record Aim(float yaw, float pitch) {

    /** 从眼睛（ex, ey, ez）看向点（x, y, z）。纯函数。 */
    static Aim between(double ex, double ey, double ez, double x, double y, double z) {
        double dx = x - ex;
        double dy = y - ey;
        double dz = z - ez;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new Aim(wrap(yaw), pitch);
    }

    /** 角度换到正负一百八十度之间。 */
    static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }
}
