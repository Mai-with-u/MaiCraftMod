// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.Map;

/**
 * 一只看得见或刚看见过的实体：类型、名字、方位、距离、高低差、是否敌对、是否正盯着我、
 * 血量快照，以及看得出来的特征（羊的颜色、是否剪过毛、幼年、着火、手持物、盔甲）。
 *
 * <p>可见为假时这条只代表"刚才看见过、现在还在保留期内"，不冒充此刻看得见。
 * {@code inView} 进一步区分该实体此刻是否落在角色第一人称屏幕前方视野锥内（背对着的无遮挡生物为假）。
 * 血量是活体生物才有的观察事实，不是活体时为 null。
 * UUID 只给认得出是哪一个的：玩家、有名字的、被驯服的；别的为 null，一群僵尸不用各带一串编号。
 */
public record SceneEntity(
        String id, String type, String name, String uuid,
        String direction, String compass, int distance, int dy,
        boolean visible, boolean hostile, boolean targetingMe,
        EntitySight.Health health,
        Map<String, String> traits,
        boolean inView) implements SceneSighting {

    public SceneEntity {
        traits = Map.copyOf(traits);
    }

    /** 未显式传入前方视野标志的兼容构造：离线假数据默认与世界可见一致。 */
    public SceneEntity(
            String id, String type, String name, String uuid,
            String direction, String compass, int distance, int dy,
            boolean visible, boolean hostile, boolean targetingMe,
            EntitySight.Health health,
            Map<String, String> traits) {
        this(id, type, name, uuid, direction, compass, distance, dy, visible, hostile, targetingMe, health, traits,
                visible);
    }
}
