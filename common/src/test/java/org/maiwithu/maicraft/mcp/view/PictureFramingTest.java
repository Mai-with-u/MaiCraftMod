// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.picture.CameraPicture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取景：外框八个角都在画面里是整个装下，有的在是只拍到一部分，看不见的算看不见，都不在是不在画面里；
 * 离得太近外框横跨画面时也是只拍到一部分；退几格能装下按视野正切算，至少一格。
 */
class PictureFramingTest {

    private static final int W = 1280;
    private static final int H = 720;

    private static CameraPicture.Projected in(double x, double y) {
        return new CameraPicture.Projected(x, y, true);
    }

    @Test
    void 八个角都在画面里是整个装下() {
        List<CameraPicture.Projected> corners = List.of(in(100, 100), in(200, 100), in(100, 200), in(200, 200),
                in(110, 110), in(210, 110), in(110, 210), in(210, 210));
        assertEquals(PictureFraming.Framed.WHOLE, PictureFraming.framed(corners, W, H, true));
    }

    @Test
    void 有的角出了画面是只拍到一部分() {
        List<CameraPicture.Projected> corners = List.of(in(100, 100), in(-50, 100), in(100, 200), in(200, 200));
        assertEquals(PictureFraming.Framed.PARTLY, PictureFraming.framed(corners, W, H, true));
    }

    @Test
    void 看不见的算看不见_哪怕角都在画面里() {
        List<CameraPicture.Projected> corners = List.of(in(100, 100), in(200, 200));
        assertEquals(PictureFraming.Framed.HIDDEN, PictureFraming.framed(corners, W, H, false));
    }

    @Test
    void 都在身后或画面外是不在画面里() {
        List<CameraPicture.Projected> corners = List.of(new CameraPicture.Projected(0, 0, false),
                in(-100, 100), in(-200, 300));
        assertEquals(PictureFraming.Framed.OUTSIDE, PictureFraming.framed(corners, W, H, true));
    }

    @Test
    void 离得太近外框横跨画面时是只拍到一部分() {
        List<CameraPicture.Projected> corners = List.of(in(-500, -500), in(2000, -500), in(-500, 1500), in(2000, 1500));
        assertEquals(PictureFraming.Framed.PARTLY, PictureFraming.framed(corners, W, H, true));
    }

    @Test
    void 退几格能装下按视野算_至少一格() {
        double tanHeight = Math.tan(Math.toRadians(35));
        double tanWidth = tanHeight * W / H;
        // 二十格宽、十格高的房子，镜头在中心前五格：要退到约二十多格外。
        int back = PictureFraming.stepBackBlocks(10, 5, 5, tanWidth, tanHeight);
        assertTrue(back > 10 && back < 30, "退 " + back + " 格");
        assertEquals(1, PictureFraming.stepBackBlocks(0.5, 0.5, 50, tanWidth, tanHeight), "本来就装得下也至少说一格");
    }
}
