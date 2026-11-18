// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.List;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;

/**
 * 一张容器取货单：从一只容器里搬一批货——搬哪几样、总共最多几件。
 *
 * <p>要的几样按 {@link WantedItem} 的写法点名（具体物品、带种类的药水或一个标签）；
 * 不点名（items 为空）就是整箱非空物品都搬。总件数是这一批共用的一条线：
 * 几样合起来拿够 maxCount 就停手，不大于零是不限。
 *
 * @param items    要的几样；空 = 整箱非空物品都搬
 * @param maxCount 这一批总共最多几件；不大于零 = 不限
 */
public record ContainerTakeOrder(List<WantedItem> items, int maxCount) {

    public ContainerTakeOrder {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 给结果与日志用的一句话，例如"整箱"或"#minecraft:logs 里的任一物品、minecraft:dirt"。 */
    public String describe() {
        if (items.isEmpty()) return "整箱";
        return items.stream().map(WantedItem::describe).collect(Collectors.joining("、"));
    }
}
