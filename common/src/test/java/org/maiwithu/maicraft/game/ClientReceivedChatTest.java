// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 聊天栏收到的组件读成收到的聊天：借角色名字代发的伪装聊天改记系统消息、去掉名字前缀；
 * 别人名下的伪装聊天照旧；死亡播报带点到的实体与翻译键。组件按原版聊天格式手搭，不起游戏。
 */
class ClientReceivedChatTest {
    private static final UUID SELF = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MOMO = UUID.fromString("5f2b9c1e-0d4a-4c1b-9e7f-1a2b3c4d5e6f");
    private static final UUID CREEPER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final String POSITION = "[name:\"winefox_momo\", x:12, y:64, z:-3, dim:minecraft:overworld]";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 公屏聊天这种消息类型（"&lt;名字&gt; 原话"），名字写成给定的样子。 */
    private static ChatType.Bound chatUnder(Component name) {
        ChatType chat = new ChatType(ChatTypeDecoration.withSender("chat.type.text"),
                ChatTypeDecoration.withSender("chat.type.text.narrate"));
        return new ChatType.Bound(Holder.direct(chat), name, Optional.empty());
    }

    @Test
    void aDisguisedLineUnderTheCharactersOwnNameIsASystemMessageWithoutThePrefix() {
        // 车万女仆死后用系统消息伪装成主人说的话发坐标：名字是角色自己的显示名，挂着角色的 UUID。
        ChatType.Bound under = chatUnder(EntityMentionsTest.named("Mai", EntityType.PLAYER, SELF));
        Component line = under.decorate(Component.literal(POSITION));

        ReceivedChat chat = ClientReceivedChat.player(line, null, null, null, under, SELF, "Mai").orElseThrow();

        assertEquals(ReceivedChat.Kind.SYSTEM, chat.kind());
        assertEquals("Mai", chat.shownAs());
        assertEquals(POSITION, chat.text(), "按聊天格式找回原话，不带名字前缀");
        assertNull(chat.sender());
        assertTrue(chat.mentions().isEmpty(), "角色自己的名字是聊天格式加的，不算消息里点到的");
        assertNull(chat.translationKey());
    }

    @Test
    void aNameWithoutHoverStillMatchesTheAccountNameAndAChangedLineLosesItsPrefix() {
        // 名字没挂悬停时按账号名比；整行被别的模组改过、找不回原话时，按"<名字> "前缀去掉。
        ChatType.Bound under = chatUnder(Component.literal("Mai"));
        Component changed = Component.literal("<Mai> " + POSITION);

        ReceivedChat chat = ClientReceivedChat.player(changed, null, null, null, under, SELF, "Mai").orElseThrow();

        assertEquals(ReceivedChat.Kind.SYSTEM, chat.kind());
        assertEquals(POSITION, chat.text());
    }

    @Test
    void aDisguisedLineUnderSomeoneElsesNameStaysAPlayerMessage() {
        // 控制台的 /say 也是伪装聊天：名字不是角色，照旧当玩家类消息记，正文照旧是整行。
        ChatType.Bound under = chatUnder(Component.literal("Server"));
        Component line = under.decorate(Component.literal("欢迎"));

        ReceivedChat chat = ClientReceivedChat.player(line, null, null, null, under, SELF, "Mai").orElseThrow();

        assertEquals(ReceivedChat.Kind.PLAYER, chat.kind());
        assertEquals("Server", chat.sender());
        assertNull(chat.shownAs());
        assertEquals(line.getString(), chat.text());
    }

    @Test
    void aDeathMessageCarriesWhoDiedWhoKilledAndItsTranslationKey() {
        Component death = Component.translatableWithFallback("death.attack.explosion.player", "%s 被 %s 炸死了",
                EntityMentionsTest.named("momo", EntityType.CAT, MOMO),
                EntityMentionsTest.named("苦力怕", EntityType.CREEPER, CREEPER));

        ReceivedChat chat = ClientReceivedChat.system(death, false).orElseThrow();

        // 正文按本机语言拼，测试环境不一定有语言文件：只看死者与凶手的名字都在。
        assertTrue(chat.text().contains("momo") && chat.text().contains("苦力怕"), chat.text());
        assertEquals("death.attack.explosion.player", chat.translationKey());
        assertEquals(List.of(MOMO, CREEPER), chat.mentions().stream().map(MentionedEntity::uuid).toList());
        assertNull(ClientReceivedChat.system(Component.literal("Alex 加入了游戏"), false).orElseThrow()
                .translationKey(), "整句不是翻译时不给翻译键");
    }
}
