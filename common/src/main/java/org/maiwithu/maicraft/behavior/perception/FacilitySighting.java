// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 一处亲眼看到的设施：箱子、工作台、熔炉、营火、床这类功能方块，一个观察编号 b#。
 * 上次看到的内容在世界记忆里（开过的容器记着里面有什么），观察视图按编号去查，不在这里重复一份；
 * 原版营火的点燃状态与架上食物肉眼可见，随本次观察直接附带。
 *
 * @param campfire 营火实时状态；非营火设施为 null
 */
public record FacilitySighting(
        String id, String blockType, WorldPosition position,
        String direction, String compass, int distance, CampfireState campfire) implements SceneSighting {

    public FacilitySighting(String id, String blockType, WorldPosition position,
            String direction, String compass, int distance) {
        this(id, blockType, position, direction, compass, distance, null);
    }
}
