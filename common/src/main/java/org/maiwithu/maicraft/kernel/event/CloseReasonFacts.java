// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.event;

import org.maiwithu.maicraft.kernel.task.CloseReason;

/**
 * 目标被收掉时挂在结束事件上的收场原因：结果状态把"被取消"和"被替换"压成同一种 CANCELLED，
 * 宿主与复盘靠这个字段分清是模型主动换策略、被新目标顶掉、起居让位、还是角色死亡断线。
 * 结果自己走到结局（DONE/PARTIAL/FAILED）不带它。
 *
 * <p>不另套一层：{@code close_reason} 就是这条收场事件本身要说的事实。
 */
public record CloseReasonFacts(CloseReason closeReason) implements EventFacts {

    @Override public String field() {
        return null;
    }
}
