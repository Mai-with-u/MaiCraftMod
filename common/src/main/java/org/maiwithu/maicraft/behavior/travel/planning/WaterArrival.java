// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.travel.settle.RemainingDistance;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.Optional;

/**
 * 水面目标柱的收场话术（纯函数）：目标柱整列是水时，默认态落到了挑出来的岸格，
 * 到达结果要写明"目标在水中央、落到了哪、偏差几格"，不冒充站在了玩家指的那一格；
 * 严格态（与默认态附近实在找不到岸）如实失败，把最近能站的岸格当建议落点带上——
 * 两种失败共用同一个格式，调用方拿同一形状的建议改坐标重发或放弃。
 */
public final class WaterArrival {

    private WaterArrival() {}

    /** 开阔水挪到岸格后的到达说明：落到了哪一格、离玩家指的那一柱偏差几格（水平直线距离）。 */
    public static String detourNote(int targetX, int targetZ, WorldPosition landing) {
        double deviation = Math.hypot(landing.x() - targetX, landing.z() - targetZ);
        return "目标在水中央，落到了 (" + landing.x() + ", " + landing.y() + ", " + landing.z()
                + ")，偏差 " + String.format("%.1f", deviation) + " 格";
    }

    /**
     * 目标柱整列是水又不去岸格的失败：写明为什么没挪（严格到达不自动挪，或附近实在没有岸），
     * 建议落点带坐标与相对目标的方位和距离。
     *
     * @param x          目标柱的 x
     * @param z          目标柱的 z
     * @param strict     是不是严格到达（true 时不自动挪是玩家要的，不是找不到）
     * @param suggestion 附近最近能站的岸格；周围连岸都没有时为空
     */
    public static Problem waterColumnFailure(int x, int z, boolean strict, Optional<WorldPosition> suggestion) {
        StringBuilder text = new StringBuilder("目标那一列整列是水，")
                .append(strict ? "严格到达不自动挪去别处" : "附近没有找到能站的岸格");
        if (suggestion.isPresent()) {
            WorldPosition spot = suggestion.get();
            // 方位与距离按目标柱这一列量：建议落在哪一边、差几格，调用方照着改坐标。
            RemainingDistance away = RemainingDistance.measure(
                    new BlockPos(x, spot.y(), z), new BlockPos(spot.x(), spot.y(), spot.z()));
            text.append("。最近能站的岸格在 (").append(spot.x()).append(", ").append(spot.y()).append(", ")
                    .append(spot.z()).append(")，在目标 ").append(away.describe());
        } else {
            text.append("，周围也没有找到能站的岸格");
        }
        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, text.toString(),
                "按建议的岸格坐标重新下达 travel，或去掉 strict_arrival 让角色自动挪到能站的地方");
    }
}
