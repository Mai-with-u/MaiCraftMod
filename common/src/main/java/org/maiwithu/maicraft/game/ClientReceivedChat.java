// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * 加载器转来的聊天事件读成收到的聊天：认出发言人、私聊、角色自己的回显，以及借角色名字代发的消息；
 * 再从消息组件里读出点到的实体与翻译键。
 *
 * <p>两个加载器给的东西不一样多：有签名的玩家消息带原话与发言人 UUID；没有签名的（命令方块的 /say、
 * 模组用系统消息伪装成的聊天）只有聊天栏显示的整行和消息类型。名字没给时按 UUID 从当前连接的玩家列表里查，
 * 再不行用消息类型里的名字。只在客户端线程调用。
 */
public final class ClientReceivedChat {

    private ClientReceivedChat() {}

    /**
     * 玩家说的话（公屏或私聊），或借角色名字代发的消息。
     *
     * @param line     聊天栏显示的整行，带发言人名字
     * @param content  不带名字的原话；没有签名的消息拿不到，为 null
     * @param senderId 发言人 UUID；没有签名的消息拿不到，为 null
     * @param sender   发言人名字；加载器没给时为 null
     * @param chatType 消息类型：公屏、私聊……
     */
    public static Optional<ReceivedChat> player(Minecraft minecraft, Component line, Component content,
                                                UUID senderId, String sender, ChatType.Bound chatType) {
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        String selfName = minecraft.player == null ? null : minecraft.player.getGameProfile().getName();
        String name = sender != null ? sender : nameOf(minecraft, senderId);
        return player(line, content, senderId, name, chatType, self, selfName);
    }

    /**
     * 同上，发言人名字与角色是谁已经读好：离线测试直接喂组件。
     *
     * @param sender   发言人名字；加载器没给、按 UUID 也查不到时为 null
     * @param self     角色自己的 UUID；不在世界里时为 null
     * @param selfName 角色的账号名；不在世界里时为 null
     */
    static Optional<ReceivedChat> player(Component line, Component content, UUID senderId, String sender,
                                         ChatType.Bound chatType, UUID self, String selfName) {
        // 没有原话的消息只有套好格式的整行：按消息类型的格式找回原话那一段，点到的实体、翻译键都从原话里读。
        // 找不回时点到的实体从整行读；整行的翻译键只是聊天格式（chat.type.text），不当消息的翻译键。
        Component words = content != null ? content : contentOf(line, chatType).orElse(null);
        Component read = words != null ? words : line;
        List<MentionedEntity> mentions = EntityMentions.in(read);
        String key = words == null ? null : translationKey(words);
        if (senderId == null && chatType != null) {
            // 没有发言人 UUID、聊天格式里写的却是角色自己：服务器或模组借角色名字发给角色看的，按系统消息记。
            Component shown = chatType.name();
            UUID shownId = EntityMentions.named(shown).map(MentionedEntity::uuid).orElse(null);
            if (ReceivedChat.isUnderOwnName(senderId, shown.getString(), shownId, self, selfName)) {
                return ReceivedChat.underOwnName(read.getString(), shown.getString())
                        .map(chat -> chat.with(mentions, key));
            }
        }
        String name = sender;
        if (name == null && chatType != null) {
            // 没有签名的消息只剩消息类型里的名字（服务器给它起的显示名）。
            name = chatType.name().getString();
        }
        // 别人用 /msg 对角色说的话是"收到的私聊"这种消息类型；自己发出的私聊回显按发言人是自己滤掉。
        boolean isPrivate = chatType != null && chatType.chatType().is(ChatType.MSG_COMMAND_INCOMING);
        String text = (content != null ? content : line).getString();
        return ReceivedChat.fromPlayer(text, senderId, name, isPrivate, self).map(chat -> chat.with(mentions, key));
    }

    /** 服务器的系统消息；动作栏提示不算聊天。死亡播报这类带着点到的实体与翻译键。 */
    public static Optional<ReceivedChat> system(Component message, boolean actionBar) {
        return ReceivedChat.fromServer(message.getString(), actionBar)
                .map(chat -> chat.with(EntityMentions.in(message), translationKey(message)));
    }

    /** 消息整句是一条翻译（顶层是可翻译内容）时的翻译键，例如 death.attack.explosion.player；不是时为 null。 */
    static String translationKey(Component message) {
        return message.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : null;
    }

    /**
     * 从套好聊天格式的整行里找回原话：整行是消息类型那条格式的翻译，原话是格式参数里"内容"那一格。
     * 别的模组改过整行、格式对不上时为空，调用方用整行。
     */
    static Optional<Component> contentOf(Component line, ChatType.Bound chatType) {
        if (chatType == null || !(line.getContents() instanceof TranslatableContents translatable)) {
            return Optional.empty();
        }
        ChatTypeDecoration format = chatType.chatType().value().chat();
        Object[] arguments = translatable.getArgs();
        int at = format.parameters().indexOf(ChatTypeDecoration.Parameter.CONTENT);
        if (!format.translationKey().equals(translatable.getKey()) || at < 0
                || arguments.length != format.parameters().size()) {
            return Optional.empty();
        }
        return arguments[at] instanceof Component said ? Optional.of(said) : Optional.empty();
    }

    // 按 UUID 从当前连接的玩家列表里查名字；查不到（还没同步、已经下线）时为 null，不编一个。
    private static String nameOf(Minecraft minecraft, UUID senderId) {
        ClientPacketListener connection = minecraft.getConnection();
        if (senderId == null || connection == null) return null;
        PlayerInfo info = connection.getPlayerInfo(senderId);
        return info == null ? null : info.getProfile().getName();
    }
}
