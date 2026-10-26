// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * ME 网络的读法：哪几格连成一张网在客户端看（线缆画出来的连接、设备贴着设备），网络编号取那一片最小的一格；
 * 那张网怎样（控制器、频道、能量、存储总量、合成 CPU）只有服务端知道，经只读操作 ae2.network 问，
 * 问出去要过几刻才有回答。问、收、重问与数据时刻交给 {@link ServerNetworkReadings}：回答收下后一小段时间内直接复用，
 * 过了就重问，重问期间的读数写明是几秒前的数据，问话没收回时 summaryPending 为真，交付方等它到账再交。
 * 客户端看得见的也一起给：这一片里有没有控制器方块、控制器亮没亮。服务器没装 MaiCraft 的 AE2 读数时如实说读不到。
 */
final class MeNetworkReader implements NetworkReader {

    static final NetworkKind KIND = new NetworkKind("me",
            "应用能源2 的 ME 网络：线缆把控制器、驱动器、接口、终端连起来的一张网，共用频道、能量与存储");
    /** 连通的片记多久（毫秒）：一次查看里反复问同一片不重算，过了重新数。 */
    private static final long COMPONENT_MILLIS = 1_000;

    private final Ae2Compat compat;
    private final PlayerServices services;
    /** 时刻从外面给：离线测试拨钟用，生产就是系统时钟；连通的片按它过期。 */
    private final LongSupplier now;
    private final Map<BlockPos, MeComponents.Component> components = new LinkedHashMap<>();
    private long componentsAt;
    /** 每张网的代表格：问服务端时报这一格。 */
    private final Map<String, BlockPos> representative = new LinkedHashMap<>();
    /** 向服务端要整网读数：问、收、没答上重问、标明数据时刻。 */
    private final ServerNetworkReadings server;

    MeNetworkReader(Ae2Compat compat, PlayerServices services) {
        this(compat, services, System::currentTimeMillis);
    }

    MeNetworkReader(Ae2Compat compat, PlayerServices services, LongSupplier now) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.now = Objects.requireNonNull(now, "now");
        this.server = new ServerNetworkReadings(Ae2Compat.NETWORK_OPERATION, services.serverReads(), now);
    }

    @Override public NetworkKind kind() {
        return KIND;
    }

    @Override public Optional<String> membership(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return Optional.empty();
        long now = this.now.getAsLong();
        if (now - componentsAt > COMPONENT_MILLIS) {
            components.clear();
            componentsAt = now;
        }
        MeComponents.Component known = components.get(at);
        if (known == null) {
            Optional<MeComponents.Component> found = MeComponents.of(at, cell -> compat.gridCell(level, cell));
            if (found.isEmpty()) return Optional.empty();
            known = found.get();
            for (BlockPos cell : known.cells()) components.put(cell, known);
            representative.put(known.id(), at.immutable());
        }
        return Optional.of(known.id());
    }

    @Override public NetworkSummary summary(String networkId) {
        BlockPos at = representative.get(networkId);
        if (at == null) return NetworkSummary.unreadable(networkId, "还没看到过这张网上的设备，先看一眼");
        Map<String, Object> seen = clientFacts(networkId);
        if (!server.available()) {
            return new NetworkSummary(networkId, false, seen,
                    "读不到：服务器没装 MaiCraft 的 AE2 读数（或所有者关了），只给看得见的");
        }
        ServerNetworkReadings.Latest latest = server.latest(networkId, at);
        if (latest.reply() == null) {
            return new NetworkSummary(networkId, false, seen, "正在向服务端要这张网的读数，稍后再看");
        }
        NetworkSummary served = MeSummaries.of(networkId, latest.reply(), seen);
        // 这一答不是当拍的：读数照给，但写明数据时刻，等这一问到账再读就是新值。
        return latest.staleness().isEmpty() ? served : new NetworkSummary(networkId, served.readable(),
                served.readings(), served.note() + latest.staleness());
    }

    @Override public boolean summaryPending(String networkId) {
        return server.pending(networkId);
    }

    @Override public Optional<LineMaterials> lineMaterials() {
        return Optional.of(new MeLines());
    }

    // 看得见的事实：这一片里有几块控制器、亮没亮。
    private Map<String, Object> clientFacts(String networkId) {
        ClientLevel level = level();
        Map<String, Object> facts = new LinkedHashMap<>();
        if (level == null) return facts;
        long controllers = 0;
        long online = 0;
        for (Map.Entry<BlockPos, MeComponents.Component> entry : components.entrySet()) {
            if (!entry.getValue().id().equals(networkId)) continue;
            Optional<String> state = compat.gridCell(level, entry.getKey()).flatMap(Ae2Grid.GridCell::controllerState);
            if (state.isEmpty()) continue;
            controllers++;
            if (state.get().equals("online")) online++;
        }
        facts.put("controller_blocks", controllers);
        facts.put("controller_blocks_online", online);
        return facts;
    }

    /** 登记代表格：实机里 membership() 第一次认出这张网的设备时记；离线测试也经它登记。 */
    void seen(String networkId, BlockPos at) {
        representative.put(networkId, at.immutable());
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }

    /** 把服务端的回答翻成网络汇总，纯函数。 */
    static final class MeSummaries {

        private MeSummaries() {
        }

        static NetworkSummary of(String networkId, JsonObject body, Map<String, Object> seen) {
            Map<String, Object> readings = new LinkedHashMap<>(seen);
            if (body.has("failed")) {
                return new NetworkSummary(networkId, false, readings, "服务端没答上：" + body.get("failed").getAsString());
            }
            if (!body.has("found") || !body.get("found").getAsBoolean()) {
                return new NetworkSummary(networkId, false, readings, "服务端说这一格不在任何 ME 网上");
            }
            for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                if (entry.getKey().equals("found") || !entry.getValue().isJsonPrimitive()) continue;
                var value = entry.getValue().getAsJsonPrimitive();
                readings.put(entry.getKey(), value.isBoolean() ? (Object) value.getAsBoolean()
                        : value.isNumber() ? (Object) value.getAsDouble() : value.getAsString());
            }
            return new NetworkSummary(networkId, true, readings, note(body));
        }

        // 一句话点出要紧的：没控制器、频道超了、没电。
        private static String note(JsonObject body) {
            StringBuilder line = new StringBuilder();
            if (body.has("controller") && body.get("controller").getAsString().equals("none")) {
                line.append("这张网没有控制器：最多 ").append(body.has("ad_hoc_limit")
                        ? body.get("ad_hoc_limit").getAsInt() : 8).append(" 个频道");
            }
            if (body.has("ad_hoc_problem") && body.get("ad_hoc_problem").getAsString().equals("too_many_channels")) {
                line.append(line.isEmpty() ? "" : "；").append("频道不够：设备超过了没有控制器时的上限，全网停摆");
            }
            if (body.has("powered") && !body.get("powered").getAsBoolean()) {
                line.append(line.isEmpty() ? "" : "；").append("网络没电");
            }
            return line.toString();
        }
    }
}
