// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;
import java.util.Objects;

/**
 * 一条"放下基底方块、拿手里物品右键改造成新东西"的配方（机械动力的手工涂抹：
 * 放下去皮木料拿安山合金、铜锭、黄铜锭涂成机壳，放下黄铜机壳拿坚固板涂成列车机壳，放下纸板块拿线捆成捆绑纸板块）。
 * 只记配方事实，选点、摆块、右键与挖回全由玩家行为层做。
 *
 * @param id                配方 ID，报价线索带着它，动手时按它找回同一条
 * @param baseItems         可放下的基底方块物品 ID 列表（均须为能放成方块的物品）
 * @param heldItems         拿在手里右键涂抹的物品 ID 列表（任一种都行）
 * @param keepHeldItem      涂抹时是否保留手持物（不消耗、不扣耐久）
 * @param resultId          主产物的物品 ID
 * @param resultCount       涂抹一次出几件主产物
 * @param placesResultBlock 主产物是不是原地替换成的方块（为真时挖回得产物，为假时原格变空气并直接掉落产物）
 */
public record BlockApplication(
        String id,
        List<String> baseItems,
        List<String> heldItems,
        boolean keepHeldItem,
        String resultId,
        int resultCount,
        boolean placesResultBlock) {

    public BlockApplication {
        Objects.requireNonNull(id, "id");
        baseItems = List.copyOf(baseItems);
        heldItems = List.copyOf(heldItems);
        Objects.requireNonNull(resultId, "resultId");
        if (baseItems.isEmpty()) {
            throw new IllegalArgumentException("手工涂抹配方至少要有一种可放下的基底方块：" + id);
        }
        if (heldItems.isEmpty()) {
            throw new IllegalArgumentException("手工涂抹配方至少要有一种手持涂抹物：" + id);
        }
        if (resultCount < 1) {
            throw new IllegalArgumentException("涂抹一次至少出一件：" + id);
        }
    }
}
