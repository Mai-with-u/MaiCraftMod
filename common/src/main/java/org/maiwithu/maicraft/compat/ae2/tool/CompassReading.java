// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 陨石罗盘玩家肉眼可见的 3 种指针状态换算（纯函数）：
 * 严格对齐 AE2 MeteoriteCompassBakedModel.getAnimatedRotation 的渲染口径：
 * <ul>
 *   <li>无目标或服务端仍在查询：{@code searching_spinning}（指针每 0.5 秒转一圈，不输出方向）；</li>
 *   <li>与最近陨石水平距离 ≤ 6 格：{@code nearby_spinning}（指针每 3 秒慢转一圈，不输出方向）；</li>
 *   <li>与最近陨石水平距离 ＞ 6 格：{@code pointing}（指针稳定指向目标水平八向方位）。</li>
 * </ul>
 * 严禁对外透出精确水平格数距离与高度 Y 坐标。
 */
public record CompassReading(String status, String direction) {

    public static final String STATUS_POINTING = "pointing";
    public static final String STATUS_NEARBY_SPINNING = "nearby_spinning";
    public static final String STATUS_SEARCHING_SPINNING = "searching_spinning";

    /** AE2 陨石罗盘从稳定指向切换为近处慢转的水平距离阈值（格）。F 游戏事实。 */
    public static final double NEARBY_RADIUS_BLOCKS = 6.0;
    private static final double NEARBY_RADIUS_SQ = NEARBY_RADIUS_BLOCKS * NEARBY_RADIUS_BLOCKS;

    public CompassReading {
        Objects.requireNonNull(status, "status");
        direction = direction == null ? "" : direction;
    }

    /** 仅在 pointing 状态时返回非空的八向水平方位，其余两种旋转状态恒返回空字符串。 */
    public boolean hasDirection() {
        return STATUS_POINTING.equals(status) && !direction.isEmpty();
    }

    /** 根据角色水平坐标与 AE2 罗盘返回的目标坐标换算玩家可见的指针状态（不保留任何精确距离与 Y 坐标）。 */
    public static CompassReading from(Vec3 playerPos, BlockPos closestMeteorite) {
        if (playerPos == null || closestMeteorite == null) {
            return new CompassReading(STATUS_SEARCHING_SPINNING, "");
        }
        double dx = closestMeteorite.getX() - playerPos.x;
        double dz = closestMeteorite.getZ() - playerPos.z;
        double distSq = dx * dx + dz * dz;
        if (distSq <= NEARBY_RADIUS_SQ) {
            return new CompassReading(STATUS_NEARBY_SPINNING, "");
        }
        return new CompassReading(STATUS_POINTING, eightWayDirection(dx, dz));
    }

    /** 将水平位移向量 (dx, dz) 映射为八向水平方位（-Z 为北、+X 为东、+Z 为南、-X 为西）。 */
    public static String eightWayDirection(double dx, double dz) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        if (degrees < 0.0) degrees += 360.0;
        if (degrees >= 337.5 || degrees < 22.5) return "north";
        if (degrees < 67.5) return "northeast";
        if (degrees < 112.5) return "east";
        if (degrees < 157.5) return "southeast";
        if (degrees < 202.5) return "south";
        if (degrees < 247.5) return "southwest";
        if (degrees < 292.5) return "west";
        return "northwest";
    }
}
