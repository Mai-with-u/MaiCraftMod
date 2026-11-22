// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.ReceivedChat;
import org.maiwithu.maicraft.game.interaction.OverlayMessages;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;

/**
 * 游戏拒绝读端的聊天过滤测试：成就与进度达成播报、玩家进出与死亡播报不属于拒绝交互，
 * 真正的模组或插件拒绝提示照常保留。
 */
class ClientGameRefusalsTest {

    @Test
    void 成就与进度播报不归类为游戏拒绝() {
        ChatEventLog chat = new ChatEventLog();
        ClientGameRefusals refusals = new ClientGameRefusals(new OverlayMessages(), List::of, () -> 0L, chat);
        long mark = refusals.chatMark();

        // 模拟点击神龛或触发进度后服务端发来的三种原版进度播报。
        chat.append(system("玩家甲取得了进度[初次见面]", "chat.type.advancement.task"));
        chat.append(system("玩家甲达成了目标[我又复活啦!]", "chat.type.advancement.goal"));
        chat.append(system("玩家甲完成了挑战[女仆大师]", "chat.type.advancement.challenge"));

        assertEquals(3, refusals.systemMessagesSince(mark).size(), "原始系统消息列表照常保留");
        assertTrue(refusals.refusalMessagesSince(mark).isEmpty(), "进度播报不该当成拒绝消息");
    }

    @Test
    void 真正拒绝提示保留而公共播报被过滤() {
        ChatEventLog chat = new ChatEventLog();
        ClientGameRefusals refusals = new ClientGameRefusals(new OverlayMessages(), List::of, () -> 0L, chat);
        long mark = refusals.chatMark();

        chat.append(system("Alex 加入了游戏", "multiplayer.player.joined"));
        chat.append(system("玩家甲达成了目标[我又复活啦!]", "chat.type.advancement.goal"));
        chat.append(system("这不是你的女仆", null));
        chat.append(system("箱子已上锁", "container.isLocked"));

        assertEquals(List.of("这不是你的女仆", "箱子已上锁"), refusals.refusalMessagesSince(mark));
        assertTrue(ClientGameRefusals.isNonRefusalBroadcast(system("x", "chat.type.advancement.task")));
        assertTrue(ClientGameRefusals.isNonRefusalBroadcast(system("x", "chat.type.advancement.goal")));
        assertTrue(ClientGameRefusals.isNonRefusalBroadcast(system("x", "chat.type.advancement.challenge")));
        assertFalse(ClientGameRefusals.isNonRefusalBroadcast(system("箱子已上锁", "container.isLocked")));
        assertFalse(ClientGameRefusals.isNonRefusalBroadcast(system("领地受保护，禁止交互", null)));
    }

    private static ReceivedChat system(String text, String translationKey) {
        ReceivedChat base = ReceivedChat.fromServer(text, false).orElseThrow();
        return translationKey == null ? base : base.with(List.of(), translationKey);
    }
}
