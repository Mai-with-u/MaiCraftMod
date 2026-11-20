// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * 加载器转来的聊天事件读成收到的聊天：认出发言人、私聊、角色自己的回显，以及借角色名字代发的消息；
 * 再从消息组件里读出点到的实体与翻译键。
 *
 * <p>两个加载器给的东西不一样多：有签名的玩家消息带原话与发言人 UUID；没有签名的（命令方块的 /say、
 * 模组用系统消息伪装成的聊天）只有聊天栏显示的整行和消息类型。名字没给时按 UUID 从当前连接的玩家列表里查，
 * 再不行用消息类型里的名字。每条玩家说的话都核对一次发言人在不在当前玩家列表里：按 UUID 查到、名字也对得上的，
 * 才是那个在线玩家说的；控制台、命令方块的 /say 只借了个名字，不算。有的服务器不走签名聊天，把玩家的公屏聊天整句当系统消息发下来，
 * 格式仍是公屏聊天那一条（"&lt;名字&gt; 原话"）：名字是在线玩家的，就读成他说的话。只在客户端线程调用。
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
        return player(line, content, senderId, sender, chatType, self, selfName,
                id -> nameOf(minecraft, id), id -> loadedPlayerDistance(minecraft, id));
    }

    /**
     * 同上，角色是谁、玩家列表里谁叫什么已经读好（不查距离）：离线测试直接喂组件。
     */
    static Optional<ReceivedChat> player(Component line, Component content, UUID senderId, String sender,
                                         ChatType.Bound chatType, UUID self, String selfName,
                                         Function<UUID, String> listedName) {
        return player(line, content, senderId, sender, chatType, self, selfName, listedName, id -> null);
    }

    /**
     * 同上，额外注入已加载玩家距离查询：仅当发言人存在于客户端已加载世界实体列表里时计算真实距离。
     *
     * @param sender         加载器给的发言人名字；没给时为 null，按 UUID 从玩家列表里查
     * @param self           角色自己的 UUID；不在世界里时为 null
     * @param selfName       角色的账号名；不在世界里时为 null
     * @param listedName     当前玩家列表里这个 UUID 的玩家叫什么；不在列表里时为 null
     * @param loadedDistance 客户端已加载世界里这个 UUID 的玩家距本地角色的真实格数；未加载时为 null
     */
    static Optional<ReceivedChat> player(Component line, Component content, UUID senderId, String sender,
                                         ChatType.Bound chatType, UUID self, String selfName,
                                         Function<UUID, String> listedName, Function<UUID, Double> loadedDistance) {
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
                return ReceivedChat.underOwnName(withClickCommands(read), shown.getString())
                        .map(chat -> chat.with(mentions, key));
            }
        }
        // 发言人名字：加载器给了用给的，没给按 UUID 从玩家列表里查；没有签名的消息只剩消息类型里的名字
        // （服务器给它起的显示名，控制台是 Server、命令方块是 @）。
        String listed = senderId == null ? null : listedName.apply(senderId);
        String name = sender != null ? sender : listed;
        if (name == null && chatType != null) name = chatType.name().getString();
        // 发言人 UUID 在当前玩家列表里、列表里的名字和这条消息的发言人名字对得上，才是那个在线玩家说的；
        // 签没签名都一样。没有 UUID 的（控制台、命令方块的 /say）只借了个名字，不算。
        boolean online = listed != null && listed.equals(name);
        // 只有核对为在线玩家且该玩家实体此刻加载在客户端世界里时才给真实几何距离，未加载绝不猜测或透视。
        Double distance = online && senderId != null ? loadedDistance.apply(senderId) : null;
        // 别人用 /msg 对角色说的话是"收到的私聊"这种消息类型；自己发出的私聊回显按发言人是自己滤掉。
        boolean isPrivate = chatType != null && chatType.chatType().is(ChatType.MSG_COMMAND_INCOMING);
        // 展示正文保留原样（有签名取原话、无签名取整行），点击可执行命令只从原话段提取，不扫外层名字装饰。
        String text = withClickCommands((content != null ? content : line).getString(), read);
        return ReceivedChat.fromPlayer(text, senderId, name, online, distance, isPrivate, self)
                .map(chat -> chat.with(mentions, key));
    }

    /** 公屏聊天那一条格式的翻译键（"&lt;名字&gt; 原话"）。 */
    static final String PUBLIC_CHAT_KEY = "chat.type.text";

    /**
     * 服务器的系统消息；动作栏提示不算聊天。死亡播报这类带着点到的实体与翻译键。
     * 整句是公屏聊天格式、名字在当前玩家列表里的，是没有签名的服务器转来的玩家聊天，读成那个玩家说的话，
     * 发言人 UUID 取玩家列表里这个名字的那一项；若该玩家此刻已加载在客户端世界里，一并附上真实几何距离。
     */
    public static Optional<ReceivedChat> system(Minecraft minecraft, Component message, boolean actionBar) {
        UUID self = minecraft.player == null ? null : minecraft.player.getUUID();
        String selfName = minecraft.player == null ? null : minecraft.player.getGameProfile().getName();
        ClientPacketListener connection = minecraft.getConnection();
        return system(message, actionBar, self, selfName,
                name -> listedId(connection, name), id -> loadedPlayerDistance(minecraft, id));
    }

    /**
     * 同上，角色是谁、谁在线已经读好（不查距离）：离线测试直接喂组件。
     */
    static Optional<ReceivedChat> system(Component message, boolean actionBar, UUID self, String selfName,
                                         Function<String, UUID> listedId) {
        return system(message, actionBar, self, selfName, listedId, id -> null);
    }

    /**
     * 同上，额外注入已加载玩家距离查询：仅当拆出的在线玩家存在于客户端已加载世界实体列表里时计算真实距离。
     *
     * @param self           角色自己的 UUID；不在世界里时为 null
     * @param selfName       角色的账号名；不在世界里时为 null
     * @param listedId       当前玩家列表里叫这个名字的玩家的 UUID；不在列表里时为 null
     * @param loadedDistance 客户端已加载世界里这个 UUID 的玩家距本地角色的真实格数；未加载时为 null
     */
    static Optional<ReceivedChat> system(Component message, boolean actionBar, UUID self, String selfName,
                                         Function<String, UUID> listedId, Function<UUID, Double> loadedDistance) {
        Optional<PublicLine> said = actionBar ? Optional.empty() : publicLine(message);
        UUID listed = said.map(line -> listedId.apply(line.name())).orElse(null);
        if (listed != null) {
            PublicLine line = said.get();
            // 名字是角色自己（点着的实体是角色，或和账号名一样）：自己说话的回显，和有签名时按 UUID 滤掉一样，不算收到的聊天。
            if (ReceivedChat.isUnderOwnName(null, line.name(), line.nameId(), self, selfName)) return Optional.empty();
            // 别的玩家说的话：消息本身不带发言人 UUID，名字照聊天格式写；名字是按玩家列表对上的，算在线玩家说的，
            // 发言人 UUID 就用玩家列表里这个名字那一项的，宿主能按 UUID 认出是哪位玩家。点到的实体与翻译键只从原话里读。
            Component words = line.words();
            Double distance = loadedDistance.apply(listed);
            return ReceivedChat.fromPlayer(withClickCommands(words), listed, line.name(), true, distance, false, self)
                    .map(chat -> chat.with(EntityMentions.in(words), translationKey(words)));
        }
        return ReceivedChat.fromServer(withClickCommands(message), actionBar)
                .map(chat -> chat.with(EntityMentions.in(message), translationKey(message)));
    }

    /** 公屏聊天格式里的发言人名字、名字点着的实体（没挂悬停时为 null）和原话。 */
    private record PublicLine(String name, UUID nameId, Component words) {}

    // 整句是公屏聊天那一条翻译、两个参数是名字和原话时拆出来；别的格式（表情动作、私聊、公告）不拆。
    private static Optional<PublicLine> publicLine(Component message) {
        if (!(message.getContents() instanceof TranslatableContents translatable)
                || !PUBLIC_CHAT_KEY.equals(translatable.getKey())) {
            return Optional.empty();
        }
        Object[] arguments = translatable.getArgs();
        if (arguments.length != 2) return Optional.empty();
        Component name = arguments[0] instanceof Component part ? part : Component.literal(String.valueOf(arguments[0]));
        Component words = arguments[1] instanceof Component part ? part : Component.literal(String.valueOf(arguments[1]));
        String shown = name.getString().strip();
        if (shown.isEmpty()) return Optional.empty();
        UUID nameId = EntityMentions.named(name).map(MentionedEntity::uuid).orElse(null);
        return Optional.of(new PublicLine(shown, nameId, words));
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

    // 从聊天组件及其子组件、翻译参数里递归收集点击可执行的命令，让 Ess 传送按钮等富文本指令进纯文本仍可见。
    private static String withClickCommands(Component root) {
        return withClickCommands(root.getString(), root);
    }

    // 按给定的展示正文与点击来源组件拼装：外层玩家名字装饰不参与命令收集，正文已写出命令时不重复附。
    private static String withClickCommands(String text, Component clickSource) {
        List<String> commands = new ArrayList<>();
        collectClickCommands(clickSource, commands);
        if (commands.isEmpty()) return text;
        List<String> missing = new ArrayList<>();
        for (String command : commands) {
            if (!text.contains(command) && !missing.contains(command)) {
                missing.add(command);
            }
        }
        return missing.isEmpty() ? text : text + "（可点击执行: " + String.join(" / ", missing) + "）";
    }

    // 只收点击后真正执行的命令（RUN_COMMAND）：原版给每位玩家显示名默认挂的 /tell 填框建议（SUGGEST_COMMAND）不是可执行按钮。
    private static void collectClickCommands(Component node, List<String> out) {
        if (node == null) return;
        ClickEvent click = node.getStyle().getClickEvent();
        if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND) {
            String value = click.getValue().strip();
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        if (node.getContents() instanceof TranslatableContents translatable) {
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component child) {
                    collectClickCommands(child, out);
                }
            }
        }
        for (Component sibling : node.getSiblings()) {
            collectClickCommands(sibling, out);
        }
    }

    // 按名字从当前连接的玩家列表里查 UUID；不在列表里（不是在线玩家，或没连着服务器）时为 null。
    private static UUID listedId(ClientPacketListener connection, String name) {
        if (connection == null) return null;
        PlayerInfo info = connection.getPlayerInfo(name);
        return info == null ? null : info.getProfile().getId();
    }

    // 按 UUID 从当前连接的玩家列表里查名字；查不到（还没同步、已经下线）时为 null，不编一个。
    private static String nameOf(Minecraft minecraft, UUID senderId) {
        ClientPacketListener connection = minecraft.getConnection();
        if (senderId == null || connection == null) return null;
        PlayerInfo info = connection.getPlayerInfo(senderId);
        return info == null ? null : info.getProfile().getName();
    }

    // 仅从客户端当前已加载世界的玩家实体列表中找发言人，计算与本地角色的真实几何距离（保留 1 位小数）；
    // 玩家不在当前已加载实体里（距离太远未加载、在别的维度等）时返回 null，绝不透视未加载区块。
    private static Double loadedPlayerDistance(Minecraft minecraft, UUID senderId) {
        if (senderId == null || minecraft == null || minecraft.player == null || minecraft.level == null) {
            return null;
        }
        for (AbstractClientPlayer other : minecraft.level.players()) {
            if (other != minecraft.player && !other.isRemoved() && senderId.equals(other.getUUID())) {
                return ReceivedChat.roundDistance((double) minecraft.player.distanceTo(other));
            }
        }
        return null;
    }
}
