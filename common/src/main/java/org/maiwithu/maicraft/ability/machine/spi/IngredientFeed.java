// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Objects;

/**
 * 配方里背包带不进机器的那一格原料（通用机械的灌注物这类化学品），机器能从附加格吃物品再换成它时，用哪样物品投：
 * 例如冶金灌注机要碳，就往附加格投煤，一块煤换 10 份碳。machine_run 把它和主料一起备齐，先投它再投主料。
 *
 * @param itemId 投哪样物品
 * @param count  投几件：按配方要的量与一件换多少算，没扣机器里已有的（投的时候能读到就扣）
 * @param note   给 LLM 看的一句话：要的是什么、为什么投这样、一件换多少、还能用哪几样
 */
public record IngredientFeed(String itemId, int count, String note) {

    public IngredientFeed {
        Objects.requireNonNull(itemId, "itemId");
        if (count < 1) throw new IllegalArgumentException("至少投 1 件：" + count);
        note = note == null ? "" : note;
    }
}
