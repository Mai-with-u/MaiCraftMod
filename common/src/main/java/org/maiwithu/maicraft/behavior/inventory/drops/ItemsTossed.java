// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.drops;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 角色朝某位玩家扔出物品的结构化事实：仅在游戏确认物品已从背包扣减并抛出后发出，
 * 挂在 {@code items_tossed} 事件的 {@code items_tossed} 字段上，供宿主记录当面扔出交付凭证；
 * 只陈述“朝谁扔出了什么几件、在哪”，绝不假定对方已捡起或收下。
 *
 * @param to     朝哪位玩家扔出（对方名字）
 * @param toUuid 对方玩家的 UUID，宿主按它稳定对人
 * @param at     扔出那一刻角色所在坐标与维度；读不到位置时为 null
 * @param items  确认扔出的物品清单（非空），每项含物品注册 ID 与确认件数
 */
public record ItemsTossed(String to, String toUuid, WorldPosition at, List<Item> items) implements EventFacts {

    /** 确认朝对方扔出的一样物品与件数。 */
    public record Item(String id, int count) {
        public Item {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) throw new IllegalArgumentException("物品 ID 不能为空");
            if (count < 1) throw new IllegalArgumentException("扔出数量至少为 1：" + count);
        }
    }

    public ItemsTossed {
        Objects.requireNonNull(to, "to");
        if (to.isBlank()) throw new IllegalArgumentException("对方名字不能为空");
        Objects.requireNonNull(toUuid, "toUuid");
        if (toUuid.isBlank()) throw new IllegalArgumentException("对方 UUID 不能为空");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) throw new IllegalArgumentException("扔出的物品清单不能为空");
    }

    @Override
    public String field() {
        return "items_tossed";
    }
}
