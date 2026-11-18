// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 开一次箱搬一批货的接缝：按取货单把整箱或点名的几样搬进背包，搬空、凑够总件数或背包放不下就关上。
 * 是 {@link ContainerTakes} 的加长版：生产的实现是同一份 {@link MenuContainerTakes}；
 * 返回的动作实现 {@link ReportsTakeSettlement}，收场后从它读结算与点不开的原因。
 */
public interface ContainerBatchTakes extends ContainerTakes {

    /** 按取货单开一次箱搬一批货；取货单不点名时整箱非空物品都搬。 */
    Action take(KnownContainer container, ContainerTakeOrder order, Permissions permissions);
}
