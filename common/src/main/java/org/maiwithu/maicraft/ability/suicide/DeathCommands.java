// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.suicide;

import java.util.List;
import java.util.Optional;

/**
 * 用命令死的接缝：服务器给了角色哪几条命令、把命令发出去、发完服务器回了什么。
 *
 * <p>服务器只把角色有权限用的命令发给客户端，所以命令树里有就是能用。插件给的 {@code /suicide} 先用；
 * 没有就看原版 {@code /kill}，有它说明角色有权限，发 {@code /kill @s}；两条都没有，这种死法就没有。
 * 生产实现读客户端收到的命令树、经聊天输入发命令，测试用替身。
 */
interface DeathCommands {

    /** 插件给的寻死命令（不带斜杠）。 */
    String SUICIDE = "suicide";
    /** 原版的杀死命令：有它说明角色有权限，只杀自己。 */
    String KILL_SELF = "kill @s";

    /** 什么命令都没有：离线场景和没接上命令树时用。 */
    DeathCommands NONE = new DeathCommands() {
        @Override public boolean offered(String root) {
            return false;
        }

        @Override public long send(String command) {
            return -1;
        }

        @Override public List<String> repliesSince(long mark) {
            return List.of();
        }
    };

    /** 客户端收到的命令树里有没有这条根命令（小写、不带斜杠）。 */
    boolean offered(String root);

    /** 发一条游戏命令（不带斜杠）：发前记下聊天栏的记号并返回它；没发出去（没连上）为 -1。 */
    long send(String command);

    /** 记号之后聊天栏新出现的话，按先后：发完命令后服务器回了什么。 */
    List<String> repliesSince(long mark);

    /** 这次用哪条命令死：有 /suicide 用它，没有而有 /kill 用 /kill @s；两条都没有为空。 */
    static Optional<String> pick(DeathCommands commands) {
        if (commands.offered(SUICIDE)) return Optional.of(SUICIDE);
        if (commands.offered("kill")) return Optional.of(KILL_SELF);
        return Optional.empty();
    }
}
