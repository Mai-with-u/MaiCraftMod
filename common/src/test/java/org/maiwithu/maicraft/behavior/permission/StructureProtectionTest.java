// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 结构保护区与耕地灌溉水源规则离线测试：覆盖建筑/机器结构保护、耕地非无限水拦截与无限水放行、
 * 容器存储取存红线 100% 放行，以及被困脱困边界。
 */
class StructureProtectionTest {

    private static final String DIM = "minecraft:overworld";
    private static final String SELF = "self-player";

    /** 内存水格替身：支持纯水源、含水方块、流动水、实心底、耕地与水车布局。 */
    private static final class FakeWaterGrid implements FarmlandIrrigation.WaterGrid {
        final Set<BlockPos> pureWaterSources = new HashSet<>();
        final Set<BlockPos> waterloggedSources = new HashSet<>();
        final Set<BlockPos> flowingWaters = new HashSet<>();
        final Set<BlockPos> solidFloors = new HashSet<>();
        final Set<BlockPos> farmlands = new HashSet<>();
        final Map<BlockPos, FarmlandIrrigation.WaterWheelAt> waterWheels = new HashMap<>();
        String dim = DIM;

        @Override public String dimension() { return dim; }
        @Override public boolean isWaterSource(BlockPos pos) {
            return pureWaterSources.contains(pos) || waterloggedSources.contains(pos);
        }
        @Override public boolean hasWater(BlockPos pos) {
            return isWaterSource(pos) || flowingWaters.contains(pos);
        }
        @Override public boolean isPureWaterBlock(BlockPos pos) { return pureWaterSources.contains(pos); }
        @Override public boolean isSolidFloor(BlockPos pos) { return solidFloors.contains(pos); }
        @Override public boolean isFarmland(BlockPos pos) { return farmlands.contains(pos); }
        @Override public FarmlandIrrigation.WaterWheelAt waterWheelAt(BlockPos pos) {
            return waterWheels.getOrDefault(pos, FarmlandIrrigation.WaterWheelAt.NONE);
        }
    }

    @Test
    void 耕地周围非无限水拦截_含同层与低一层及四格边界与含水方块() {
        FakeWaterGrid grid = new FakeWaterGrid();
        BlockPos water = new BlockPos(0, 64, 0);
        grid.pureWaterSources.add(water);
        grid.solidFloors.add(water.below());

        // 周围没有耕地：不拦。
        assertFalse(FarmlandIrrigation.isProtectedIrrigationWater(grid, water));

        // 水平 4 格内、同层耕地：孤立水源拦截。
        grid.farmlands.add(new BlockPos(4, 64, -4));
        assertTrue(FarmlandIrrigation.isProtectedIrrigationWater(grid, water), "水平 4 格同层耕地应拦截孤立水源");

        // 水平 5 格耕地（超原版湿润半径 4）：不拦。
        grid.farmlands.clear();
        grid.farmlands.add(new BlockPos(5, 64, 0));
        assertFalse(FarmlandIrrigation.isProtectedIrrigationWater(grid, water), "超出 4 格范围不应误拦");

        // 耕地比水低 1 层（dy=-1，原版高 1 格的水能湿润低 1 格耕地）：拦截。
        grid.farmlands.clear();
        grid.farmlands.add(new BlockPos(2, 63, 0));
        assertTrue(FarmlandIrrigation.isProtectedIrrigationWater(grid, water), "比水低 1 层的耕地也在原版湿润范围内");

        // 耕地比水高 1 层（dy=+1，原版水无法湿润高 1 层的耕地）：不拦。
        grid.farmlands.clear();
        grid.farmlands.add(new BlockPos(1, 65, 0));
        assertFalse(FarmlandIrrigation.isProtectedIrrigationWater(grid, water), "比水高 1 层的耕地不在湿润范围内");

        // 1x2 农田水坑（水平只有 1 个邻水源，舀走不再生）：拦截。
        grid.farmlands.clear();
        grid.farmlands.add(new BlockPos(2, 64, 0));
        grid.pureWaterSources.add(new BlockPos(1, 64, 0));
        grid.solidFloors.add(new BlockPos(1, 63, 0));
        assertTrue(FarmlandIrrigation.isProtectedIrrigationWater(grid, water), "1x2 水坑舀走后不会再生，应拦截");
    }

    @Test
    void 耕地旁无限水放行_含2x2水池与1x3水渠中格_悬空与含水方块不视为无限水() {
        FakeWaterGrid grid = new FakeWaterGrid();
        // 2x2 无限水池 + 实心底 + 旁边耕地。
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) {
                grid.pureWaterSources.add(new BlockPos(x, 64, z));
                grid.solidFloors.add(new BlockPos(x, 63, z));
            }
        }
        grid.farmlands.add(new BlockPos(3, 64, 0));
        assertTrue(FarmlandIrrigation.isInfiniteWaterSource(grid, new BlockPos(0, 64, 0)));
        assertFalse(FarmlandIrrigation.isProtectedIrrigationWater(grid, new BlockPos(0, 64, 0)),
                "2x2 无限水池舀完自动再生，应放行");

        // 底部变成空气（悬空水格）：舀走后原版不再生，应拦截。
        grid.solidFloors.remove(new BlockPos(0, 63, 0));
        assertFalse(FarmlandIrrigation.isInfiniteWaterSource(grid, new BlockPos(0, 64, 0)));
        assertTrue(FarmlandIrrigation.isProtectedIrrigationWater(grid, new BlockPos(0, 64, 0)),
                "无实心底且下方非水的格不再生，应拦截");

        // 含水活板门/台阶即使两侧有水，原版舀干后也不会自动变回含水：应拦截。
        grid.solidFloors.add(new BlockPos(0, 63, 0));
        grid.pureWaterSources.remove(new BlockPos(0, 64, 0));
        grid.waterloggedSources.add(new BlockPos(0, 64, 0));
        assertFalse(FarmlandIrrigation.isInfiniteWaterSource(grid, new BlockPos(0, 64, 0)));
        assertTrue(FarmlandIrrigation.isProtectedIrrigationWater(grid, new BlockPos(0, 64, 0)),
                "含水方块舀干后不自动再生，应拦截");
    }

    @Test
    void 结构保护区独立于圈地与存储取存_箱子与终端百分之百放行() {
        WorldPosition chestInBuilding = new WorldPosition(10, 64, 10, DIM);
        WorldPosition terminalInMachine = new WorldPosition(20, 64, 20, DIM);
        WorldPosition farmWater = new WorldPosition(30, 64, 30, DIM);

        ReadsStructureProtection structures = new ReadsStructureProtection() {
            @Override public StructureProtection blockAt(WorldPosition pos) {
                if (pos.equals(chestInBuilding)) return StructureProtection.building("仓库小屋");
                if (pos.equals(terminalInMachine)) return StructureProtection.machine("ME主控室");
                return StructureProtection.NONE;
            }
        };
        FakeWaterGrid grid = new FakeWaterGrid();
        grid.pureWaterSources.add(new BlockPos(30, 64, 30));
        grid.solidFloors.add(new BlockPos(30, 63, 30));
        grid.farmlands.add(new BlockPos(32, 64, 30));

        Protection protection = new Protection((d, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), SELF, SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id,
                structures, grid);

        // 结构保护区准确命中建筑、机器与耕地灌溉水。
        assertEquals(StructureProtection.Route.BUILDING, protection.structureBlockAt(chestInBuilding).route());
        assertEquals(StructureProtection.Route.MACHINE, protection.structureBlockAt(terminalInMachine).route());
        assertEquals(StructureProtection.Route.FARMLAND_IRRIGATION, protection.structureFluidAt(farmWater).route());

        // 最高红线：storageUse / mayUseStorage 100% 放行建筑与机器内的箱子、木桶、ME 终端。
        assertTrue(protection.mayUseStorage(chestInBuilding, "minecraft:chest", Set.of()));
        assertEquals(StorageUse.USABLE, protection.storageUse(chestInBuilding, "minecraft:chest", Set.of()));
        assertTrue(protection.mayUseStorage(terminalInMachine, "ae2:cable_bus", Set.of()));
        assertEquals(StorageUse.USABLE, protection.storageUse(terminalInMachine, "ae2:cable_bus", Set.of()));

        // 圈地保护 protectedBy 保持独立，不误伤建筑改建/机器维护与作物收割。
        assertFalse(protection.blockProtected(chestInBuilding, "minecraft:oak_planks", Set.of()));
        assertFalse(protection.blockProtected(terminalInMachine, "create:gearbox", Set.of()));

        // 野外卡坑脱困不挖穿建筑或机器。
        assertFalse(protection.breakableWhenTrapped(chestInBuilding, false));
        assertFalse(protection.breakableWhenTrapped(terminalInMachine, false));
    }

    @Test
    void 被困自家拆墙脱困允许普通房墙但绝不拆机器() {
        WorldPosition wall = new WorldPosition(10, 64, 10, DIM);
        WorldPosition machine = new WorldPosition(11, 64, 10, DIM);
        WorldPosition homeCenter = new WorldPosition(10, 64, 10, DIM);

        ReadsStructureProtection structures = pos -> {
            if (pos.equals(wall)) return StructureProtection.building("小屋");
            if (pos.equals(machine)) return StructureProtection.machine("鼓风机");
            return StructureProtection.NONE;
        };
        ReadsRememberedPlaces places = new ReadsRememberedPlaces() {
            @Override public Optional<WorldPosition> place(String name) {
                return "家".equals(name) ? Optional.of(homeCenter) : Optional.empty();
            }
            @Override public Map<String, WorldPosition> places() { return Map.of("家", homeCenter); }
        };
        Protection protection = new Protection((d, x, y, z) -> Optional.empty(), List::of, places, SELF,
                SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id, structures, FarmlandIrrigation.WaterGrid.EMPTY);

        assertTrue(protection.breakableAtHome(wall, false), "被困自家屋里时普通房墙仍可按拆后补回机制脱困");
        assertFalse(protection.breakableAtHome(machine, false), "在役机器格绝不能当房墙拆掉");
    }

    @Test
    void 已接上结构保护接缝返回NONE时不再回退旧自家快照_未接上接缝时保留兼容回退() {
        WorldPosition retiredWall = new WorldPosition(40, 64, 40, DIM);
        // 模拟建筑退役后 5 秒缓存的 OwnHomeSnapshot 尚未刷新，仍返回旧建筑名。
        ReadsOwnHome staleHome = new ReadsOwnHome() {
            @Override public Optional<WorldPosition> sleptBed() { return Optional.empty(); }
            @Override public List<Building> ownBuildings() { return List.of(); }
            @Override public Optional<String> archivedBuildingAt(WorldPosition position) {
                return position.equals(retiredWall) ? Optional.of("已退役旧屋") : Optional.empty();
            }
        };

        // 已接上独立结构保护接缝（1 秒刷新已解除保护返回 NONE）：不得被旧 OwnHome 残留重新锁住。
        ReadsStructureProtection freshStructures = pos -> StructureProtection.NONE;
        Protection withStructures = new Protection((d, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), SELF, SeesVillagers.NOBODY, staleHome, id -> id,
                freshStructures, FarmlandIrrigation.WaterGrid.EMPTY);
        assertFalse(withStructures.structureBlockAt(retiredWall).protects(),
                "已接上结构保护接缝返回 NONE 时不应回退旧 OwnHome 残留");
        assertFalse(withStructures.structureFluidAt(retiredWall).protects(),
                "已接上结构保护接缝返回 NONE 时流体检查同样不受旧 OwnHome 残留影响");

        // 未接上结构保护接缝（ReadsStructureProtection.NONE）：兼容只传 ReadsOwnHome 的旧离线测试。
        Protection fallbackOnly = new Protection((d, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), SELF, SeesVillagers.NOBODY, staleHome, id -> id,
                ReadsStructureProtection.NONE, FarmlandIrrigation.WaterGrid.EMPTY);
        assertEquals(StructureProtection.building("已退役旧屋"), fallbackOnly.structureBlockAt(retiredWall));
        assertEquals(StructureProtection.building("已退役旧屋"), fallbackOnly.structureFluidAt(retiredWall));
    }

    @Test
    void 水车驱动面动力水源不依赖建档即受保护_含小水车大水车斜上供水且不误伤轴端与外部无限水() {
        FakeWaterGrid grid = new FakeWaterGrid();
        // 小水车：中心 (0, 64, 0)，旋转轴 Z（东西上下 4 个紧邻格为驱动面，南北轴端不是驱动面）。
        BlockPos smallWheel = new BlockPos(0, 64, 0);
        grid.waterWheels.put(smallWheel, FarmlandIrrigation.WaterWheelAt.small(Direction.Axis.Z));
        BlockPos topDrive = new BlockPos(0, 65, 0);
        BlockPos westDrive = new BlockPos(-1, 64, 0);
        BlockPos axleEnd = new BlockPos(0, 64, 1);
        BlockPos upperDiagFeeder = new BlockPos(-1, 65, 0);
        grid.pureWaterSources.add(topDrive);
        grid.pureWaterSources.add(axleEnd);
        grid.pureWaterSources.add(upperDiagFeeder);
        grid.flowingWaters.add(westDrive);

        assertTrue(WaterWheelPower.isProtectedPowerWater(grid, topDrive), "紧贴小水车顶面驱动格的水源应受保护");
        assertTrue(WaterWheelPower.isProtectedPowerWater(grid, upperDiagFeeder), "水车斜上方给有水驱动面供水的非无限水源应受保护");
        assertFalse(WaterWheelPower.isProtectedPowerWater(grid, axleEnd), "沿旋转轴端不驱动水车的水源不应误拦");

        // 大水车：中心 (20, 64, 0)，旋转轴 Z（外圈 12 个驱动格，如 (20, 66, 0)、(21, 66, 0)、(22, 65, 0)）。
        BlockPos largeWheel = new BlockPos(20, 64, 0);
        grid.waterWheels.put(largeWheel, FarmlandIrrigation.WaterWheelAt.large(Direction.Axis.Z));
        BlockPos largeRimTop = new BlockPos(20, 66, 0);
        BlockPos largeRimCorner = new BlockPos(22, 65, 0);
        BlockPos largeAxleSide = new BlockPos(20, 64, 2);
        grid.pureWaterSources.add(largeRimTop);
        grid.pureWaterSources.add(largeRimCorner);
        grid.pureWaterSources.add(largeAxleSide);

        assertEquals(4, WaterWheelPower.smallOffsets(Direction.Axis.Z).size());
        assertEquals(12, WaterWheelPower.largeOffsets(Direction.Axis.Z).size());
        assertTrue(WaterWheelPower.isProtectedPowerWater(grid, largeRimTop), "大水车外圈顶部驱动格水源应受保护");
        assertTrue(WaterWheelPower.isProtectedPowerWater(grid, largeRimCorner), "大水车外圈侧角驱动格水源应受保护");
        assertFalse(WaterWheelPower.isProtectedPowerWater(grid, largeAxleSide), "大水车轴向位置不应误判为驱动面");

        // 驱动面外的 2x2 无限水池：即使贴在有水驱动面旁边，舀走会立即再生，应放行。
        FakeWaterGrid infiniteGrid = new FakeWaterGrid();
        infiniteGrid.waterWheels.put(new BlockPos(0, 64, 0), FarmlandIrrigation.WaterWheelAt.small(Direction.Axis.Z));
        infiniteGrid.flowingWaters.add(new BlockPos(1, 64, 0));
        for (int x = 2; x <= 3; x++) {
            for (int z = 0; z <= 1; z++) {
                infiniteGrid.pureWaterSources.add(new BlockPos(x, 64, z));
                infiniteGrid.solidFloors.add(new BlockPos(x, 63, z));
            }
        }
        assertFalse(WaterWheelPower.isProtectedPowerWater(infiniteGrid, new BlockPos(2, 64, 0)),
                "驱动面外的 2x2 无限水池舀完自动再生，不应误拦");

        // 不建任何机器档案，直接通过 Protection.structureFluidAt 也能拦截水车动力水源。
        Protection protection = new Protection((d, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), SELF, SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id,
                ReadsStructureProtection.NONE, grid);
        StructureProtection hit = protection.structureFluidAt(new WorldPosition(0, 65, 0, DIM));
        assertEquals(StructureProtection.Route.WATER_WHEEL_POWER, hit.route());
        assertTrue(hit.describe().contains("水车"), hit.describe());
    }
}
