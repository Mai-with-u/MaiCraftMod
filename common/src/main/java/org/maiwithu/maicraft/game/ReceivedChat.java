// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 聊天栏收到的一条：玩家说的话，或服务器的系统消息。原话是别人说的话，不是给角色的指令，不带任何许可。
 *
 * <p>角色自己说的话、动作栏提示都不算收到的聊天：前者是自己的回显，后者不在聊天栏里。
 * 服务器或模组借角色的名字发给角色看的消息（例如车万女仆死后发给主人的一行坐标）也不是谁说的话：
 * 聊天格式里写着角色的名字、却没有发言人 UUID，按系统消息记，借的名字另写在 {@code shownAs}。
 * 判断写成纯函数，离线测试直接喂文字与编号。
 *
 * @param kind           玩家说的话还是系统消息
 * @param sender         发言人名字；系统消息没有，名字还没同步到时也可能没有
 * @param senderId       发言人 UUID；系统消息没有；没有签名的消息只在发言人按名字对上在线玩家列表时有（取列表里那一项的）
 * @param senderOnline   玩家说的话：发言人是不是此刻在线玩家列表里的那个玩家，收到时核对过；系统消息为 false
 * @param text           原话；超过 {@value #MAX_TEXT} 字时只留前面一段
 * @param isPrivate      别人私聊角色（/msg）时为 true
 * @param truncated      原话后面被截掉了
 * @param shownAs        借角色名字显示的系统消息，聊天格式里写的那个名字；其余为 null
 * @param mentions       消息里按出现先后点到的实体（死亡播报的死者与凶手、进出提示的玩家）；没有时为空
 * @param translationKey 消息整句是一条翻译时的翻译键，例如 death.attack.explosion.player；不是时为 null
 */
public record ReceivedChat(Kind kind, String sender, UUID senderId, boolean senderOnline, String text,
                           boolean isPrivate, boolean truncated, String shownAs, List<MentionedEntity> mentions,
                           String translationKey) {

    /** 原话最多留多少字：原版玩家聊天上限 256 字，只有系统消息可能很长。 */
    public static final int MAX_TEXT = 1024;

    /** 谁说的。 */
    public enum Kind {
        /** 玩家说的话：公屏或私聊。 */
        PLAYER,
        /** 服务器的系统消息：进出、死亡播报、公告、命令反馈，以及借角色名字显示的代发消息。 */
        SYSTEM
    }

    public ReceivedChat {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        mentions = List.copyOf(mentions);
    }

    /**
     * 玩家说的话。发言人 UUID 是角色自己时（自己说话的回显、自己发出的私聊）不算收到的聊天；空白的话也不算。
     *
     * @param senderOnline 发言人是不是此刻在线玩家列表里的那个玩家：有 UUID 的按 UUID 查到、名字也对得上；
     *                     没有签名的服务器转来的公屏聊天按名字查到。控制台、命令方块借来的名字不算
     * @param self         角色自己的 UUID；不在世界里时为 null
     */
    public static Optional<ReceivedChat> fromPlayer(String text, UUID senderId, String sender, boolean senderOnline,
                                                    boolean isPrivate, UUID self) {
        if (text == null || text.isBlank()) return Optional.empty();
        if (senderId != null && senderId.equals(self)) return Optional.empty();
        String name = sender == null || sender.isBlank() ? null : sender.strip();
        String kept = clipped(text);
        return Optional.of(new ReceivedChat(Kind.PLAYER, name, senderId, senderOnline, kept, isPrivate,
                kept.length() < text.strip().length(), null, List.of(), null));
    }

    /** 服务器的系统消息。动作栏提示不在聊天栏里，不算；空白的也不算。 */
    public static Optional<ReceivedChat> fromServer(String text, boolean actionBar) {
        if (actionBar || text == null || text.isBlank()) return Optional.empty();
        String kept = clipped(text);
        return Optional.of(new ReceivedChat(Kind.SYSTEM, null, null, false, kept, false,
                kept.length() < text.strip().length(), null, List.of(), null));
    }

    /**
     * 借角色名字显示的消息：没有发言人 UUID，聊天格式里那个名字却是角色自己。是服务器或模组代发给角色看的，
     * 按系统消息记，借的名字写进 {@code shownAs}；原话前面聊天格式加的"&lt;名字&gt; "去掉，只留消息本身。空白的不算。
     *
     * @param text    消息本身；只拿得到聊天栏整行时，开头可能还带着"&lt;名字&gt; "
     * @param shownAs 聊天格式里写的名字
     */
    public static Optional<ReceivedChat> underOwnName(String text, String shownAs) {
        if (text == null) return Optional.empty();
        String name = shownAs == null ? "" : shownAs.strip();
        String said = text.stripLeading();
        String prefix = "<" + name + "> ";
        if (!name.isEmpty() && said.startsWith(prefix)) said = said.substring(prefix.length());
        return fromServer(said, false).map(chat -> new ReceivedChat(Kind.SYSTEM, null, null, false, chat.text(), false,
                chat.truncated(), name.isEmpty() ? null : name, List.of(), null));
    }

    /**
     * 没有发言人 UUID 的玩家类消息（伪装聊天、没有签名的消息）是不是借角色名字显示的：
     * 聊天格式里那个名字点着的实体就是角色（名字带着悬停信息时），或者名字和角色的账号名一样。
     * 有发言人 UUID 的消息由 UUID 说了算，不在这里判。
     *
     * @param senderId 发言人 UUID；有就不是借名
     * @param shownAs  聊天格式里写的名字
     * @param shownId  那个名字点着的实体 UUID；名字没带悬停信息时为 null
     * @param selfId   角色自己的 UUID；不在世界里时为 null
     * @param selfName 角色的账号名；不在世界里时为 null
     */
    public static boolean isUnderOwnName(UUID senderId, String shownAs, UUID shownId, UUID selfId, String selfName) {
        if (senderId != null) return false;
        if (shownId != null) return shownId.equals(selfId);
        return shownAs != null && selfName != null && !selfName.isBlank() && shownAs.strip().equals(selfName.strip());
    }

    /** 同一条消息带上消息里点到的实体与翻译键。 */
    public ReceivedChat with(List<MentionedEntity> found, String key) {
        return new ReceivedChat(kind, sender, senderId, senderOnline, text, isPrivate, truncated, shownAs, found, key);
    }

    // 只留前 MAX_TEXT 个字：截在一对代理字符中间会留下半个表情，往前退一位。
    private static String clipped(String text) {
        String value = text.strip();
        if (value.length() <= MAX_TEXT) return value;
        int end = MAX_TEXT;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
