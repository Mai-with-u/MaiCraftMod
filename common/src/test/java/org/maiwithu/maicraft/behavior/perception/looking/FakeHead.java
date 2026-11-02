// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

/**
 * 转头接缝的替身：镜头默认当刻跟到要去的方向，站定朝向按"开始看时记下、放开就忘"记账；
 * 可以设成镜头不跟（人按 F8 拿回了角色），也记下每一刻转了多少度。
 */
final class FakeHead implements TurnsHead {
    float yaw;
    float pitch;
    Float standing;
    boolean follows = true;
    int holds;
    int letGos;
    int turns;
    float largestStep;

    FakeHead(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override public float yaw() { return yaw; }
    @Override public float pitch() { return pitch; }
    @Override public float standingYaw() { return standing != null ? standing : yaw; }

    @Override public void hold() {
        holds++;
        if (standing == null) standing = yaw;
    }

    @Override public void letGo() {
        letGos++;
        standing = null;
    }

    @Override public void turnToward(float toYaw, float toPitch) {
        turns++;
        if (!follows) return;
        float step = (float) Math.hypot(wrap(toYaw - yaw), toPitch - pitch);
        largestStep = Math.max(largestStep, step);
        yaw = toYaw;
        pitch = toPitch;
    }

    static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
