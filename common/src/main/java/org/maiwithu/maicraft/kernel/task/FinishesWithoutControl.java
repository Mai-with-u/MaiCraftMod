// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 角色正忙腾不出手脚时，不需要控制角色也能直接办完的任务。
 *
 * <p>平时角色空得出手就照常进控制循环做完整套动作（例如打开聊天框逐字打字）；
 * 手上的事一时停不下或正在处理自卫、换气、撤离时，不必干等或按时机不对放弃，直接把事办完给出结果。
 */
public interface FinishesWithoutControl {

    /** 不控制角色直接办完并返回结果；这件事必须控制角色才能做时返回 null。 */
    TaskResult finishWithoutControl(TickContext context);
}
