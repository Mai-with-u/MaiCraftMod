// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;

import net.minecraft.world.level.Level;

/**
 * 一个模组的"放下基底方块、拿材料右键改造"的配方表：机械动力的手工涂抹配方由它的联动交来。
 * 拿东西有一条途径专门走它，备料、摆块、右键涂抹与挖回拾取全在玩家行为层，这里只回答有哪些配方。
 */
public interface BlockApplications {

    /** 这些配方走哪条拿东西的途径（机械动力是 item_application），写进能力说明的 via 可选值。 */
    AcquireVia via();

    /** 此刻这个世界里的配方（客户端同步过来的那一份）；读不到给空表。 */
    List<BlockApplication> recipes(Level level);
}
