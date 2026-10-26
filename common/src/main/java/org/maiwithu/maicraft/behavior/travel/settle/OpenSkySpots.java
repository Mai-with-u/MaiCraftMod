// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.util.SpotScan;

import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.behavior.explore.OpenSky;
import org.maiwithu.maicraft.behavior.travel.planning.TravelWorldView;

/**
 * 露天落脚处：已加载区里头顶见天、站得下人的地面格——那一列顶上是实心方块（不是水、岩浆），顶上那一格站得下人。
 * 回露天时先找旁边几步的（站在崖檐下、洞口里，走出去就是），没有再找远处的交给走到去找路。纯判断，只读出行现场。
 */
public final class OpenSkySpots {

    /** 近处：水平几格以内每一列都看。类别：玩家常识（崖檐下、洞口里走几步就见天）。 */
    static final int NEAR_RADIUS = 8;
    /** 近处：比脚下高低不超过几格，再高就得爬上去了，不算"走几步就到"。 */
    static final int NEAR_RISE = 4;
    /** 远处：水平几格以内找。只看已加载区，再远的看不到。 */
    static final int FAR_RADIUS = 64;
    /** 远处每隔几格看一列：露天落脚处一大片挨着，隔几列看一列就够走到附近。 */
    static final int FAR_STEP = 4;
    /** 一次最多交给走到几处：走到挑走得到的最近那一处，处数太多只是让寻路每一步多比几回。 */
    static final int MAX_SPOTS = 48;

    private OpenSkySpots() {}

    /** 角色此刻站的地方露天了没有；不知道自己在哪时答没有。 */
    public static boolean standingUnderOpenSky(TravelWorldView world) {
        var spot = world.currentSpot();
        return spot != null && underOpenSky(world, new BlockPos(spot.x(), spot.y(), spot.z()));
    }

    /** 脚下这一列露天了没有：忽略树叶的最高遮挡物就在脚底。那一列没加载不算。 */
    public static boolean underOpenSky(TravelWorldView world, BlockPos feet) {
        return OpenSky.judge(world.column(feet.getX(), feet.getZ()), feet.getY()) instanceof OpenSky.Verdict.Open;
    }

    /** 旁边几步的露天落脚处，近的在前。 */
    public static List<BlockPos> near(TravelWorldView world, BlockPos feet) {
        return scan(world, feet, NEAR_RADIUS, 1, NEAR_RISE, Set.of());
    }

    /** 远处的露天落脚处，近的在前；skip 里的是走到过却没见天、或者已经交给走到试过的格。 */
    public static List<BlockPos> far(TravelWorldView world, BlockPos feet, Set<BlockPos> skip) {
        return scan(world, feet, FAR_RADIUS, FAR_STEP, Integer.MAX_VALUE, skip);
    }

    // 以脚下为中心按格网看列：顶盖是实心、不是液体，顶上那一格站得下人，高低差在范围内就收；
    // 隔几列看一列、圆圈外的列不看这些范围条件折进列函数，收下的按直线远近排，留最近的那些。
    private static List<BlockPos> scan(TravelWorldView world, BlockPos feet, int radius, int step, int maxRise,
            Set<BlockPos> skip) {
        return SpotScan.columns(feet.getX() - radius, feet.getZ() - radius,
                feet.getX() + radius, feet.getZ() + radius, step,
                (x, z) -> {
                    int dx = x - feet.getX();
                    int dz = z - feet.getZ();
                    if (dx * dx + dz * dz > radius * radius) return Optional.empty();
                    ColumnSample column = world.column(x, z);
                    if (!column.loaded() || column.topFluid() || !column.topBlocksMotion()) return Optional.empty();
                    BlockPos spot = new BlockPos(x, column.topY() + 1, z);
                    if (Math.abs(spot.getY() - feet.getY()) > maxRise || skip.contains(spot)) return Optional.empty();
                    if (!world.standable(x, spot.getY(), z)) return Optional.empty();
                    return Optional.of(spot);
                },
                Comparator.comparingDouble(spot -> spot.distSqr(feet)), MAX_SPOTS);
    }
}
