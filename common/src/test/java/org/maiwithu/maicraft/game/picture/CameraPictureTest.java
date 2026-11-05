// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.picture;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界里一点换成图上的像素：镜头正前方落在图中央，身后的点不在画面里；面朝南时西边在右手边、
 * 头顶在上半张；视野正切按投影读回。镜头朝向的算法和原版镜头一样。
 */
class CameraPictureTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final float FOV_DEGREES = 70f;

    // 原版镜头的朝向：偏航 0 是面朝南（正 Z），俯仰正值是低头。
    private static Quaternionf facing(float yaw, float pitch) {
        return new Quaternionf().rotationYXZ((float) Math.PI - yaw * (float) Math.PI / 180f,
                -pitch * (float) Math.PI / 180f, 0f);
    }

    private static CameraPicture picture(float yaw, float pitch) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(FOV_DEGREES),
                (float) WIDTH / HEIGHT, 0.05f, 512f);
        return new CameraPicture(WIDTH, HEIGHT, new int[WIDTH * HEIGHT], 0, 0, 0, facing(yaw, pitch), projection,
                true, false);
    }

    @Test
    void 正前方落在图中央() {
        CameraPicture.Projected ahead = picture(0f, 0f).project(0, 0, 10);
        assertTrue(ahead.inFront());
        assertEquals(WIDTH / 2.0, ahead.x(), 0.5);
        assertEquals(HEIGHT / 2.0, ahead.y(), 0.5);
        assertTrue(ahead.inFrame(WIDTH, HEIGHT));
    }

    @Test
    void 身后的点不在画面里() {
        CameraPicture.Projected behind = picture(0f, 0f).project(0, 0, -10);
        assertFalse(behind.inFront());
        assertFalse(behind.inFrame(WIDTH, HEIGHT));
    }

    @Test
    void 面朝南时西边在右手边_头顶在上半张() {
        CameraPicture south = picture(0f, 0f);
        assertTrue(south.project(-3, 0, 10).x() > WIDTH / 2.0, "西边在右");
        assertTrue(south.project(3, 0, 10).x() < WIDTH / 2.0, "东边在左");
        assertTrue(south.project(0, 3, 10).y() < HEIGHT / 2.0, "高处在上");
    }

    @Test
    void 面朝东时东边的点落在中央() {
        CameraPicture east = picture(-90f, 0f);
        CameraPicture.Projected ahead = east.project(10, 0, 0);
        assertTrue(ahead.inFront());
        assertEquals(WIDTH / 2.0, ahead.x(), 0.5);
    }

    @Test
    void 低头时脚下前方的点落在中央() {
        CameraPicture down = picture(0f, 45f);
        CameraPicture.Projected ahead = down.project(0, -5, 5);
        assertEquals(WIDTH / 2.0, ahead.x(), 0.5);
        assertEquals(HEIGHT / 2.0, ahead.y(), 0.5);
    }

    @Test
    void 视野正切按投影读回() {
        CameraPicture south = picture(0f, 0f);
        double tanHeight = Math.tan(Math.toRadians(FOV_DEGREES / 2));
        assertEquals(tanHeight, south.tanHalfHeight(), 1e-4);
        assertEquals(tanHeight * WIDTH / HEIGHT, south.tanHalfWidth(), 1e-4);
    }

    @Test
    void 像素数和宽高对不上时拒收() {
        assertThrows(IllegalArgumentException.class, () -> new CameraPicture(4, 4, new int[3], 0, 0, 0,
                new Quaternionf(), new Matrix4f(), true, false));
    }
}
