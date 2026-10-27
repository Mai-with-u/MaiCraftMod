// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.maiwithu.maicraft.network.MaiCraftPayload;
import org.maiwithu.maicraft.network.ProtocolJson;
import org.maiwithu.maicraft.network.ProtocolReplies;
import org.maiwithu.maicraft.network.ServerFeature;
import org.maiwithu.maicraft.network.ServerProtocolDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端链路：把收到的客户端信封交给按连接隔离的协议引擎，应答通过加载器发回。
 * 绝不使用客户端自己报的身份，只认当前连接上的玩家对象；普通玩家没有会话，照常游玩。
 * 只有角色账号能建会话：别的玩家发来信封，只回一句握手被拒，不留协议引擎，确认通道也不给他推东西。
 */
public final class ServerLinkNetwork {
    private static final Logger LOG = LoggerFactory.getLogger(ServerLinkNetwork.class);

    private final ServerOperationRegistry operations;
    private final Predicate<ServerPlayer> admits;
    private final Map<ServerGamePacketListenerImpl, ServerProtocolDispatcher> connections =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /**
     * 来握过手、因为不是角色账号被拒的连接：日志同一条连接只记一次；这些玩家装着 MaiCraft，
     * 服主用 /mai 把他加进名单时就给他推一条"准入了"，客户端当场重新握手。
     */
    private final Set<ServerGamePacketListenerImpl> refused =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * @param operations 服务端操作注册表
     * @param admits     这个玩家是不是角色账号；不是的不给会话
     */
    public ServerLinkNetwork(ServerOperationRegistry operations, Predicate<ServerPlayer> admits) {
        this.operations = operations;
        this.admits = admits;
    }

    public void receive(ServerPlayer player, MaiCraftPayload payload, Consumer<JsonObject> reply) {
        JsonObject envelope;
        try {
            if (payload.json().length() > ProtocolJson.MAX_REQUEST_CHARS)
                throw new IllegalArgumentException("Request too large");
            envelope = payload.envelope();
        } catch (RuntimeException malformed) {
            reply.accept(ProtocolReplies.reject(new JsonObject(), "invalid_envelope", "Malformed envelope",
                    Integer.toUnsignedLong(player.serverLevel().getServer().getTickCount())));
            return;
        }
        receive(player, envelope, reply);
    }

    public void receive(ServerPlayer player, JsonObject envelope, Consumer<JsonObject> reply) {
        MinecraftServer server = player.serverLevel().getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Network dispatch requires the server game thread");
        if (player.hasDisconnected() || player.connection.player != player) return;
        boolean admitted = admits.test(player);
        // 不是角色账号：拿掉这条连接上已有的协议引擎（服主刚把他从名单里拿掉时旧会话作废），
        // 用一个用完就扔的引擎回一句握手被拒；连接表里不留他，确认通道也就不会给他推放置、破坏的确认。
        if (!admitted) {
            connections.remove(player.connection);
            if (refused.add(player.connection)) {
                LOG.info("玩家 {} 想用 MaiCraft 服务端链路，但不是角色账号；要让这个号当角色，用 /mai {} 把它加进名单",
                        player.getGameProfile().getName(), player.getGameProfile().getName());
            }
        }
        var dispatcher = admitted
                ? connections.computeIfAbsent(player.connection, ignored -> new ServerProtocolDispatcher())
                : new ServerProtocolDispatcher();
        reply.accept(dispatcher.receive(envelope, new ServerProtocolDispatcher.Peer() {
            @Override public String dimension() { return player.serverLevel().dimension().location().toString(); }
            @Override public long tick() { return Integer.toUnsignedLong(server.getTickCount()); }
            @Override public boolean admitted() { return admitted; }
            @Override public boolean mayMutate() { return player.isAlive() && !player.isSpectator(); }
            @Override public List<ServerFeature> features() { return operations.features(player); }
            @Override public JsonObject execute(String operationId, JsonObject body) {
                return operations.execute(player, operationId, body);
            }
        }));
    }

    /** 该玩家当前是否有活跃的 MaiCraft 会话；交互确认只发给这些玩家。 */
    public boolean hasSession(ServerPlayer player) {
        return connections.containsKey(player.connection);
    }

    /**
     * 服主改了角色账号名单（/mai）之后，按这个玩家此刻准不准入处理他的连接：
     * 刚准入、之前来握过手被拒的，推一条"准入了"，他的客户端当场重新握手；
     * 刚撤掉、正有会话的，当场关掉他的会话并推一条"不准入了"。没装 MaiCraft 的玩家什么都不推。
     *
     * @param push 给这个玩家发信封的加载器通道
     */
    public void admissionChanged(ServerPlayer player, ServerConfirmations.Push push) {
        if (player.hasDisconnected()) return;
        if (admits.test(player)) {
            if (refused.remove(player.connection)) push.send(player, ServerProtocolDispatcher.admissionNotice(true));
        } else if (connections.remove(player.connection) != null) {
            // 撤掉的也记进被拒的连接：服主回头再把他加回来时，照样推一条"准入了"让他当场重新握手。
            refused.add(player.connection);
            push.send(player, ServerProtocolDispatcher.admissionNotice(false));
        }
    }

    public void worldChanged(ServerPlayer player) {
        var dispatcher = connections.get(player.connection);
        if (dispatcher != null) dispatcher.invalidateWorld();
    }

    public void disconnected(ServerPlayer player) {
        connections.remove(player.connection);
        refused.remove(player.connection);
    }

    public void stopped(MinecraftServer server) {
        List<ServerGamePacketListenerImpl> remaining;
        synchronized (connections) {
            remaining = connections.keySet().stream()
                    .filter(connection -> connection.player.serverLevel().getServer() == server).toList();
        }
        remaining.forEach(connections::remove);
        // 停服时连接都没了：记过日志的连接一并清掉，单人游戏下次开世界重新记。
        refused.clear();
    }
}
