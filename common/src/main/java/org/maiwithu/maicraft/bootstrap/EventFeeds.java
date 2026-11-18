// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.ability.timer.Timers;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 往外报事的几条出口：任务事件流、LLM 挂的计时表与聊天栏收到的消息——
 * 目标处境、生存需求与动别人东西的通报都从这一组出去，对外通道（Channels）也从这里拼。
 * 新加一条对外报事的出口，加在这里并传入所在组即可。
 */
record EventFeeds(
        /** 任务事件出口：进世界后按当前目标收窄的动作事件也从同一条流出。 */
        TaskEventSink events,
        Timers timers,
        ChatEventLog chatEvents) {

    EventFeeds {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(timers, "timers");
        Objects.requireNonNull(chatEvents, "chatEvents");
    }
}
