// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * Mekanism 一种管网的读法（能量、流体、物品、化学品各一个）：管道之间连着的面是 Mekanism 画出来的，沿着它数出一片；
 * 机器算在连着它的那一片里（管道朝它的那一面连着机器）。网里存着多少电、多少流体（存量）客户端读不到，
 * 这里只给几段管道、连着几台机器，结果里写明读不到存量。
 * 能量、流体、物品三种会接线：线缆、管道、导管都是能直接放的方块，基础那一级；化学品只读不接。
 */
final class PipeNetworkReader implements NetworkReader {

    /** 一片最多数这么多格。 */
    static final int MAX_CELLS = 4096;

    private final String medium;
    private final NetworkKind kind;
    private final MekanismCompat compat;
    private final PlayerServices services;

    PipeNetworkReader(String medium, MekanismCompat compat, PlayerServices services) {
        this.medium = Objects.requireNonNull(medium, "medium");
        this.kind = kindOf(medium);
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    static NetworkKind kindOf(String medium) {
        return switch (medium) {
            case "energy" -> new NetworkKind("energy", "通用机械的能量网：通用线缆连起来的一组，给接着的机器送电");
            case "fluid" -> new NetworkKind("fluid", "通用机械的流体管网：机械管道连起来的一组");
            case "item" -> new NetworkKind("item", "通用机械的物品管网：物流传输管道连起来的一组");
            default -> new NetworkKind("chemical", "通用机械的化学品管网：加压管道连起来的一组（只读，不会接）");
        };
    }

    @Override public NetworkKind kind() {
        return kind;
    }

    @Override public Optional<String> membership(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return Optional.empty();
        Function<BlockPos, Optional<MekanismReads.Transmitter>> pipeAt = cell -> compat.transmitter(level, cell)
                .filter(pipe -> pipe.media().contains(medium));
        Optional<MekanismReads.Transmitter> here = pipeAt.apply(at);
        if (here.isPresent()) return Optional.of(componentOf(at, pipeAt).id());
        // 不是管道：看贴着它的管道有没有朝它这一面连着机器，有就算那一片里的。
        for (Direction side : Direction.values()) {
            BlockPos beside = at.relative(side);
            Optional<MekanismReads.Transmitter> pipe = pipeAt.apply(beside);
            if (pipe.isPresent() && pipe.get().machines().contains(side.getOpposite())) {
                return Optional.of(componentOf(beside, pipeAt).id());
            }
        }
        return Optional.empty();
    }

    @Override public NetworkSummary summary(String networkId) {
        ClientLevel level = level();
        BlockPos start = startOf(networkId);
        if (level == null || start == null) return NetworkSummary.unreadable(networkId, "读不到这张管网");
        Component found = componentOf(start, cell -> compat.transmitter(level, cell)
                .filter(pipe -> pipe.media().contains(medium)));
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("pipes", (long) found.cells().size());
        readings.put("machines", (long) found.machines());
        return new NetworkSummary(networkId, true, readings,
                "读不到存量：网里存着多少电、多少流体客户端看不到，这里只数得出几段管道、连着几台机器");
    }

    @Override public Optional<LineMaterials> lineMaterials() {
        String pipe = switch (medium) {
            case "energy" -> "mekanism:basic_universal_cable";
            case "fluid" -> "mekanism:basic_mechanical_pipe";
            case "item" -> "mekanism:basic_logistical_transporter";
            default -> null;
        };
        if (pipe == null) return Optional.empty();
        ResourceLocation id = ResourceLocation.tryParse(pipe);
        return id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id)
                .map(block -> new Pipes(block.defaultBlockState(), SideSettings.mediumOf(medium), medium));
    }

    /** 管道方块（去掉等级前缀）传的是哪种介质：同种的挨着就连成一张网，不分等级。 */
    private static final Map<String, String> PIPE_MEDIA = Map.of(
            "universal_cable", "energy", "mechanical_pipe", "fluid", "pressurized_tube", "chemical",
            "logistical_transporter", "item", "restrictive_transporter", "item", "diversion_transporter", "item",
            "thermodynamic_conductor", "heat");

    /**
     * 往 medium 这种网铺线时，挨着 blockId 这一格会不会被一起连上：同种介质的 Mekanism 管道会（不分等级连成一张网），
     * Mekanism 与发电机的机器会（能收能出这种介质的面会接上，宁可绕开）；别种介质的管道不会（线缆贴着物流管道走不相连），
     * 别的模组的方块不管。
     */
    static boolean joins(String blockId, String medium) {
        int colon = blockId.indexOf(':');
        if (colon < 0) return false;
        String namespace = blockId.substring(0, colon);
        if (!namespace.equals(MekanismCompat.MOD_ID) && !namespace.equals(MekanismMachineCatalog.GENERATORS_MOD_ID)) {
            return false;
        }
        String path = blockId.substring(colon + 1).replaceFirst("^(basic|advanced|elite|ultimate)_", "");
        String pipeMedium = PIPE_MEDIA.get(path);
        return pipeMedium == null || pipeMedium.equals(medium);
    }

    /** 一片管道：编号、格子、连着几台机器。 */
    record Component(String id, Set<BlockPos> cells, int machines) {
    }

    // 从这一格沿管道之间连着的面数一片，编号取最小的那一格（带上管网种类）。
    Component componentOf(BlockPos start, Function<BlockPos, Optional<MekanismReads.Transmitter>> pipeAt) {
        Set<BlockPos> seen = new LinkedHashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        seen.add(start.immutable());
        frontier.add(start.immutable());
        int machines = 0;
        while (!frontier.isEmpty() && seen.size() < MAX_CELLS) {
            BlockPos at = frontier.poll();
            Optional<MekanismReads.Transmitter> pipe = pipeAt.apply(at);
            if (pipe.isEmpty()) continue;
            machines += pipe.get().machines().size();
            for (Direction side : pipe.get().pipes()) {
                BlockPos next = at.relative(side).immutable();
                if (seen.add(next)) frontier.add(next);
            }
        }
        BlockPos least = seen.stream().min((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                : a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX()) : Integer.compare(a.getZ(), b.getZ()))
                .orElse(start);
        return new Component(medium + "@" + least.getX() + "," + least.getY() + "," + least.getZ(), seen, machines);
    }

    // 编号里写着最小的那一格：从它数回整片。
    private BlockPos startOf(String networkId) {
        int at = networkId.indexOf('@');
        if (at < 0) return null;
        String[] parts = networkId.substring(at + 1).split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException broken) {
            return null;
        }
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }

    /** 能直接放的管道方块：不分方向、六面都接；挨着会被一起连上的（同种管道、Mekanism 的机器）不在那一格放。 */
    private record Pipes(BlockState pipe, ExchangePoint.Medium medium, String mediumName) implements LineMaterials {

        @Override public BlockState straight(Direction.Axis axis) {
            return pipe;
        }

        @Override public Optional<BlockState> turn(Direction.Axis in, Direction.Axis out) {
            return Optional.of(pipe);
        }

        @Override public boolean joins(BlockState neighbor) {
            return !neighbor.is(Blocks.AIR)
                    && PipeNetworkReader.joins(BuiltInRegistries.BLOCK.getKey(neighbor.getBlock()).toString(), mediumName);
        }

        @Override public Set<Direction> connectedFaces(BlockState piece) {
            return EnumSet.allOf(Direction.class);
        }

        @Override public Optional<Span> span() {
            return Optional.empty();
        }
    }
}
