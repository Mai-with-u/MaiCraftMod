// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.server.MinecraftServer;

/**
 * 命令树读端，两种读法：
 * 内置服务器的命令树里一条根命令普通玩家（零级权限）用不用得了——连远程服务器拿不到，答"不知道"（false），
 * 命令树正在重载等读不出的时候也一样，不因此把普通玩家命令拦下；
 * 客户端收到的命令树里有没有一条根命令——服务器只发角色有权限用的，有就是能用，远程服务器也读得到。
 * 只读，不执行命令、不改权限。
 */
public final class ClientCommandTree {

    /**
     * 服务器发给这个角色的命令树里有没有这条根命令（不带 /，小写）：有就是角色能用。
     * 还没连上、命令树还没到时答没有。寻死先看有没有 /suicide、/kill 就用它。
     */
    public boolean offered(String commandName) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft == null ? null : minecraft.getConnection();
        return connection != null && connection.getCommands().getRoot().getChild(commandName) != null;
    }

    /** 这条根命令（不带 /，小写）普通玩家用不了：只有内置服务器在、命令树里有它、零级权限用不了才算。 */
    public boolean adminOnly(String commandName) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            MinecraftServer server = minecraft == null ? null : minecraft.getSingleplayerServer();
            if (server == null) return false;
            var node = server.getCommands().getDispatcher().getRoot().getChild(commandName);
            return node != null && !node.canUse(server.createCommandSourceStack().withPermission(0));
        } catch (RuntimeException unavailable) {
            // 命令树正在重载、服务器正在关：读不出就不算管理员命令，交给名单兜底。
            return false;
        }
    }
}
