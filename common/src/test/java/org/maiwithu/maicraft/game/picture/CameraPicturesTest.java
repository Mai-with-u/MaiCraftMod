// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.picture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取画面的观察口里不碰显卡的那一半：缩图的尺寸、读回像素的翻转换色、一次只拍一张。
 */
class CameraPicturesTest {

    @Test
    void 按长边缩到一千二百八_宽高比不变() {
        assertArrayEquals(new int[] {1280, 720}, CameraPictures.scaledSize(2560, 1440, 1280));
        assertArrayEquals(new int[] {720, 1280}, CameraPictures.scaledSize(1080, 1920, 1280));
        assertArrayEquals(new int[] {1280, 536}, CameraPictures.scaledSize(3440, 1440, 1280));
    }

    @Test
    void 本来就小的不放大() {
        assertArrayEquals(new int[] {854, 480}, CameraPictures.scaledSize(854, 480, 1280));
    }

    @Test
    void 读回的像素从下往上翻成从上往下_ABGR换成RGB() {
        // 两行两列：显卡读回的第一行是图的最下一行。
        int red = 0xFF0000FF;
        int green = 0xFF00FF00;
        int blue = 0xFFFF0000;
        int white = 0xFFFFFFFF;
        int[] abgr = {red, green, blue, white};
        assertArrayEquals(new int[] {0x0000FF, 0xFFFFFF, 0xFF0000, 0x00FF00}, CameraPictures.topDownRgb(abgr, 2, 2));
    }

    @Test
    void 正在拍一张时不收第二张() {
        CameraPictures pictures = new CameraPictures();
        assertNull(pictures.refusal(0L));
        assertInstanceOf(CameraPictures.Asked.Accepted.class, pictures.request(false, 0L));
        assertInstanceOf(CameraPictures.Asked.AlreadyTaking.class, pictures.refusal(1L));
        assertInstanceOf(CameraPictures.Asked.AlreadyTaking.class, pictures.request(true, 1L));
    }

    @Test
    void 等不及撤掉以后下一张照常能要() {
        CameraPictures pictures = new CameraPictures();
        CameraPictures.Asked.Accepted first = (CameraPictures.Asked.Accepted) pictures.request(false, 0L);
        pictures.giveUp(first.picture());
        assertTrue(first.picture().isCancelled());
        assertNull(pictures.refusal(2L));
        assertInstanceOf(CameraPictures.Asked.Accepted.class, pictures.request(false, 2L));
    }
}
