// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 打开战利品容器发现稀罕贵重物品的结构化事实：无论是搜刮、取物还是用东西打开查看，
 * 看到容器里装着 precious 级物品时挂在 rare_loot_found 事件的 rare_loot 字段上交给宿主当里程碑播报。
 *
 * @param block 容器方块注册 ID
 * @param at    容器所在坐标与维度
 * @param items 这一趟新发现的稀罕物品：是什么、几件、显示名、其中拿走了几件
 */
public record RareLootFound(String block, WorldPosition at, List<Item> items) implements EventFacts {

    /**
     * 容器里发现的一样稀罕贵重物品。
     *
     * @param item  物品注册 ID
     * @param count 打开那一刻容器里这一样有几件
     * @param name  游戏里显示的名字
     * @param taken 这一趟从容器里确认拿走了几件；只打开查看没拿时为 0
     */
    public record Item(String item, int count, String name, int taken) {
        public Item {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(name, "name");
        }
    }

    public RareLootFound {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(at, "at");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    @Override
    public String field() {
        return "rare_loot";
    }
}
