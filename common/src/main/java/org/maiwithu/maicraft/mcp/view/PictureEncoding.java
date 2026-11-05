// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import org.maiwithu.maicraft.game.picture.CameraPicture;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;

/**
 * 把取到的画面画上标注、编成 JPEG：在 MCP 的请求线程上做，不占渲染线程。
 *
 * <p>标签照 {@link PictureLabels} 摆好的位置画：深色半透明底、白字写编号，边框颜色按种类分
 * （实体黄、设施与告示牌青、地形特征绿），要看的那一样边框加粗；下边一个小尖角指着那一点。
 * 只画在交给 LLM 的这一份拷贝上，直播画面上不出现。
 */
public final class PictureEncoding {

    /** JPEG 质量：单张约一两百 KB，小字还认得出。 */
    static final float JPEG_QUALITY = 0.85f;

    private static final Color BACKGROUND = new Color(0, 0, 0, 170);
    private static final Color TEXT = Color.WHITE;
    private static final Color ENTITY = new Color(255, 214, 0);
    private static final Color FACILITY = new Color(0, 220, 230);
    private static final Color FEATURE = new Color(80, 220, 80);

    private PictureEncoding() {}

    /** 画上标注（没摆标签的不画），编成 JPEG 的字节。 */
    public static byte[] jpeg(CameraPicture picture, List<PictureLabels.Placement> placements) {
        BufferedImage image = new BufferedImage(picture.width(), picture.height(), BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, picture.width(), picture.height(), picture.rgb(), 0, picture.width());
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, PictureLabels.fontSize(picture.height()));
            graphics.setFont(font);
            for (PictureLabels.Placement placement : placements) {
                if (placement.labeled()) draw(graphics, placement, picture.height());
            }
        } finally {
            graphics.dispose();
        }
        return encode(image);
    }

    // 一个标签：先画指着那一点的小尖角，再画底和边框，最后把编号居中写上。
    private static void draw(Graphics2D graphics, PictureLabels.Placement placement, int height) {
        boolean target = placement.candidate().target();
        PictureLabels.Rect tag = placement.tag();
        Color edge = edgeColor(placement.candidate().rank());
        int x = (int) Math.round(tag.x());
        int y = (int) Math.round(tag.y());
        int w = (int) Math.round(tag.width());
        int h = (int) Math.round(tag.height());
        int anchorX = (int) Math.round(placement.candidate().x());
        int anchorY = (int) Math.round(placement.candidate().y());
        int half = Math.max(3, PictureLabels.pointer(height) / 2 + 1);
        int baseX = Math.max(x + half, Math.min(x + w - half, anchorX));
        int baseY = anchorY < y ? y : y + h;
        graphics.setColor(edge);
        graphics.fillPolygon(new Polygon(new int[] {baseX - half, baseX + half, anchorX},
                new int[] {baseY, baseY, anchorY}, 3));
        graphics.setColor(BACKGROUND);
        graphics.fillRoundRect(x, y, w, h, 6, 6);
        graphics.setColor(edge);
        graphics.setStroke(new BasicStroke(target ? 3f : 1.5f));
        graphics.drawRoundRect(x, y, w, h, 6, 6);
        graphics.setColor(TEXT);
        FontMetrics metrics = graphics.getFontMetrics();
        String id = placement.candidate().id();
        int textX = x + (w - metrics.stringWidth(id)) / 2;
        int textY = y + (h - metrics.getHeight()) / 2 + metrics.getAscent();
        graphics.drawString(id, textX, textY);
    }

    private static Color edgeColor(PictureLabels.Rank rank) {
        return switch (rank) {
            case FACILITY -> FACILITY;
            case FEATURE -> FEATURE;
            default -> ENTITY;
        };
    }

    private static byte[] encode(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("这台机器的 Java 没有 JPEG 编码器");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException failure) {
            throw new UncheckedIOException("画面编不成 JPEG", failure);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
