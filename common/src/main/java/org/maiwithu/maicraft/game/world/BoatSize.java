// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

/**
 * 原版船的尺寸：右键放船时服务端把船实体放在准星命中的那一点，碰撞箱 1.375 宽、0.5625 高，
 * 这箱子压到任何方块或别的身体（包括放船的人自己）服务端就不放、也不说一声。
 * 落地救援放船、出行划船放船都从这里取同一份尺寸，算放哪一点、放不放得下。
 */
public final class BoatSize {

    /** 碰撞箱半宽（格）。 */
    public static final double HALF_WIDTH = 0.6875;
    /** 碰撞箱高（格）。 */
    public static final double HEIGHT = 0.5625;
    /** 船宽（格）。 */
    public static final double WIDTH = HALF_WIDTH * 2;

    private BoatSize() {}
}
