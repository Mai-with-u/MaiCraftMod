// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import org.maiwithu.maicraft.game.picture.CameraPicture;

import java.util.List;
import java.util.Locale;

/**
 * 取景：要看的那样东西整个在不在画面里、往后退几格能整个装下。纯函数。
 *
 * <p>只看它的外框（实体的碰撞箱、一格方块、建筑档案的范围）的八个角落在图上的哪里：
 * 都在画面里是整个装下，有的在有的不在是只拍到一部分，都不在是不在画面里；此刻看不见的直接算看不见。
 */
public final class PictureFraming {

    /** 算"退几格能装下"时留的边：装下以后四周还留一点空，不贴着画面边。 */
    static final double MARGIN = 1.15;

    private PictureFraming() {}

    /** 装没装进画面。 */
    public enum Framed {
        /** 整个在画面里。 */
        WHOLE,
        /** 只拍到一部分。 */
        PARTLY,
        /** 隔着东西看不见。 */
        HIDDEN,
        /** 不在画面里（没转过去时常见）。 */
        OUTSIDE;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 外框的角落落在图上的情形。
     *
     * @param corners 外框角落换到图上的位置（一格方块、实体、建筑都是八个角）
     * @param visible 此刻看不看得见它（和感知判断"看见"同一份视线）
     */
    public static Framed framed(List<CameraPicture.Projected> corners, int width, int height, boolean visible) {
        if (!visible) return Framed.HIDDEN;
        int inFrame = 0;
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean anyInFront = false;
        for (CameraPicture.Projected corner : corners) {
            if (corner.inFrame(width, height)) inFrame++;
            if (!corner.inFront()) continue;
            anyInFront = true;
            minX = Math.min(minX, corner.x());
            minY = Math.min(minY, corner.y());
            maxX = Math.max(maxX, corner.x());
            maxY = Math.max(maxY, corner.y());
        }
        if (!corners.isEmpty() && inFrame == corners.size()) return Framed.WHOLE;
        if (inFrame > 0) return Framed.PARTLY;
        // 一个角都不在画面里，外框却横跨画面（离得太近、整面墙占满画面）：也是只拍到一部分。
        boolean spans = anyInFront && maxX >= 0 && minX < width && maxY >= 0 && minY < height;
        return spans ? Framed.PARTLY : Framed.OUTSIDE;
    }

    /**
     * 按此刻的视野往后退约几格能整个装下。
     *
     * @param halfWidth    外框横着的一半（格），按水平对角线的一半算，哪个方向看都装得下
     * @param halfHeight   外框竖着的一半（格）
     * @param distance     镜头到外框中心多远（格）
     * @param tanHalfWidth 画面左右一半视野的正切
     */
    public static int stepBackBlocks(double halfWidth, double halfHeight, double distance, double tanHalfWidth,
                                     double tanHalfHeight) {
        double needed = Math.max(halfWidth / tanHalfWidth, halfHeight / tanHalfHeight) * MARGIN + halfWidth;
        return (int) Math.max(1, Math.ceil(needed - distance));
    }
}
