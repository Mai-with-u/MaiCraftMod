// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 当前连接的玩家列表（Tab 列表）：按名字查在线玩家的编号、按编号问在不在线。只在客户端线程调用。
 */
public final class OnlinePlayers {

    private OnlinePlayers() {}

    /**
     * 名字对应的玩家编号（UUID 字符串）。
     * 只认此刻在线、已同步到玩家列表里的人；不在线或还没同步时为空，不猜也不编。
     */
    public static Optional<String> idByName(Minecraft minecraft, String name) {
        ClientPacketListener connection = minecraft.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(name);
        return info == null ? Optional.empty() : Optional.of(info.getProfile().getId().toString());
    }

    /** 玩家列表（Tab）里在线的一个人：名字与编号。 */
    public record Online(String name, String uuid) {}

    /**
     * 玩家列表（Tab）里在线的每个人，角色自己不算（它是看的那一方，不是被看的）。
     * 还没连上服务器时给空：列表还没同步，谁在线都说不准。
     */
    public static List<Online> online(PlayerContext context) {
        ClientPacketListener connection = context.connection();
        LocalPlayer self = context.localPlayer();
        if (connection == null || self == null) {
            return List.of();
        }
        List<Online> online = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile().getId().equals(self.getUUID())) {
                continue;
            }
            online.add(new Online(info.getProfile().getName(), info.getProfile().getId().toString()));
        }
        return List.copyOf(online);
    }

    /**
     * 这个编号的玩家此刻在玩家列表里叫什么：被别人的方块封住、挖开后写明是谁放的时用。
     * 不在线或还没同步时为空，由调用方改写编号，不猜名字。
     */
    public static Optional<String> nameById(PlayerContext context, UUID id) {
        ClientPacketListener connection = context.connection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        return info == null ? Optional.empty() : Optional.of(info.getProfile().getName());
    }

    /**
     * 回报里写一个玩家（会被直播念出来）：在线写名字，不在线、编号读不出来、没有当刻的角色上下文都写"一位不在线的玩家"，
     * 不吐编号。动了别人放在角色家里的东西、被别人的方块挡住时说是谁放的用它。
     */
    public static String nameOrSomeoneOffline(PlayerContext context, String playerId) {
        try {
            return context == null ? SOMEONE_OFFLINE
                    : nameById(context, UUID.fromString(playerId)).orElse(SOMEONE_OFFLINE);
        } catch (IllegalArgumentException notUuid) {
            return SOMEONE_OFFLINE;
        }
    }

    /** 不在线的玩家在回报里的说法。 */
    public static final String SOMEONE_OFFLINE = "一位不在线的玩家";

    /**
     * 从看的那个玩家（角色）这边写一个玩家：玩家列表里同步了的写名字，不在线、还没同步的写编号（UUID），不猜名字。
     * 观察里写别人驯服的生物是谁驯服的时用。
     */
    public static String nameOrId(Player viewer, UUID id) {
        ClientPacketListener connection = viewer instanceof LocalPlayer local ? local.connection : null;
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        return info == null ? id.toString() : info.getProfile().getName();
    }

    /**
     * 说明里写一个玩家：在线写名字，不在线写编号；编号读不出来、没有当刻的角色上下文，都照原样写。
     * 被别人的方块封住挖开时写是谁放的、跳过别人的箱子时写记在谁名下，都用它。
     */
    public static String nameOrId(PlayerContext context, String playerId) {
        try {
            return context == null ? playerId : nameById(context, UUID.fromString(playerId)).orElse(playerId);
        } catch (IllegalArgumentException notUuid) {
            return playerId;
        }
    }

    /**
     * 这个编号的玩家此刻在不在玩家列表里：看不见他不等于他下线了，走出能看见的范围时他照样在列表里。
     * 还没连上服务器时答不在。
     */
    public static boolean online(PlayerContext context, UUID id) {
        ClientPacketListener connection = context.connection();
        return connection != null && connection.getPlayerInfo(id) != null;
    }
}
