// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

/**
 * 任务事件的出口：任务与生存需求用它把"开始干了、卡在哪、自己处理不了"告诉宿主。
 *
 * <p>事件只描述已经发生或正在发生的事，不替 LLM 做决定。出口由启动一侧创建并登记；
 * 还没接上时用 {@link #NONE}，事实照常组装，只是没有人听到。
 */
public interface TaskEventSink {

    /**
     * 发一条任务事件。
     *
     * @param kind    事件的种类，取 TaskEvent.Kind 里的值
     * @param message 用游戏里的话说清发生了什么
     */
    void publish(TaskEvent.Kind kind, String message);

    /**
     * 发一条带结构化事实的任务事件：消息照样说一句话，宿主要按字段处理的放在事实里。
     * 不认识事实的出口照只发消息处理。
     */
    default void publish(TaskEvent.Kind kind, String message, EventFacts facts) {
        publish(kind, message);
    }

    /**
     * 发一条由当前动作引起的带结构化事实的事件：手上有正在推进的目标且没被生存需求临时任务借走身体时，
     * 挂在那个目标与当前步骤的能力上；临时任务动手或没有目标时，不挂目标。
     */
    default void publishInAction(TaskEvent.Kind kind, String message, EventFacts facts) {
        publish(kind, message, facts);
    }

    /**
     * 先冻结这一刻正在推进的目标与步骤能力，供稍后合并成一段的事件（例如连拆同一个人的好几格方块）
     * 在收段时按动手当时的目标归属发出，不因目标刚结束而丢掉归属。
     */
    default TaskEventSink captureAction() {
        return this;
    }

    /** 没有接上出口时的空实现：事件就地丢弃。 */
    TaskEventSink NONE = (kind, message) -> {};
}