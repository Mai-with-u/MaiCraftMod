// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import net.minecraft.core.BlockPos;

/**
 * 暗处看不看得清：钻洞看矿时，视线没被挡住还不够，那一块还得够亮或者够近，屏幕上才认得出是矿。
 *
 * <p>洞里没插火把的地方，观众屏幕上是一片黑；角色要是照样说看见了钻石、走过去挖，和矿透没两样。
 * 所以暗处的矿要等火把、岩浆、发光方块把它照亮了，或者贴到跟前，才算看见——插了火把照亮了才看见洞壁上的矿，
 * 和玩家一样。岩浆自己会发光，看危险不受这一条限制；量路也不受。纯判断，亮度与位置由调用方给。
 */
public final class SeeingInTheDark {

    /**
     * 露出来的那一面挨着的空气格亮度到这么多才算照亮了（方块光与天空光取大，夜里天空光按天色减）。
     * 默认亮度设置下，亮度 4 的方块在屏幕上约有满亮的两成，矿石的斑点认得出来；再暗一档只剩一成多，和洞壁糊成一团黑影。
     * 随行补光把路上补到 8 以上，插下的火把能照亮周围十来格，照到的洞壁上的矿就看得见。
     */
    static final int LIT = 4;
    /** 离头这么近（格）就算黑着也认得出：贴着洞壁时眼前一两格的轮廓看得清，再远就是一片黑。 */
    static final double NEAR = 2;

    private SeeingInTheDark() {}

    /**
     * 看不看得清这一块。
     *
     * @param light 这一块露出来的那几面挨着的空气格里最亮的亮度（0..15）
     * @param head  角色头所在的那一格
     * @param block 那一块在哪
     */
    public static boolean canMakeOut(int light, BlockPos head, BlockPos block) {
        return light >= LIT || head.distSqr(block) <= NEAR * NEAR;
    }
}
