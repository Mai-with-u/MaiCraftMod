// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.List;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Target;

/**
 * 一次钻洞说好的事：留意什么、往哪边偏、走多远、许可动多少地形。能力校验过参数后交给钻洞任务，不可变。
 *
 * @param interestBlocks 留意清单里的方块 ID 或 # 标签：看见了就走到跟前收场
 * @param watchLava      留意清单里有 lava_pool：看见岩浆也走到跟前收场
 * @param direction      在地面上挑哪个方向的洞口、进了洞偏向哪个方位；不给为 null
 * @param angleDegrees   挑洞口的扇形开口角（度）
 * @param minDistance    比这近的洞口不挑（格）
 * @param maxDistance    离洞口直线最远走到几格
 * @param maxSeconds     多久没有真实进展算卡住（秒）；不超过 0 用默认的半分钟
 * @param permit         走到能不能挖垫；能的话量路时往下跳三格以内也算连通
 */
public record CaveWalkPlan(List<String> interestBlocks, boolean watchLava, Target.Toward direction,
        int angleDegrees, int minDistance, int maxDistance, int maxSeconds, TerrainPermit permit) {

    public CaveWalkPlan {
        interestBlocks = List.copyOf(interestBlocks);
    }

    /** 留意清单里有没有东西：有的话看见就停。 */
    public boolean hasInterests() {
        return !interestBlocks.isEmpty() || watchLava;
    }
}
