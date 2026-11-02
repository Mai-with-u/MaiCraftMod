// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不紧不慢地转头：走近路、斜着转、每刻不超过定好的速度、镜头到了目标附近才算转到位。 */
class HeadMotionTest {

    @Test
    void 左右走近路跨过正负一百八十度() {
        // 从 170 度转到 -170 度只隔 20 度，往正方向转过 180 度，不绕一大圈。
        float[] next = HeadMotion.toward(170f, 0f, -170f, 0f, 5f);
        assertEquals(175f, next[0], 0.01f);
        assertEquals(0f, next[1], 0.01f);
    }

    @Test
    void 左右上下按同一比例斜着转() {
        // 要转 30 度偏航、40 度俯仰，一共 50 度；挪 10 度时偏航走 6 度、俯仰走 8 度。
        float[] next = HeadMotion.toward(0f, 0f, 30f, 40f, 10f);
        assertEquals(6f, next[0], 0.01f);
        assertEquals(8f, next[1], 0.01f);
    }

    @Test
    void 剩下的不到一步就直接到() {
        float[] next = HeadMotion.toward(10f, 5f, 12f, 6f, 8f);
        assertEquals(12f, next[0], 0.01f);
        assertEquals(6f, next[1], 0.01f);
    }

    @Test
    void 每刻转的不超过速度_镜头到了才算到位() {
        // 每秒 100 度即每刻 5 度：转 90 度，中途每一刻都不超过 5 度，最后停在 90 度。
        FakeHead head = new FakeHead(0f, 0f);
        HeadMotion motion = new HeadMotion(100f);
        motion.aim(90f, 0f);
        int ticks = 1;
        while (!motion.step(head)) {
            ticks++;
            assertTrue(ticks < 100, "转不到位");
        }
        assertTrue(ticks >= 17 && ticks <= 18, "转 90 度约十八刻：" + ticks);
        assertTrue(head.largestStep <= 5.01f, "每刻最多 5 度：" + head.largestStep);
        assertEquals(90f, head.yaw, 0.01f);
    }

    @Test
    void 镜头没跟上就不算到位() {
        // 人按 F8 拿回了角色：转头请求发出去，镜头不动，一直不报到位。
        FakeHead head = new FakeHead(0f, 0f);
        head.follows = false;
        HeadMotion motion = new HeadMotion(1000f);
        motion.aim(30f, 0f);
        for (int i = 0; i < 5; i++) assertFalse(motion.step(head));
    }

    @Test
    void 镜头被别的事转过后从实际位置接着转() {
        FakeHead head = new FakeHead(0f, 0f);
        HeadMotion motion = new HeadMotion(100f);
        motion.aim(90f, 0f);
        motion.step(head);
        // 中途被干活瞄准转到了 -60 度：重新同步后从 -60 度起步，不先跳回原来那一步。
        head.yaw = -60f;
        motion.resync();
        motion.step(head);
        assertEquals(-55f, head.yaw, 0.01f);
    }
}
