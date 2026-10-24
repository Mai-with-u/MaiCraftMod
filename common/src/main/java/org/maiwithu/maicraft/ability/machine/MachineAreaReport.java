// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.MachineDetails.Box;
import org.maiwithu.maicraft.ability.machine.MachineDetails.Connection;
import org.maiwithu.maicraft.ability.machine.MachineDetails.ConnectionKind;
import org.maiwithu.maicraft.ability.machine.MachineDetails.ConnectorState;
import org.maiwithu.maicraft.ability.machine.MachineDetails.Connectors;
import org.maiwithu.maicraft.ability.machine.MachineDetails.DeviceView;
import org.maiwithu.maicraft.ability.machine.MachineDetails.NetworkView;
import org.maiwithu.maicraft.ability.machine.MachineDetails.StateGroup;
import org.maiwithu.maicraft.ability.machine.MachineDetails.StateView;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.MountedPart;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;

/**
 * 机器区报告：把一眼认领的机器方块整理成网、设备与连接三块。只读给定的读数、给结论，不动角色。
 *
 * <p>线缆、传动件、管道（机器角色是线缆或传动的格）只负责连接：合进它们所在的网，只给格数、种类计数与
 * 按读数分组的运行状态；没有网络读取器的连接件按贴着连成一段。其余机器方块是设备：同一台（分组规则在
 * {@link MachineGrouping}，查看与操作共用，这里不改）里同种相邻的格合成一个；线缆格上装着的部件各算一个设备。
 * 连接有三种：部件朝着的那一格、设备的加工处、两个设备贴着。
 *
 * <p>整张网的读数只在网那一条给，设备与连接件读数里和所在网同名同值的项不再重复；
 * 已经挂在网上的设备不再逐面列 ME 面与转动面（网已经说明它接着），其余进出口照列。不按字数或条数截断。
 */
final class MachineAreaReport {

    /** 没有网络读取器的连接件段的种类。 */
    static final String ADJACENT = "adjacent";
    /** 部件当设备时的角色。 */
    static final String PART = "part";
    private static final String ADJACENT_DESCRIPTION =
            "没有网络读取器的连接件（流体管道这类）按贴着连成的一段：通不通、里面有什么以各格读数为准";
    /** 先低后高、先西后东、先北后南：同一片每次看，编号与顺序都一样。 */
    private static final Comparator<BlockPos> LOW_FIRST = Comparator.comparingInt((BlockPos cell) -> cell.getY())
            .thenComparingInt(cell -> cell.getX()).thenComparingInt(cell -> cell.getZ());

    private final Function<BlockState, MachineType> claiming;
    private final List<NetworkReader> readers;
    private final Function<BlockPos, Optional<BlockState>> world;

    /**
     * @param claiming 认领一格方块状态的机器类型
     * @param readers  登记了的网络读取器：给网的说明与整张网的读数
     * @param world    任一格现在是什么方块（部件朝着的、加工处那一格）；没加载给空
     */
    MachineAreaReport(Function<BlockState, MachineType> claiming, List<NetworkReader> readers,
            Function<BlockPos, Optional<BlockState>> world) {
        this.claiming = Objects.requireNonNull(claiming, "claiming");
        this.readers = List.copyOf(readers);
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * 整理好的三块，外加每个设备占的格（读现场时按格挑设备）。
     *
     * @param deviceCells 设备编号 → 它占的格；部件记它所在的线缆格
     */
    record Result(List<NetworkView> networks, List<DeviceView> devices, List<Connection> connections,
                  Map<String, Set<BlockPos>> deviceCells) {

        static final Result EMPTY = new Result(List.of(), List.of(), List.of(), Map.of());

        Result {
            networks = List.copyOf(networks);
            devices = List.copyOf(devices);
            connections = List.copyOf(connections);
            deviceCells = Map.copyOf(deviceCells);
        }

        /** 有格落在这些格里的设备编号，按编号顺序。 */
        List<String> devicesIn(Collection<BlockPos> cells) {
            Set<BlockPos> wanted = Set.copyOf(cells);
            return devices.stream().map(DeviceView::id)
                    .filter(id -> deviceCells.get(id).stream().anyMatch(wanted::contains)).toList();
        }

        /** 线缆上的部件有几个。 */
        long parts() {
            return devices.stream().filter(device -> PART.equals(device.role())).count();
        }

        /** 网络读取器认出来的网有几张（不算没有读取器的连接件段）。 */
        long readNetworks() {
            return networks.stream().filter(network -> !ADJACENT.equals(network.kind())).count();
        }
    }

    /**
     * 整理一眼的机器方块。
     *
     * @param machines     分好台的机器（分组规则不在这里）
     * @param claimed      认领了的格与它们的方块状态
     * @param memberships  每格挂着的网（网络种类 → 网络编号）；不在网上的格给空表或不给
     * @param archiveCells 所看档案的格；没看档案给空
     * @param archiveName  所看档案的名字；没看档案为 null
     */
    Result report(List<MachineGrouping.Machine> machines, Map<BlockPos, BlockState> claimed,
            Map<BlockPos, Map<String, String>> memberships, Set<BlockPos> archiveCells, String archiveName) {
        if (machines.isEmpty()) return Result.EMPTY;
        Map<BlockPos, MachineType> types = new LinkedHashMap<>();
        for (MachineGrouping.Machine machine : machines) {
            for (BlockPos cell : machine.cells()) types.put(cell, claiming.apply(claimed.get(cell)));
        }
        Site site = new Site(types, claimed, memberships, archiveCells, archiveName);
        List<Device> devices = devices(machines, site);
        return new Result(networks(site), devices.stream().map(device -> view(device, site)).toList(),
                connections(devices, site), cellsOf(devices));
    }

    // 网：读取器认出来的每张网一条（连接件不在范围里时只给整张网的读数），再把没有读取器的连接件按贴着连成段，各一条。
    private List<NetworkView> networks(Site site) {
        List<NetworkView> out = new ArrayList<>();
        site.kindOf.forEach((id, kind) -> {
            List<BlockPos> members = site.links().stream()
                    .filter(cell -> site.networksAt(cell).contains(id)).toList();
            NetworkSummary summary = site.summaries.get(id);
            String description = reader(kind).map(reader -> reader.kind().description()).orElse("");
            out.add(new NetworkView(id, kind, description, summary == null ? null : summary.readable(),
                    summary == null ? null : summary.readings(), summary == null ? null : summary.note(),
                    members.isEmpty() ? null : connectors(members, site), site.archiveOf(members)));
        });
        for (List<BlockPos> segment : site.segments) {
            out.add(new NetworkView(segmentId(segment), ADJACENT, ADJACENT_DESCRIPTION, null, null, null,
                    connectors(segment, site), site.archiveOf(segment)));
        }
        return out;
    }

    // 一张网的连接件：总数、范围、按机器类型计数；读数一样的格合成一组，每组给几格与在哪一片。
    private static Connectors connectors(List<BlockPos> cells, Site site) {
        Map<String, Integer> types = new LinkedHashMap<>();
        Map<StateView, List<BlockPos>> byState = new LinkedHashMap<>();
        for (BlockPos cell : cells) {
            types.merge(site.types.get(cell).id(), 1, Integer::sum);
            byState.computeIfAbsent(site.state(cell), key -> new ArrayList<>()).add(cell);
        }
        List<ConnectorState> states = byState.entrySet().stream()
                .map(entry -> new ConnectorState(entry.getValue().size(), single(entry.getValue()),
                        box(entry.getValue()), entry.getKey()))
                .toList();
        return new Connectors(cells.size(), single(cells), box(cells), types, states);
    }

    // 设备：每台里同种相邻的非连接件格合成一个，线缆格上的部件各一个；按最低那一格排好再编号。
    private List<Device> devices(List<MachineGrouping.Machine> machines, Site site) {
        List<Device> devices = new ArrayList<>();
        for (MachineGrouping.Machine machine : machines) {
            Map<MachineType, List<BlockPos>> byType = new LinkedHashMap<>();
            for (BlockPos cell : machine.cells()) {
                MachineType type = site.types.get(cell);
                if (!isConnector(type)) byType.computeIfAbsent(type, key -> new ArrayList<>()).add(cell);
            }
            byType.forEach((type, cells) -> {
                for (List<BlockPos> piece : components(cells)) devices.add(Device.ofBlocks(type, piece));
            });
        }
        site.types.forEach((cell, type) -> {
            for (MountedPart part : type.mountedParts(cell)) devices.add(Device.ofPart(cell, part));
        });
        // 同一格上方块设备排在部件前面，同一格的几个部件按六个方向的固定顺序。
        devices.sort(Comparator.comparing((Device device) -> device.cells.getFirst(), LOW_FIRST)
                .thenComparing(device -> device.part != null)
                .thenComparing(device -> device.part == null ? 0 : device.part.side().ordinal()));
        for (int i = 0; i < devices.size(); i++) {
            devices.get(i).order = i;
            devices.get(i).id = "d" + (i + 1);
        }
        return devices;
    }

    // 一个设备的样子：挂在哪些网上、读数（一样的写一份，不一样的按读数分组列格）、网表示不了的进出口。
    private DeviceView view(Device device, Site site) {
        List<String> on = new ArrayList<>(networksOf(device, site));
        if (device.part != null) {
            return new DeviceView(device.id, device.part.itemId(), PART, null, null, null, device.cells.getFirst(),
                    device.part.side(), on, site.archiveOf(device.cells),
                    StateView.of(device.part.state(), device.part.state().readings()), null, null);
        }
        Map<StateView, List<BlockPos>> byState = new LinkedHashMap<>();
        for (BlockPos cell : device.cells) byState.computeIfAbsent(site.state(cell), key -> new ArrayList<>()).add(cell);
        StateView state = byState.size() == 1 ? byState.keySet().iterator().next() : null;
        List<StateGroup> states = byState.size() == 1 ? null : byState.entrySet().stream()
                .map(entry -> new StateGroup(entry.getValue(), entry.getKey())).toList();
        int count = device.cells.size();
        return new DeviceView(device.id, device.type.id(), device.type.role().name().toLowerCase(Locale.ROOT),
                single(device.cells), count == 1 ? null : count, box(device.cells), null, null, on,
                site.archiveOf(device.cells), state, states, ports(device, site));
    }

    // 设备挂在哪些网上：各格（部件看它所在的线缆格）的网络编号，再加上贴着它的连接件段。
    private static Set<String> networksOf(Device device, Site site) {
        Set<String> on = new LinkedHashSet<>();
        for (BlockPos cell : device.cells) on.addAll(site.networksAt(cell));
        if (device.part != null) return on;
        for (BlockPos cell : device.cells) {
            for (Direction side : Direction.values()) {
                String segment = site.segmentAt.get(cell.relative(side));
                if (segment != null) on.add(segment);
            }
        }
        return on;
    }

    // 网表示不了的进出口：加工处另算连接；已经挂在网上的设备，ME 面与转动面由网说明，不逐面列。
    private static List<ExchangePoint> ports(Device device, Site site) {
        boolean networked = device.cells.stream().anyMatch(cell -> !site.networksAt(cell).isEmpty());
        List<ExchangePoint> ports = new ArrayList<>();
        for (BlockPos cell : device.cells) {
            for (ExchangePoint point : site.pointsAt(cell)) {
                if (isProcessingSpot(point, cell)) continue;
                boolean byNetwork = point.medium() == ExchangePoint.Medium.ME
                        || point.medium() == ExchangePoint.Medium.ROTATION;
                if (networked && byNetwork) continue;
                ports.add(point);
            }
        }
        return ports;
    }

    // 连接：部件朝着的那一格、设备的加工处、两个设备贴着；同一条只写一次。
    private List<Connection> connections(List<Device> devices, Site site) {
        Map<BlockPos, Device> deviceAt = new LinkedHashMap<>();
        for (Device device : devices) {
            if (device.part == null) device.cells.forEach(cell -> deviceAt.put(cell, device));
        }
        Set<Connection> out = new LinkedHashSet<>();
        // 部件朝着的那一格：总线对着保险库就连到那个设备，对着原版箱子写出方块；对着空气的（终端面向玩家）不算连接。
        for (Device device : devices) {
            if (device.part == null) continue;
            BlockPos facing = device.cells.getFirst().relative(device.part.side());
            Device other = deviceAt.get(facing);
            if (other != null) {
                out.add(new Connection(device.id, other.id, null, null, ConnectionKind.FACING));
                continue;
            }
            Optional<BlockState> there = world.apply(facing);
            if (there.isPresent() && there.get().isAir()) continue;
            out.add(new Connection(device.id, null, facing, there.map(MachineAreaReport::blockId).orElse(null),
                    ConnectionKind.FACING));
        }
        // 加工处：压机正下方第二格这类；那一格是别的设备就连过去，不是就写出那一格现在是什么（空着也写，摆没摆东西是事实）。
        for (Device device : devices) {
            if (device.part != null) continue;
            for (BlockPos cell : device.cells) {
                for (ExchangePoint point : site.pointsAt(cell)) {
                    if (!isProcessingSpot(point, cell)) continue;
                    Device other = deviceAt.get(point.at());
                    if (other == device) continue;
                    out.add(other != null
                            ? new Connection(device.id, other.id, null, null, ConnectionKind.PROCESSING_SPOT)
                            : new Connection(device.id, null, point.at(),
                                    world.apply(point.at()).map(MachineAreaReport::blockId).orElse(null),
                                    ConnectionKind.PROCESSING_SPOT));
                }
            }
        }
        // 两个设备贴着：漏斗贴着保险库、置物台贴着传送带；不管是不是同一台、在不在同一张网上，每对只写一次。
        for (Device device : devices) {
            if (device.part != null) continue;
            for (BlockPos cell : device.cells) {
                for (Direction side : Direction.values()) {
                    Device other = deviceAt.get(cell.relative(side));
                    // 每对从编号小的那个写起，另一头再碰到时不重写。
                    if (other == null || other.order <= device.order) continue;
                    out.add(new Connection(device.id, other.id, null, null, ConnectionKind.TOUCHING));
                }
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Set<BlockPos>> cellsOf(List<Device> devices) {
        Map<String, Set<BlockPos>> cells = new LinkedHashMap<>();
        for (Device device : devices) cells.put(device.id, Set.copyOf(device.cells));
        return cells;
    }

    private Optional<NetworkReader> reader(String kind) {
        return readers.stream().filter(reader -> reader.kind().id().equals(kind)).findFirst();
    }

    /** 机器角色是线缆或传动的格只负责连接，合进网，不当设备。 */
    static boolean isConnector(MachineType type) {
        return type.role() == MachineRole.CABLE || type.role() == MachineRole.TRANSMISSION;
    }

    /** 不分面、又不在这一格自己身上的进出口是加工处（压机正下方第二格）；在自己身上的（合成器、装置）是说明。 */
    static boolean isProcessingSpot(ExchangePoint point, BlockPos cell) {
        return point.side() == null && !point.at().equals(cell);
    }

    /** 一组格按六向贴面连成块，每块按先低后高排好，块之间按最低那一格排。 */
    static List<List<BlockPos>> components(Collection<BlockPos> cells) {
        Set<BlockPos> left = new HashSet<>(cells);
        List<List<BlockPos>> out = new ArrayList<>();
        for (BlockPos seed : cells.stream().sorted(LOW_FIRST).toList()) {
            if (!left.remove(seed)) continue;
            List<BlockPos> piece = new ArrayList<>(List.of(seed));
            Deque<BlockPos> frontier = new ArrayDeque<>(List.of(seed));
            while (!frontier.isEmpty()) {
                BlockPos at = frontier.poll();
                for (Direction side : Direction.values()) {
                    BlockPos next = at.relative(side);
                    if (left.remove(next)) {
                        piece.add(next);
                        frontier.add(next);
                    }
                }
            }
            piece.sort(LOW_FIRST);
            out.add(piece);
        }
        return out;
    }

    private static String segmentId(List<BlockPos> segment) {
        BlockPos low = segment.getFirst();
        return ADJACENT + "@" + low.getX() + "," + low.getY() + "," + low.getZ();
    }

    private static BlockPos single(List<BlockPos> cells) {
        return cells.size() == 1 ? cells.getFirst() : null;
    }

    private static Box box(List<BlockPos> cells) {
        return cells.size() == 1 ? null : Box.of(cells);
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** 拼报告时用的一份现场：每格的机器类型、挂着的网、整张网的读数、连接件段，读数与进出口每格只读一次。 */
    private final class Site {
        final Map<BlockPos, MachineType> types;
        final Map<BlockPos, BlockState> claimed;
        final Map<BlockPos, Map<String, String>> memberships;
        final Set<BlockPos> archiveCells;
        final String archiveName;
        /** 网络编号 → 种类，按第一次见到的顺序。 */
        final Map<String, String> kindOf = new LinkedHashMap<>();
        final Map<String, NetworkSummary> summaries = new LinkedHashMap<>();
        /** 没有网络读取器的连接件按贴着连成的段，与每格在哪一段。 */
        final List<List<BlockPos>> segments;
        final Map<BlockPos, String> segmentAt = new LinkedHashMap<>();
        private final Map<BlockPos, StateView> states = new LinkedHashMap<>();
        private final Map<BlockPos, List<ExchangePoint>> points = new LinkedHashMap<>();

        Site(Map<BlockPos, MachineType> types, Map<BlockPos, BlockState> claimed,
                Map<BlockPos, Map<String, String>> memberships, Set<BlockPos> archiveCells, String archiveName) {
            this.types = types;
            this.claimed = claimed;
            this.memberships = memberships;
            this.archiveCells = archiveCells;
            this.archiveName = archiveName;
            for (BlockPos cell : types.keySet()) {
                memberships.getOrDefault(cell, Map.of()).forEach((kind, id) -> kindOf.putIfAbsent(id, kind));
            }
            // 整张网的读数每张只问一次：网那一条给它，设备读数里同名同值的项据此去掉。
            kindOf.forEach((id, kind) -> reader(kind).ifPresent(reader -> summaries.put(id, reader.summary(id))));
            segments = components(links().stream().filter(cell -> networksAt(cell).isEmpty()).toList());
            for (List<BlockPos> segment : segments) {
                String id = segmentId(segment);
                segment.forEach(cell -> segmentAt.put(cell, id));
            }
        }

        /** 范围里的连接件格，按先低后高。 */
        List<BlockPos> links() {
            return types.entrySet().stream().filter(entry -> isConnector(entry.getValue()))
                    .map(Map.Entry::getKey).sorted(LOW_FIRST).toList();
        }

        /** 这一格挂着的网络编号。 */
        Collection<String> networksAt(BlockPos cell) {
            return memberships.getOrDefault(cell, Map.of()).values();
        }

        /** 这一格的读数：去掉和所在网的整网读数同名同值的项（应力、容量这类 Create 抄到每个成员上的）。 */
        StateView state(BlockPos cell) {
            return states.computeIfAbsent(cell, key -> {
                MachineState state = types.get(key).state(key);
                Map<String, Object> network = new LinkedHashMap<>();
                for (String id : networksAt(key)) {
                    NetworkSummary summary = summaries.get(id);
                    if (summary != null) network.putAll(summary.readings());
                }
                Map<String, Object> own = new LinkedHashMap<>();
                state.readings().forEach((name, value) -> {
                    if (!Objects.equals(network.get(name), value)) own.put(name, value);
                });
                return StateView.of(state, own);
            });
        }

        /** 这一格的进出口（机器类型给的原样）。 */
        List<ExchangePoint> pointsAt(BlockPos cell) {
            return points.computeIfAbsent(cell, key -> types.get(key).exchangePoints(claimed.get(key), key));
        }

        /** 有格落在所看档案里时给档案名。 */
        String archiveOf(Collection<BlockPos> cells) {
            if (archiveName == null) return null;
            return cells.stream().anyMatch(archiveCells::contains) ? archiveName : null;
        }
    }

    /** 一个设备的草稿：方块设备是同种相邻的几格，部件是一格线缆上的一面；编号排好序后再给。 */
    private static final class Device {
        /** 方块设备的机器类型；部件为 null。 */
        final MachineType type;
        /** 方块设备占的格（先低后高）；部件只有它所在的那一格线缆。 */
        final List<BlockPos> cells;
        /** 部件；方块设备为 null。 */
        final MountedPart part;
        /** 排好序后的位置（从 0 起）与编号 d#。 */
        int order;
        String id;

        private Device(MachineType type, List<BlockPos> cells, MountedPart part) {
            this.type = type;
            this.cells = List.copyOf(cells);
            this.part = part;
        }

        static Device ofBlocks(MachineType type, List<BlockPos> cells) {
            return new Device(type, cells, null);
        }

        static Device ofPart(BlockPos host, MountedPart part) {
            return new Device(null, List.of(host), part);
        }
    }
}
