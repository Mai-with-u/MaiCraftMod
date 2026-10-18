// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.List;
import java.util.function.Consumer;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 发起一次拿东西的入口：拿到物品的引擎以这个样子交给能力与任务，
 * 判断与任务用替身测的时候不必造出一台真引擎。
 * 语义与 {@link ItemAcquisition#need}、{@link ItemAcquisition#needSeveral} 一致；引擎是它目前的唯一实现。
 */
public interface StartsAcquisition {

    /**
     * 带限定的一次拿东西：只走指定途径、只考虑愿意走的距离、只在给定的半径里找，
     * 实际拿到东西时把途径报告给 onDelivered；来源动作里点了但没能确认结果的交互、
     * 途中腾地方存了丢了什么，都记进发起任务的记账口 records（没能确认的不能盲目重做）。
     */
    Action need(ItemRequest request, Permissions permissions,
            ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records);

    /**
     * 一次要几样：一张要货单逐样去弄，每样仍按上面那套问价、挑路、重新清点；
     * 哪一样被顺手拿到就不再单独去弄，一样弄不到不耽误别的。
     * 拿到一部分以问题失败收场时，价钱认到的那些照旧进背包、由发起任务重新清点。
     */
    Action needSeveral(List<ItemRequest> requests, Permissions permissions,
            ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records);
}
