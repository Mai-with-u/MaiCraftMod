// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.picture.CameraPicture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编 JPEG：大小和取到的一样，标签画在摆好的位置上，没摆的地方不动。 */
class PictureEncodingTest {

    private static final int W = 320;
    private static final int H = 180;

    private static CameraPicture grey() {
        int[] rgb = new int[W * H];
        Arrays.fill(rgb, 0x808080);
        return new CameraPicture(W, H, rgb, 0, 0, 0, new Quaternionf(), new Matrix4f(), true, false);
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    // 一格像素离纯灰多远：JPEG 有损，按差值算。
    private static int offGrey(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return Math.abs(red - 0x80) + Math.abs(green - 0x80) + Math.abs(blue - 0x80);
    }

    @Test
    void 编出来的图和取到的一样大() throws IOException {
        BufferedImage image = decode(PictureEncoding.jpeg(grey(), List.of()));
        assertEquals(W, image.getWidth());
        assertEquals(H, image.getHeight());
        assertTrue(offGrey(image.getRGB(W / 2, H / 2)) < 12, "没有标签的图不动");
    }

    @Test
    void 标签画在摆好的地方() throws IOException {
        PictureLabels.Candidate candidate = new PictureLabels.Candidate("e1", PictureLabels.Rank.ENTITY, false,
                160, 120, 5);
        List<PictureLabels.Placement> placements = PictureLabels.place(List.of(candidate), W, H, null, false);
        PictureLabels.Rect tag = placements.get(0).tag();
        BufferedImage image = decode(PictureEncoding.jpeg(grey(), placements));
        // 标签底是深色：标签左上角往里两格明显比灰色暗。
        int inside = image.getRGB((int) tag.x() + 3, (int) (tag.y() + tag.height() / 2));
        assertTrue(offGrey(inside) > 60, "标签那里画了东西");
        assertTrue(offGrey(image.getRGB(20, 20)) < 12, "别处不动");
    }
}
