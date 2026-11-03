// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.function.LongSupplier;

import org.maiwithu.maicraft.ability.timer.Timers;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 对外的通道：聊天通道、客户端刻号、任务事件出口、计时表、服务端会话与聊天栏收到的消息——
 * 能力往外发话、往外报事、读服务端回音都经这一组。新加一条对外通路，加在这里并传入所在组即可。
 */
record Channels(
        ChatChannel chat,
        /** 客户端刻号跟着所在世界走；没进世界读不到，按 0 兜底（只在读端内部量时长用）。 */
        LongSupplier clientTicks,
        TaskEventSink events,
        Timers timers,
        ServerLinkSession session,
        /** 聊天栏收到的消息（events(topic=chat) 读的那一份）：交互前后服务端在聊天栏说了什么从这里读。 */
        ChatEventLog chatEvents) {

    Channels {
        Objects.requireNonNull(chat, "chat");
        Objects.requireNonNull(clientTicks, "clientTicks");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(timers, "timers");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(chatEvents, "chatEvents");
    }

    /** 离线清单用：聊天栏收不到东西，给一份空的。 */
    Channels(ChatChannel chat, LongSupplier clientTicks, TaskEventSink events, Timers timers,
            ServerLinkSession session) {
        this(chat, clientTicks, events, timers, session, new ChatEventLog());
    }
}
