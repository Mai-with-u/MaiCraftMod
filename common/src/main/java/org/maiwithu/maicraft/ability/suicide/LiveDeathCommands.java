// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.world.ClientCommandTree;

/**
 * 用命令死的生产实现：读客户端收到的命令树，经聊天输入发命令（和人在聊天框里敲一样，服务器按角色的权限裁决），
 * 发完从聊天栏读服务器回的话。
 */
final class LiveDeathCommands implements DeathCommands {

    private final ClientCommandTree tree;
    private final ChatChannel chat;

    LiveDeathCommands(ClientCommandTree tree, ChatChannel chat) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override public boolean offered(String root) {
        return tree.offered(root);
    }

    // 先记下聊天栏的记号再发：之后冒出来的才是这条命令的回话。
    @Override public long send(String command) {
        long mark = chat.mark();
        return chat.send("/" + command) ? mark : -1;
    }

    @Override public List<String> repliesSince(long mark) {
        return chat.shownSince(mark);
    }
}
