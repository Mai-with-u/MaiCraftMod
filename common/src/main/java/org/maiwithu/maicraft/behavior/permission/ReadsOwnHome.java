// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 圈出角色自己的家要读的几样：最近真睡过的那张床、角色自己按图盖过的每一栋里她真放下过的格。
 * 记住的"家"不在这里，保护判断从记过的地点里读。
 *
 * <p>只读接缝：生产实现由启动一侧拼好（床读世界记忆，盖过的读建筑档案），按一阵刷新一次的快照答，
 * 任何线程都能读；离线测试直接喂数值。
 */
public interface ReadsOwnHome {

    /** 最近一次真睡下过的那张床；只放下没睡过的不算，一张都没睡过为空。 */
    Optional<WorldPosition> sleptBed();

    /** 角色自己按图盖过、记下了真放下过哪几格的每一栋（读现场建的档不算）；一栋都没有给空表。 */
    List<Building> ownBuildings();

    /**
     * 一栋盖过的房子里，她施工时确认真放下过方块的那些格。
     * 不用整张蓝图：图上画了、她没放成的格（被保护挡下、别人本来就盖着的）不算她的。
     *
     * @param name   建筑档案名，回报里说"在你盖的「…」里"用
     * @param placed 真放下过的格（世界坐标）
     */
    record Building(String name, String dimension, Set<BlockPos> placed) {

        public Building {
            placed = Set.copyOf(placed);
        }

        /** 这一格离她真放下过的哪一格不超过 margin 格（三个方向都不超过）：屋里摆的箱子、贴着墙外的台阶也算这栋的。 */
        boolean around(WorldPosition position, int margin) {
            // 两边维度都写了、而且是同一个才算：维度说不清的格子不认成家。
            if (dimension == null || !dimension.equals(position.dimension()) || placed.isEmpty()) return false;
            for (int dx = -margin; dx <= margin; dx++) {
                for (int dy = -margin; dy <= margin; dy++) {
                    for (int dz = -margin; dz <= margin; dz++) {
                        if (placed.contains(new BlockPos(position.x() + dx, position.y() + dy, position.z() + dz))) return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * 这一格是不是建筑档案里某一栋图上的格（她按图盖的、读现场建的都算）：是就给那一栋的名字。
     * 自动挑取材点时跳过这些格，不拆房子当材料；没接上时一格都不是。
     */
    default Optional<String> archivedBuildingAt(WorldPosition position) {
        return Optional.empty();
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
