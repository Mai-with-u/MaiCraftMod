// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 收到的聊天：别人说的话照实收下，自己的回显、动作栏提示、空白消息不收，太长的截断并标出来；
 * 借角色名字代发的消息按系统消息记。
 */
class ReceivedChatTest {
    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @Test
    void someoneElseSpeakingIsKeptWithTheirNameAndId() {
        ReceivedChat chat = ReceivedChat.fromPlayer("  麦麦你在干嘛  ", STEVE, "Steve", false, SELF).orElseThrow();

        assertEquals(ReceivedChat.Kind.PLAYER, chat.kind());
        assertEquals("Steve", chat.sender());
        assertEquals(STEVE, chat.senderId());
        assertEquals("麦麦你在干嘛", chat.text());
        assertFalse(chat.isPrivate());
        assertFalse(chat.truncated());
    }

    @Test
    void theCharactersOwnWordsAreNotReceivedChat() {
        // 自己说话的回显与自己发出的私聊，发言人都是自己。
        assertTrue(ReceivedChat.fromPlayer("我来了", SELF, "Mai", false, SELF).isEmpty());
        assertTrue(ReceivedChat.fromPlayer("悄悄话", SELF, "Mai", true, SELF).isEmpty());
    }

    @Test
    void unsignedMessagesWithoutAnIdAreStillKept() {
        // 命令方块的 /say 这类没有签名的消息：没有 UUID，名字可能也没有，照样收下，不当成自己的话。
        ReceivedChat chat = ReceivedChat.fromPlayer("[Server] 欢迎", null, " ", false, SELF).orElseThrow();

        assertNull(chat.senderId());
        assertNull(chat.sender());
    }

    @Test
    void actionBarAndBlankMessagesAreNotChat() {
        assertTrue(ReceivedChat.fromServer("床太远了", true).isEmpty());
        assertTrue(ReceivedChat.fromServer("   ", false).isEmpty());
        assertTrue(ReceivedChat.fromPlayer("", STEVE, "Steve", false, SELF).isEmpty());

        ReceivedChat joined = ReceivedChat.fromServer("Alex 加入了游戏", false).orElseThrow();
        assertEquals(ReceivedChat.Kind.SYSTEM, joined.kind());
        assertNull(joined.sender());
    }

    @Test
    void aMessageShownUnderTheCharactersOwnNameIsASystemMessage() {
        // 车万女仆死后借主人的名字发一行坐标给主人：没有发言人 UUID，聊天格式里写的是角色自己。
        assertTrue(ReceivedChat.isUnderOwnName(null, "Mai", null, SELF, "Mai"), "名字和账号名一样");
        assertTrue(ReceivedChat.isUnderOwnName(null, "[红队] Mai", SELF, SELF, "Mai"), "名字点着的就是角色");

        ReceivedChat chat = ReceivedChat.underOwnName(
                "<Mai> [name:\"winefox_momo\", x:12, y:64, z:-3, dim:minecraft:overworld]", "Mai").orElseThrow();

        assertEquals(ReceivedChat.Kind.SYSTEM, chat.kind());
        assertEquals("Mai", chat.shownAs());
        assertEquals("[name:\"winefox_momo\", x:12, y:64, z:-3, dim:minecraft:overworld]", chat.text(),
                "聊天格式加的名字前缀去掉");
        assertNull(chat.sender());
        assertNull(chat.senderId());
    }

    @Test
    void messagesShownUnderSomeoneElsesNameStayAsTheyWere() {
        // 命令方块、控制台的 /say 名字不是角色；同名但点着别的实体的不是角色；有发言人 UUID 的由 UUID 说了算。
        assertFalse(ReceivedChat.isUnderOwnName(null, "Server", null, SELF, "Mai"));
        assertFalse(ReceivedChat.isUnderOwnName(null, "Mai", STEVE, SELF, "Mai"));
        assertFalse(ReceivedChat.isUnderOwnName(STEVE, "Mai", null, SELF, "Mai"));
        assertFalse(ReceivedChat.isUnderOwnName(null, "Mai", null, null, null), "不在世界里时不认");
        // 正文本来就没有前缀时原样留着；空白的不算收到的聊天。
        assertEquals("已经到家", ReceivedChat.underOwnName("已经到家", "Mai").orElseThrow().text());
        assertTrue(ReceivedChat.underOwnName("<Mai> ", "Mai").isEmpty());
    }

    @Test
    void longTextIsCutAndMarkedWithoutSplittingACharacter() {
        String emoji = "😀";
        // 第 MAX_TEXT 个字落在一个表情的前半个上：往前退一位，不留下半个表情。
        String text = "a".repeat(ReceivedChat.MAX_TEXT - 1) + emoji + "后面还有";
        ReceivedChat chat = ReceivedChat.fromServer(text, false).orElseThrow();

        assertTrue(chat.truncated());
        assertEquals(ReceivedChat.MAX_TEXT - 1, chat.text().length());
        assertFalse(Character.isHighSurrogate(chat.text().charAt(chat.text().length() - 1)));
        assertFalse(ReceivedChat.fromServer("a".repeat(ReceivedChat.MAX_TEXT), false).orElseThrow().truncated());
    }
}
