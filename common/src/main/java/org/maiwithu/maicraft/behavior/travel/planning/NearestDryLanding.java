// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.util.SpotScan;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 目标柱附近找最近能站的落点（纯函数）：只给 x/z 的目标柱整列是水时（贴岸的水边、码头边上），
 * 落点挑选不能停在那一柱水里——到达判据要站上干地才放行，落点留在水里就永远到不了，
 * 角色会泡在水里干等到超时。在目标柱四周一圈一圈往外找，谁的干地落点离目标柱最近就落谁；
 * 给定半径里没有干地给空，怎么收场由调用方定（把半径放大接着找、如实失败带建议落点）。
 * 挪目的地与给建议落点都从这里拿岸格：同一份干地事实，两个消费，不写第二份扫描。
 *
 * <p>每一柱"有没有能站的落点、落在哪一层"由调用方给的那一份干地事实回答
 * （与单柱落点挑选同源），这里只管往哪儿找、挑哪个，不另写站得住的规矩。
 */
public final class NearestDryLanding {

    private NearestDryLanding() {}

    /**
     * 在目标柱附近找最近的干地落点。
     *
     * @param x         目标柱的 x
     * @param z         目标柱的 z
     * @param radius    往外找几格（切比雪夫距离）：只找贴岸的这一圈，再远就是开阔水中央，不替玩家挪目的地
     * @param landingOf 一柱的落点挑选：给 x、z，回那一柱里能站的脚位高度；挑不出（整柱是水、没加载）给空
     * @param dimension 目的地的维度；原样带进落点
     * @return 最近的干地落点；附近没有给空
     */
    public static Optional<WorldPosition> near(int x, int z, int radius,
            BiFunction<Integer, Integer, OptionalInt> landingOf, String dimension) {
        // 环扫交给内核：方形一圈的边界原样传入，按到目标柱的实际水平距离挑最近的第一个；
        // 斜向上一圈里有几格远近不同，不按扫描先后取，一样近时保留先扫到的。
        List<BlockPos> spots = SpotScan.columns(x - radius, z - radius, x + radius, z + radius, 1,
                (columnX, columnZ) -> landingOf.apply(columnX, columnZ).stream()
                        .mapToObj(y -> new BlockPos(columnX, y, columnZ)).findFirst(),
                Comparator.comparingDouble(feet -> Math.hypot(feet.getX() - x, feet.getZ() - z)),
                1);
        return spots.isEmpty() ? Optional.empty()
                : Optional.of(new WorldPosition(spots.get(0).getX(), spots.get(0).getY(),
                        spots.get(0).getZ(), dimension));
    }
}
