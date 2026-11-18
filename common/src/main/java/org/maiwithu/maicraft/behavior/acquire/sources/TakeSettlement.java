// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.Map;

/**
 * 一轮开箱搬货的结算：这一趟实际搬进了什么、箱里本来有没有要的、搬完还剩没有。
 * 拿到多少以结算为准——点出去的笔不冒充拿到，件数从收场读数（或界面没了时背包的差额）核对出来。
 *
 * @param taken               实际搬进角色背包的各物品件数（按物品 ID，药水合并算一种），按箱子格子先后
 * @param matchedAtOpen       开箱第一眼里箱里有没有要的东西
 * @param remainedInContainer 关箱时箱里是不是还剩着没搬完的要的东西（背包放不下或到了总件数）
 */
public record TakeSettlement(Map<String, Integer> taken, boolean matchedAtOpen, boolean remainedInContainer) {

    public TakeSettlement {
        taken = taken == null ? Map.of() : Map.copyOf(taken);
    }

    /** 还没收场、算不出账时的空结算。 */
    public static TakeSettlement none() {
        return new TakeSettlement(Map.of(), false, false);
    }
}
