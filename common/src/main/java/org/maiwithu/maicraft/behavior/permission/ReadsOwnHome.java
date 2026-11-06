// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 圈出角色自己的家要读的几样：最近睡过的那张床、角色自己按图盖过的每一栋的范围。
 * 记住的"家"不在这里，保护判断从记过的地点里读。
 *
 * <p>只读接缝：生产实现由启动一侧拼好（床读世界记忆，盖过的读建筑档案），按一阵刷新一次的快照答，
 * 任何线程都能读；离线测试直接喂数值。
 */
public interface ReadsOwnHome {

    /** 最近一次睡过的那张床；一张都没睡过为空。 */
    Optional<WorldPosition> sleptBed();

    /** 角色自己按图盖过的每一栋（读现场建的档不算）；一栋都没有给空表。 */
    List<Building> ownBuildings();

    /**
     * 一栋盖过的房子在世界里占的范围（含两端）。
     *
     * @param name 建筑档案名，回报里说"在你盖的「…」里"用
     */
    record Building(String name, String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        /** 这一格在不在这一栋的范围外扩 margin 格以内：贴着墙外的台阶、屋檐下的箱子也算这栋的。 */
        boolean around(WorldPosition position, int margin) {
            boolean sameDimension = position.dimension() == null || dimension.equals(position.dimension());
            return sameDimension
                    && position.x() >= minX - margin && position.x() <= maxX + margin
                    && position.y() >= minY - margin && position.y() <= maxY + margin
                    && position.z() >= minZ - margin && position.z() <= maxZ + margin;
        }
    }

    /** 还没接上、或这个世界里还没有家时：没睡过床，也没盖过房子。 */
    ReadsOwnHome NOWHERE = new ReadsOwnHome() {
        @Override public Optional<WorldPosition> sleptBed() {
            return Optional.empty();
        }

        @Override public List<Building> ownBuildings() {
            return List.of();
        }
    };
}
