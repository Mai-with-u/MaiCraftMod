// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 村民读取的只读接缝：某一格附近看不看得见村民。村庄里的床是村民睡的，角色就算睡过一晚，
 * 收回"自己睡过的床"时也让开（玩家常识：借住村庄的床，走的时候不拆走）。
 * 只认村民本身，不认流浪商人；读不到现场（还没进世界、人接管中）按没看见算，不凭空当成村庄。
 * 生产实现读客户端世界里的村民，测试给固定值。
 */
@FunctionalInterface
public interface SeesVillagers {

    /** 什么都看不见：接缝还没接上时的占位，保护判断照常工作，只是认不出村庄。 */
    SeesVillagers NOBODY = (position, radius) -> false;

    /** 这一格周围 radius 格内有没有村民。 */
    boolean villagersNear(WorldPosition position, double radius);
}
