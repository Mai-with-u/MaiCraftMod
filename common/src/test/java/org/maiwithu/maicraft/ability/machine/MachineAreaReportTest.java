// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.MachineDetails.Connection;
import org.maiwithu.maicraft.ability.machine.MachineDetails.ConnectionKind;
import org.maiwithu.maicraft.ability.machine.MachineDetails.DeviceView;
import org.maiwithu.maicraft.ability.machine.MachineDetails.NetworkView;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.MountedPart;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 机器区报告：线缆合进网、设备与部件单列、三种连接、整网读数不重复，都按摆好的现场离线核对。 */
class MachineAreaReportTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 线缆合进网_挂在网上的设备不再逐面列网络面() {
        Scene scene = new Scene();
        MachineTestDoubles.FakeMachineType drive = scene.type("test:drive", MachineRole.STORAGE, Blocks.FURNACE);
        scene.type("test:cable", MachineRole.CABLE, Blocks.IRON_BARS);
        drive.portsAt = cell -> List.of(
                new ExchangePoint(cell, Direction.UP, ExchangePoint.Medium.ME, ExchangePoint.Flow.BOTH, "这一面能接线缆"),
                new ExchangePoint(cell, Direction.NORTH, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.INPUT,
                        "侧面配置：物品 input"));
        MachineTestDoubles.FakeNetworkReader me = scene.reader("me");
        scene.put(ORIGIN, Blocks.FURNACE);
        scene.put(ORIGIN.east(), Blocks.IRON_BARS);
        scene.put(ORIGIN.east(2), Blocks.IRON_BARS);
        for (BlockPos cell : List.of(ORIGIN, ORIGIN.east(), ORIGIN.east(2))) scene.on(cell, me, "me@0,64,0");

        MachineAreaReport.Result result = scene.report();

        assertEquals(1, result.networks().size(), "两格线缆和驱动器在同一张网上，只有一条网");
        NetworkView network = result.networks().getFirst();
        assertEquals("me@0,64,0", network.id());
        assertEquals(2, network.connectors().cells());
        assertEquals(Map.of("test:cable", 2), network.connectors().types());
        assertEquals(3, network.readings().get("成员数"), "整张网的读数在网那一条给");
        assertEquals(1, result.devices().size(), "线缆不当设备");
        DeviceView device = result.devices().getFirst();
        assertEquals(List.of("me@0,64,0"), device.on());
        // 挂在网上了：ME 面由网说明，不逐面列；侧面配置是网表示不了的，照列。
        assertEquals(1, device.ports().size());
        assertEquals(ExchangePoint.Medium.ITEM, device.ports().getFirst().medium());
    }

    @Test
    void 线缆上的部件单列成设备_总线朝着谁就连到谁_朝着空气的不算连接() {
        Scene scene = new Scene();
        MachineTestDoubles.FakeMachineType cable = scene.type("test:cable", MachineRole.CABLE, Blocks.IRON_BARS);
        scene.type("test:vault", MachineRole.STORAGE, Blocks.FURNACE);
        MachineState online = new MachineState(MachineState.Activity.RUNNING, null, null, List.of(), Map.of(), "");
        cable.parts = at -> at.equals(ORIGIN) ? List.of(new MountedPart(Direction.EAST, "test:export_bus", online),
                new MountedPart(Direction.UP, "test:terminal", online)) : List.of();
        MachineTestDoubles.FakeNetworkReader me = scene.reader("me");
        scene.put(ORIGIN, Blocks.IRON_BARS);
        scene.on(ORIGIN, me, "me@0,64,0");
        scene.put(ORIGIN.east(), Blocks.FURNACE);

        MachineAreaReport.Result result = scene.report();

        // 同一格的两个部件按六个方向的固定顺序：上面的终端在前，东面的总线在后；保险库在更东边一格。
        assertEquals(List.of("test:terminal", "test:export_bus", "test:vault"),
                result.devices().stream().map(DeviceView::type).toList());
        DeviceView bus = result.devices().get(1);
        assertEquals(MachineAreaReport.PART, bus.role());
        assertEquals(ORIGIN, bus.host());
        assertEquals(Direction.EAST, bus.side());
        assertEquals(List.of("me@0,64,0"), bus.on(), "部件挂在它所在线缆格的网上");
        assertEquals(MachineState.Activity.RUNNING, bus.state().activity());
        assertEquals(List.of(new Connection("d2", "d3", null, null, ConnectionKind.FACING)), result.connections(),
                "总线朝着保险库连过去；终端朝着空气，不算连接");
        assertEquals(2, result.parts());
    }

    @Test
    void 总线朝着的不是设备_写出那一格是什么方块() {
        Scene scene = new Scene();
        MachineTestDoubles.FakeMachineType cable = scene.type("test:cable", MachineRole.CABLE, Blocks.IRON_BARS);
        MachineState online = new MachineState(MachineState.Activity.RUNNING, null, null, List.of(), Map.of(), "");
        cable.parts = at -> List.of(new MountedPart(Direction.NORTH, "test:export_bus", online));
        scene.put(ORIGIN, Blocks.IRON_BARS);
        scene.world.put(ORIGIN.north(), Blocks.CHEST.defaultBlockState());

        MachineAreaReport.Result result = scene.report();

        assertEquals(List.of(new Connection("d1", null, ORIGIN.north(), "minecraft:chest", ConnectionKind.FACING)),
                result.connections());
    }

    @Test
    void 同种相邻合成一个设备_读数不一样按读数分组列格() {
        Scene scene = new Scene();
        MachineTestDoubles.FakeMachineType deployer =
                scene.type("test:deployer", MachineRole.PROCESSING, Blocks.FURNACE);
        deployer.stateAt = at -> new MachineState(MachineState.Activity.RUNNING, 256.0, null, List.of(),
                Map.of("held", at.getX() == 2 ? "minecraft:iron_ingot" : "empty"), "");
        for (int x = 0; x < 3; x++) scene.put(ORIGIN.east(x), Blocks.FURNACE);

        MachineAreaReport.Result result = scene.report();

        assertEquals(1, result.devices().size(), "一排三台机械手合成一个设备");
        DeviceView device = result.devices().getFirst();
        assertEquals(3, device.cells());
        assertNull(device.state(), "三台读数不一样，不给一份笼统的读数");
        assertEquals(2, device.states().size());
        assertEquals(List.of(ORIGIN, ORIGIN.east()), device.states().getFirst().cells());
        assertEquals("minecraft:iron_ingot", device.states().get(1).state().readings().get("held"),
                "第三台手里拿的东西照样看得到");
    }

    @Test
    void 加工处连到那一格_那一格空着也照写() {
        Scene scene = new Scene();
        MachineTestDoubles.FakeMachineType press = scene.type("test:press", MachineRole.PROCESSING, Blocks.FURNACE);
        scene.type("test:depot", MachineRole.STORAGE, Blocks.BARREL);
        press.portsAt = cell -> List.of(new ExchangePoint(cell.below(2), null, ExchangePoint.Medium.ITEM,
                ExchangePoint.Flow.BOTH, "加工处：正下方第二格"));
        scene.put(ORIGIN, Blocks.BARREL);
        scene.put(ORIGIN.above(2), Blocks.FURNACE);
        scene.put(ORIGIN.above(2).east(5), Blocks.FURNACE);

        MachineAreaReport.Result result = scene.report();

        // 置物台最低，编号 d1；两台压机在上面，按先西后东是 d2、d3。
        assertEquals(List.of(new Connection("d2", "d1", null, null, ConnectionKind.PROCESSING_SPOT),
                new Connection("d3", null, ORIGIN.east(5), "minecraft:air", ConnectionKind.PROCESSING_SPOT)),
                result.connections());
        assertNull(result.devices().get(1).ports(), "加工处改成连接，不再重复列进进出口");
    }

    @Test
    void 没有读取器的连接件按贴着连成段_贴着的设备挂到段上() {
        Scene scene = new Scene();
        scene.type("test:pipe", MachineRole.CABLE, Blocks.IRON_BARS);
        scene.type("test:tank", MachineRole.STORAGE, Blocks.FURNACE);
        scene.put(ORIGIN, Blocks.IRON_BARS);
        scene.put(ORIGIN.east(), Blocks.IRON_BARS);
        scene.put(ORIGIN.east(2), Blocks.FURNACE);
        scene.put(ORIGIN.east(5), Blocks.IRON_BARS);

        MachineAreaReport.Result result = scene.report();

        assertEquals(List.of("adjacent@0,64,0", "adjacent@5,64,0"),
                result.networks().stream().map(NetworkView::id).toList());
        assertEquals(MachineAreaReport.ADJACENT, result.networks().getFirst().kind());
        assertNull(result.networks().getFirst().readable(), "没有读取器的段没有整网读数");
        assertEquals(2, result.networks().getFirst().connectors().cells());
        assertEquals(List.of("adjacent@0,64,0"), result.devices().getFirst().on());
        assertEquals(0, result.readNetworks());
    }

    @Test
    void 整网读数只在网上给_设备与连接件读数里同名同值的不重复() {
        Scene scene = new Scene();
        MachineTestDoubles.FakeMachineType press = scene.type("test:press", MachineRole.PROCESSING, Blocks.FURNACE);
        MachineTestDoubles.FakeMachineType shaft = scene.type("test:shaft", MachineRole.TRANSMISSION, Blocks.IRON_BARS);
        MachineTestDoubles.FakeNetworkReader kinetic = scene.reader("kinetic");
        press.stateAt = at -> new MachineState(MachineState.Activity.RUNNING, 32.0, null, List.of(),
                Map.of("成员数", 2, "required_speed", 32.0), "");
        shaft.stateAt = at -> new MachineState(MachineState.Activity.RUNNING, 32.0, null, List.of(),
                Map.of("成员数", 2), "");
        scene.put(ORIGIN, Blocks.FURNACE);
        scene.put(ORIGIN.east(), Blocks.IRON_BARS);
        scene.on(ORIGIN, kinetic, "k");
        scene.on(ORIGIN.east(), kinetic, "k");

        MachineAreaReport.Result result = scene.report();

        assertEquals(Map.of("required_speed", 32.0), result.devices().getFirst().state().readings());
        assertNull(result.networks().getFirst().connectors().states().getFirst().state().readings(),
                "轴上只有整网读数，去掉后不再写读数");
        assertEquals(32.0, result.networks().getFirst().connectors().states().getFirst().state().speed(),
                "转速是轴自己的，照写");
    }

    @Test
    void 两个设备贴着_每对只写一次_读现场按格挑得出设备() {
        Scene scene = new Scene();
        scene.type("test:funnel", MachineRole.LOGISTICS, Blocks.FURNACE);
        scene.type("test:vault", MachineRole.STORAGE, Blocks.BARREL);
        scene.put(ORIGIN, Blocks.FURNACE);
        scene.put(ORIGIN.east(), Blocks.FURNACE);
        scene.put(ORIGIN.above(), Blocks.BARREL);
        scene.put(ORIGIN.above().east(), Blocks.BARREL);

        MachineAreaReport.Result result = scene.report();

        assertEquals(List.of(new Connection("d1", "d2", null, null, ConnectionKind.TOUCHING)), result.connections(),
                "两格漏斗贴着两格保险库，算一对，只写一次");
        assertEquals(List.of("d2"), result.devicesIn(Set.of(ORIGIN.above())));
        assertTrue(result.devicesIn(Set.of(ORIGIN.west())).isEmpty());
    }

    /** 一片离线现场：几种机器类型、认领的格、每格挂的网、读取器与世界里别的格。 */
    private static final class Scene {
        final List<MachineTestDoubles.FakeMachineType> types = new ArrayList<>();
        final Map<BlockPos, BlockState> claimed = new LinkedHashMap<>();
        final Map<BlockPos, Map<String, String>> memberships = new HashMap<>();
        final Map<BlockPos, BlockState> world = new HashMap<>();
        final List<NetworkReader> readers = new ArrayList<>();

        MachineTestDoubles.FakeMachineType type(String id, MachineRole role, Block block) {
            MachineTestDoubles.FakeMachineType type =
                    new MachineTestDoubles.FakeMachineType(id, id, role, state -> state.is(block));
            types.add(type);
            return type;
        }

        MachineTestDoubles.FakeNetworkReader reader(String kind) {
            MachineTestDoubles.FakeNetworkReader reader = new MachineTestDoubles.FakeNetworkReader(kind, "测试用的网");
            readers.add(reader);
            return reader;
        }

        void put(BlockPos at, Block block) {
            claimed.put(at, block.defaultBlockState());
            world.put(at, block.defaultBlockState());
        }

        void on(BlockPos at, MachineTestDoubles.FakeNetworkReader reader, String networkId) {
            memberships.computeIfAbsent(at, key -> new LinkedHashMap<>()).put(reader.kind().id(), networkId);
            reader.put(at, networkId);
        }

        MachineAreaReport.Result report() {
            Function<BlockState, MachineType> claiming = state -> types.stream()
                    .filter(type -> type.covers(state)).findFirst().orElse(null);
            List<MachineGrouping.Claimed> cells = claimed.entrySet().stream()
                    .map(entry -> new MachineGrouping.Claimed(entry.getKey(), claiming.apply(entry.getValue())))
                    .toList();
            List<MachineGrouping.Machine> machines =
                    MachineGrouping.group(cells, at -> memberships.getOrDefault(at, Map.of()));
            return new MachineAreaReport(claiming, readers,
                    at -> Optional.of(world.getOrDefault(at, Blocks.AIR.defaultBlockState())))
                    .report(machines, claimed, memberships, Set.of(), null);
        }
    }
}
