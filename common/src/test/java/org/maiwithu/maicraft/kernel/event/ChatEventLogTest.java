// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.ReceivedChat;

/**
 * 交互前后聊天栏收到了什么：只给记号之后的服务端系统消息，玩家说的话不算，换了世界记号作废。
 */
class ChatEventLogTest {

    @Test
    void 记号之后的系统消息按先后给出_玩家聊天不算() {
        ChatEventLog log = new ChatEventLog();
        log.append(ReceivedChat.fromServer("出手前的旧话", false).orElseThrow());
        long mark = log.latestCursor();
        log.append(ReceivedChat.fromPlayer("路过的人说话", UUID.randomUUID(), "路人", false, UUID.randomUUID())
                .orElseThrow());
        log.append(ReceivedChat.fromServer("这不是你的女仆", false).orElseThrow());
        log.append(ReceivedChat.fromServer("某某加入了游戏", false).orElseThrow());
        assertEquals(List.of("这不是你的女仆", "某某加入了游戏"), log.systemTextsAfter(mark));
    }

    @Test
    void 换了世界_旧记号作废() {
        ChatEventLog log = new ChatEventLog();
        log.append(ReceivedChat.fromServer("一", false).orElseThrow());
        log.append(ReceivedChat.fromServer("二", false).orElseThrow());
        long mark = log.latestCursor();
        log.restart();
        log.append(ReceivedChat.fromServer("新世界的话", false).orElseThrow());
        assertEquals(List.of(), log.systemTextsAfter(mark));
    }
}
