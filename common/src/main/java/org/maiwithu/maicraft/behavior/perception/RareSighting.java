// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 角色在眼前视野里亲眼看见罕见目标（Boss 级生物或天然露出的稀有矿脉）的结构化事实：
 * 挂在 {@code rare_sighting} 事件的 {@code rare_sighting} 字段上，供宿主当场反应并决定是否派任务去应对或采掘。
 *
 * @param category 种类：{@code boss}（Boss 级强敌）或 {@code rare_ore}（稀有矿脉）
 * @param targetId 实体类型注册 ID 或矿石方块注册 ID
 * @param name     游戏里显示的名字
 * @param seenId   场景里的观察编号（实体有编号时带上，矿脉没有则为 null）
 * @param count    看见的数量（Boss 为 1，矿脉为这一簇露在眼前相连的块数）
 * @param at       最近那一格或实体所在的坐标与维度
 */
public record RareSighting(String category, String targetId, String name, String seenId, int count, WorldPosition at)
        implements EventFacts {

    /** Boss 级强敌。 */
    public static final String CATEGORY_BOSS = "boss";
    /** 天然露出的稀有矿脉。 */
    public static final String CATEGORY_RARE_ORE = "rare_ore";

    public RareSighting {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(at, "at");
        if (count < 1) {
            throw new IllegalArgumentException("目击数量至少为 1：" + count);
        }
    }

    @Override
    public String field() {
        return "rare_sighting";
    }
}
