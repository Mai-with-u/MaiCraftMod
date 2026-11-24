// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

/**
 * 耕地灌溉水源保护判断：识别任意耕地（{@code minecraft:farmland}）周围湿润范围内的非无限水水源格，
 * 防止自动装水或空桶舀水抽干农田灌溉水。
 *
 * <p>按原版耕地湿润范围，一格耕地能被水平 4 格内、同层或高 1 层（{@code dx∈[-4,4], dz∈[-4,4], dy∈[0,1]}）
 * 的水源湿润；反过来从水源格出发，只要水平 4 格内、同层或低 1 层（{@code dy∈[-1,0]}）存在耕地，
 * 且该水源格舀走后不会自动再生（非无限水），就视为受保护的农田灌溉水源。纯函数，离线可测。
 */
public final class FarmlandIrrigation {

    /** 原版耕地吸水水平半径（格）。游戏事实。 */
    public static final int HORIZONTAL_RADIUS = 4;
    /** 原版无限水至少需要相邻水平水源格数。游戏事实。 */
    public static final int INFINITE_WATER_MIN_NEIGHBORS = 2;

    /** 一格位置上的水车形态：没有水车、小水车或大水车及其旋转轴（读不到轴向时为 null）。 */
    public record WaterWheelAt(Kind kind, Direction.Axis axis) {

        /** 这一格不是水车。 */
        public static final WaterWheelAt NONE = new WaterWheelAt(Kind.NONE, null);

        /** 水车规格种类。 */
        public enum Kind { NONE, SMALL, LARGE }

        public WaterWheelAt {
            Objects.requireNonNull(kind, "kind");
        }

        public static WaterWheelAt small(Direction.Axis axis) {
            return new WaterWheelAt(Kind.SMALL, axis);
        }

        public static WaterWheelAt large(Direction.Axis axis) {
            return new WaterWheelAt(Kind.LARGE, axis);
        }
    }

    /** 读取一格水、耕地与水车事实的只读接缝：生产接客户端世界，测试用内存替身。 */
    public interface WaterGrid {

        /** 该只读视图对应的当前维度 ID；不在世界里或离线测试不区分维度时可为 null。 */
        default String dimension() {
            return null;
        }

        /** 这一格是否是水源格（纯水方块水源与含水方块均算）。 */
        boolean isWaterSource(BlockPos pos);

        /** 这一格是否含有水体（水源格与流动水均算），供水车斜上方供水判定核对驱动面是否有水。 */
        default boolean hasWater(BlockPos pos) {
            return isWaterSource(pos);
        }

        /**
         * 这一格是否是纯水方块（非含水活板门/台阶）：原版中只有纯水格被舀空后才可能由两侧水源自动再生，
         * 含水方块被舀干后不会自行变回含水状态。
         */
        default boolean isPureWaterBlock(BlockPos pos) {
            return isWaterSource(pos);
        }

        /** 这一格能否作为无限水底部的实心支撑（实心方块）。 */
        boolean isSolidFloor(BlockPos pos);

        /** 这一格是否是耕地（{@code minecraft:farmland}）。 */
        boolean isFarmland(BlockPos pos);

        /** 这一格是否是水车方块（小水车或大水车中心）及其旋转轴；不是水车返回 {@link WaterWheelAt#NONE}。 */
        default WaterWheelAt waterWheelAt(BlockPos pos) {
            return WaterWheelAt.NONE;
        }

        /** 空世界：没有水源也没有耕地。 */
        WaterGrid EMPTY = new WaterGrid() {
            @Override public boolean isWaterSource(BlockPos pos) {
                return false;
            }

            @Override public boolean isSolidFloor(BlockPos pos) {
                return false;
            }

            @Override public boolean isFarmland(BlockPos pos) {
                return false;
            }
        };
    }

    private FarmlandIrrigation() {}

    /**
     * 这一格是否是舀走后会由原版规则自动再生的无限水水源：
     * 本身是纯水源格、水平四邻至少 2 格为水源、且正下方是水源或实心方块。
     */
    public static boolean isInfiniteWaterSource(WaterGrid grid, BlockPos pos) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(pos, "pos");
        if (!grid.isWaterSource(pos) || !grid.isPureWaterBlock(pos)) return false;
        int adjacentSources = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (grid.isWaterSource(pos.relative(side))) {
                adjacentSources++;
            }
        }
        if (adjacentSources < INFINITE_WATER_MIN_NEIGHBORS) return false;
        BlockPos below = pos.below();
        return grid.isWaterSource(below) || grid.isSolidFloor(below);
    }

    /**
     * 这一格水源是否位于任意耕地的原版湿润范围内：
     * 原版耕地向水平 4 格、同层与高 1 层找水，故从水源出发检查水平 4 格、同层与低 1 层是否存在耕地。
     */
    public static boolean irrigatesFarmland(WaterGrid grid, BlockPos pos) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(pos, "pos");
        for (int dy = -1; dy <= 0; dy++) {
            for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
                for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                    if (grid.isFarmland(pos.offset(dx, dy, dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 这一格是否是受保护的耕地灌溉水源：本身是水源格、正在湿润范围内灌溉至少一格耕地，
     * 且舀走后不会自动再生（非无限水）。无限水（如 2×2 水池或 1×3 水渠中格）舀完即再生，放行。
     */
    public static boolean isProtectedIrrigationWater(WaterGrid grid, BlockPos pos) {
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(pos, "pos");
        return grid.isWaterSource(pos)
                && !isInfiniteWaterSource(grid, pos)
                && irrigatesFarmland(grid, pos);
    }

    /** 包装客户端当前世界为只读水格视图；不在世界里或目标格未加载时一律返回假。 */
    public static WaterGrid live(Supplier<ClientLevel> levelSupplier) {
        Objects.requireNonNull(levelSupplier, "levelSupplier");
        return new WaterGrid() {
            @Override public String dimension() {
                ClientLevel level = levelSupplier.get();
                return level == null ? null : level.dimension().location().toString();
            }

            @Override public boolean isWaterSource(BlockPos pos) {
                ClientLevel level = levelSupplier.get();
                return level != null && level.isLoaded(pos)
                        && level.getFluidState(pos).isSourceOfType(Fluids.WATER);
            }

            @Override public boolean hasWater(BlockPos pos) {
                ClientLevel level = levelSupplier.get();
                return level != null && level.isLoaded(pos)
                        && (level.getFluidState(pos).is(Fluids.WATER) || level.getFluidState(pos).is(Fluids.FLOWING_WATER));
            }

            @Override public boolean isPureWaterBlock(BlockPos pos) {
                ClientLevel level = levelSupplier.get();
                return level != null && level.isLoaded(pos)
                        && level.getBlockState(pos).is(Blocks.WATER)
                        && level.getFluidState(pos).isSourceOfType(Fluids.WATER);
            }

            @Override public boolean isSolidFloor(BlockPos pos) {
                ClientLevel level = levelSupplier.get();
                if (level == null || !level.isLoaded(pos)) return false;
                BlockState state = level.getBlockState(pos);
                return state.isSolidRender(level, pos);
            }

            @Override public boolean isFarmland(BlockPos pos) {
                ClientLevel level = levelSupplier.get();
                return level != null && level.isLoaded(pos)
                        && level.getBlockState(pos).is(Blocks.FARMLAND);
            }

            @Override public WaterWheelAt waterWheelAt(BlockPos pos) {
                ClientLevel level = levelSupplier.get();
                if (level == null || !level.isLoaded(pos)) return WaterWheelAt.NONE;
                // 只读取标准注册 ID 与原版方块状态属性，不依赖第三方模组类。
                BlockState state = level.getBlockState(pos);
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if ("create:water_wheel".equals(blockId)) {
                    Direction.Axis axis = state.hasProperty(BlockStateProperties.FACING)
                            ? state.getValue(BlockStateProperties.FACING).getAxis() : null;
                    return WaterWheelAt.small(axis);
                }
                if ("create:large_water_wheel".equals(blockId)) {
                    Direction.Axis axis = state.hasProperty(BlockStateProperties.AXIS)
                            ? state.getValue(BlockStateProperties.AXIS) : null;
                    return WaterWheelAt.large(axis);
                }
                return WaterWheelAt.NONE;
            }
        };
    }
}
