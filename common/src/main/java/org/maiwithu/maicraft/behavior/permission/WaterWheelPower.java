// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 水车动力水源保护判断：识别紧邻水车方块驱动面的水体以及正向驱动面供水的非无限水源，
 * 防止未建档的水车在自动装水或空桶改舀时被抽干停转。
 *
 * <p>物理几何按水车真实驱动面定义：
 * <ul>
 * <li>小水车（{@code create:water_wheel}）：垂直于旋转轴的 4 个紧邻格为驱动面；</li>
 * <li>大水车（{@code create:large_water_wheel}）：垂直于旋转轴、外圈半径 2 格的 12 个邻格为驱动面；</li>
 * <li>沿旋转轴两端的格子不驱动水车，不纳入保护。</li>
 * </ul>
 * 直接落在驱动面上的水源一律受保护；紧邻正在流水驱动面（正上方 1 格或水平 4 邻格）的非无限水源
 * （例如水车斜上方倒一桶水向下冲刷叶片）同样受保护，而驱动面外的无限水池（舀完自动再生）照常放行。
 */
public final class WaterWheelPower {

    /** 小水车每个旋转轴对应的 4 个驱动格偏移（水车中心到驱动水格）。 */
    private static final Map<Direction.Axis, Set<BlockPos>> SMALL_DRIVE_OFFSETS = buildSmallOffsets();
    /** 大水车每个旋转轴对应的 12 个外圈驱动格偏移（大水车中心到驱动水格）。 */
    private static final Map<Direction.Axis, Set<BlockPos>> LARGE_DRIVE_OFFSETS = buildLargeOffsets();

    private WaterWheelPower() {}

    /**
     * 这一格是不是某台小水车或大水车的直接驱动水格（紧贴叶片驱动面，排除轴端）。
     */
    public static boolean isDriveCell(FarmlandIrrigation.WaterGrid grid, BlockPos pos) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(pos, "pos");
        // 检查 6 邻格是否存在小水车，且当前格位于垂直于其旋转轴的驱动面上。
        for (Direction side : Direction.values()) {
            FarmlandIrrigation.WaterWheelAt wheel = grid.waterWheelAt(pos.relative(side));
            if (wheel.kind() == FarmlandIrrigation.WaterWheelAt.Kind.SMALL
                    && (wheel.axis() == null || wheel.axis() != side.getAxis())) {
                return true;
            }
        }
        // 按大水车驱动面反向偏移检查是否存在大水车中心，匹配所有 12 个外圈驱动格。
        for (Direction.Axis axis : Direction.Axis.values()) {
            for (BlockPos offset : LARGE_DRIVE_OFFSETS.get(axis)) {
                FarmlandIrrigation.WaterWheelAt wheel = grid.waterWheelAt(pos.subtract(offset));
                if (wheel.kind() == FarmlandIrrigation.WaterWheelAt.Kind.LARGE
                        && (wheel.axis() == null || wheel.axis() == axis)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 这一格水源是否属于受保护的水车动力水源：
     * 直接在驱动面上的水源无条件受保护；放在驱动面正上方或水平紧邻、正流向有水驱动面的非无限水源同样受保护。
     */
    public static boolean isProtectedPowerWater(FarmlandIrrigation.WaterGrid grid, BlockPos pos) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(pos, "pos");
        if (!grid.isWaterSource(pos)) return false;
        // 紧贴水车驱动面的水源格直接提供冲刷水流，一律不舀。
        if (isDriveCell(grid, pos)) return true;
        // 驱动面外的无限水（2x2 水池、河海等）舀走会立即再生，不导致水车停转，放行。
        if (FarmlandIrrigation.isInfiniteWaterSource(grid, pos)) return false;
        // 非无限水源位于有水驱动面的正上方 1 格（斜上方垂落冲刷）或水平紧邻 1 格（横向流进叶片）：属于直接供水动力源。
        if (isActiveDriveCell(grid, pos.below())) return true;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (isActiveDriveCell(grid, pos.relative(side))) return true;
        }
        return false;
    }

    private static boolean isActiveDriveCell(FarmlandIrrigation.WaterGrid grid, BlockPos cell) {
        return grid.hasWater(cell) && isDriveCell(grid, cell);
    }

    private static Map<Direction.Axis, Set<BlockPos>> buildSmallOffsets() {
        Map<Direction.Axis, Set<BlockPos>> map = new EnumMap<>(Direction.Axis.class);
        for (Direction.Axis axis : Direction.Axis.values()) {
            Set<BlockPos> offsets = new HashSet<>();
            for (Direction d : Direction.values()) {
                if (d.getAxis() != axis) {
                    offsets.add(BlockPos.ZERO.relative(d));
                }
            }
            map.put(axis, Set.copyOf(offsets));
        }
        return map;
    }

    private static Map<Direction.Axis, Set<BlockPos>> buildLargeOffsets() {
        Map<Direction.Axis, Set<BlockPos>> map = new EnumMap<>(Direction.Axis.class);
        for (Direction.Axis axis : Direction.Axis.values()) {
            Set<BlockPos> offsets = new HashSet<>();
            for (Direction d : Direction.values()) {
                if (d.getAxis() == axis) continue;
                BlockPos center = BlockPos.ZERO.relative(d, 2);
                offsets.add(center);
                for (Direction d2 : Direction.values()) {
                    if (d2.getAxis() == axis || d2.getAxis() == d.getAxis()) continue;
                    offsets.add(center.relative(d2));
                }
            }
            map.put(axis, Set.copyOf(offsets));
        }
        return map;
    }

    /** 暴露给测试验证的小水车某旋转轴的驱动格集合。 */
    static Set<BlockPos> smallOffsets(Direction.Axis axis) {
        return SMALL_DRIVE_OFFSETS.get(axis);
    }

    /** 暴露给测试验证的大水车某旋转轴的驱动格集合。 */
    static Set<BlockPos> largeOffsets(Direction.Axis axis) {
        return LARGE_DRIVE_OFFSETS.get(axis);
    }
}
