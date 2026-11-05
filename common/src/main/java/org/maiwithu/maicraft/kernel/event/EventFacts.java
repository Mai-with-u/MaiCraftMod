// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

/**
 * 一条任务事件附带的结构化事实：消息说一句话，宿主要按字段处理的（谁的、在哪、几件）放在这里。
 *
 * <p>发事件的一侧用自己的 record 实现它，字段就是要告诉宿主的事实；MCP 入口按 {@link #field()} 给的名字
 * 把它整份转成 JSON 挂在事件上，和结果细节同一种转法。内核只负责带着它走，不认识里面是什么。
 */
public interface EventFacts {

    /** 挂在事件上的字段名（小写下划线），例如 {@code others_food_taken}。 */
    String field();
}
