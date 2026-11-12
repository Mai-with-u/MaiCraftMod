// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 够得着规则：一次靠近用到的距离数值。数值本身是玩家属性或服务端握手状态，由游戏接口层在动手前读出来；
 * 站位判断只拿这些数做纯计算，自己不碰游戏对象。
 *
 * @param blockRange    方块交互距离（格），读角色的 blockInteractionRange 属性
 * @param entityRange   实体交互距离（格），读角色的 entityInteractionRange 属性
 * @param bedDistance   服务端对床允许的距离档：站位与床底面中心各轴的偏移不超过它
 * @param eyeHeight     角色此刻的眼睛离脚底的高度（格）；潜行时会变矮，按当时的姿态取
 * @param serverPadding 服务端对已到达站位放行的交互距离余量（格）：新服务端角色账号为 2.0，旧服务端为原版 1.0
 */
public record ReachRules(double blockRange, double entityRange, int bedDistance, double eyeHeight,
                         double serverPadding) {

    /** 默认按原版服务端 1.0 格交互余量构造。 */
    public ReachRules(double blockRange, double entityRange, int bedDistance, double eyeHeight) {
        this(blockRange, entityRange, bedDistance, eyeHeight, InteractionRange.VANILLA_SERVER_PADDING);
    }

    public ReachRules {
        if (blockRange <= 0 || entityRange <= 0 || bedDistance <= 0 || eyeHeight <= 0 || serverPadding < 0) {
            throw new IllegalArgumentException("交互距离与眼高必须是正数，服务端距离余量不能为负数");
        }
    }
}
