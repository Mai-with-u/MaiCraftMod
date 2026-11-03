// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.interaction.OverlayMessages;
import org.maiwithu.maicraft.game.serverlink.ReceivedConfirmations;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;

/**
 * 游戏拒绝读端：动作栏提示语、聊天栏里的服务端系统消息与"服务端没有报告这次交互有结果"的信号。
 *
 * <p>动作栏文案是游戏事实，读不到如实给空。服务端只在交互真实生效（放置、破坏、使用）
 * 后推送确认；一段时间内确认流纹丝不动，就说明最近的交互没有被任何东西处理。
 * 这个信号是粗的：别的任务同时做成了事也会让确认流动起来，所以只回答"最近有没有服务端
 * 见到的成果"，不下"必定被拒绝了"的结论。
 */
public final class ClientGameRefusals {

    /** 多久没有新确认才算"交互没被处理"；半分钟是服务端推送与网络余量的宽松上限。 */
    static final long UNHANDLED_WINDOW_TICKS = 20L * 30;

    private final OverlayMessages overlayMessages;
    private final Supplier<List<ReceivedConfirmations.ServerConfirmation>> confirmations;
    private final LongSupplier clientTicks;
    private final ChatEventLog chat;
    private int seenCount;
    private long lastFlowTick;

    public ClientGameRefusals(OverlayMessages overlayMessages,
                              Supplier<List<ReceivedConfirmations.ServerConfirmation>> confirmations,
                              LongSupplier clientTicks) {
        this(overlayMessages, confirmations, clientTicks, new ChatEventLog());
    }

    /** @param chat 聊天栏收到的消息：模组、插件常把拒绝写成服务端系统消息 */
    public ClientGameRefusals(OverlayMessages overlayMessages,
                              Supplier<List<ReceivedConfirmations.ServerConfirmation>> confirmations,
                              LongSupplier clientTicks, ChatEventLog chat) {
        this.overlayMessages = Objects.requireNonNull(overlayMessages);
        this.confirmations = Objects.requireNonNull(confirmations);
        this.clientTicks = Objects.requireNonNull(clientTicks);
        this.chat = Objects.requireNonNull(chat);
        this.seenCount = confirmations.get().size();
        this.lastFlowTick = clientTicks.getAsLong();
    }

    /** 出手前记一下聊天栏读到哪了：之后收到的服务端系统消息才算"这一下前后"的回应。 */
    public long chatMark() {
        return chat.latestCursor();
    }

    /**
     * 记号之后聊天栏收到的服务端系统消息原话，按收到先后。模组（车万女仆的名额满、"这不是你的女仆"）与插件（领地保护）
     * 常把拒绝写在这里；同一段时间里别人进出服务器的提示也会进来，所以只给原话，不按文字猜哪条和这一下有关。
     */
    public List<String> systemMessagesSince(long mark) {
        return chat.systemTextsAfter(mark);
    }

    /** 最近一条动作栏提示语；没有在显示时为空。 */
    public Optional<String> latestMessage() {
        String text = overlayMessages.latest();
        return text == null ? Optional.empty() : Optional.of(text);
    }

    /**
     * 动作栏此刻正显示着的提示，连同还要显示几刻；没有在显示时为 null。点下去之后要认"这一下新冒出来的话"
     * 用它：同一句拒绝连说两遍文字一样，只有剩余显示刻数会跳回满格。
     */
    public OverlayMessages.Shown shownMessage() {
        return overlayMessages.shown();
    }

    /** 最近一段时间服务端没有推送过任何交互成果，最近的交互多半没被处理。 */
    public boolean interactionWasUnhandled() {
        List<ReceivedConfirmations.ServerConfirmation> recent = confirmations.get();
        long now = clientTicks.getAsLong();
        if (recent.size() != seenCount) {
            seenCount = recent.size();
            lastFlowTick = now;
            return false;
        }
        return now - lastFlowTick >= UNHANDLED_WINDOW_TICKS;
    }
}
