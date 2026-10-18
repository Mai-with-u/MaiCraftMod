// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing.spi;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 身上没带落地料时从哪儿现取一件：例如拿出身上的 AE2 无线终端，从绑定的网络里取一桶水。
 * 意外往下掉、落点又没东西接时，落地防护先问它；取得到就在空中接着放，取不到为空，照旧如实说没有东西接。
 *
 * <p>只在原地取，不为了取东西走路：坠落中每一刻都还在往下掉，走开就来不及接住自己。
 */
public interface LandingMaterials {

    /**
     * 现在能不能取一件用上：取得到给一个把东西放进背包的动作，取不到为空。
     *
     * @param itemIds 依次想要的物品 ID（水桶优先）；实现只取其中一种
     */
    Optional<Action> fetch(PlayerContext context, List<String> itemIds);

    /** 从哪儿取的，写进结果，例如"身上的无线终端"。 */
    String describe();
}
