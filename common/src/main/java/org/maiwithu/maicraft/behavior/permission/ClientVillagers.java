// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 村民读取的生产实现：在角色所在的客户端世界里找这一格周围的村民（{@link Villager}，流浪商人不算）。
 * 只在客户端线程上问（收回来源问价、动手前核对都在客户端刻里）；位置写着别的维度、或读不到世界时按没看见算。
 */
public final class ClientVillagers implements SeesVillagers {

    /** 角色此刻所在的世界；还没进世界、人接管中给空。 */
    private final Supplier<Level> level;

    public ClientVillagers(Supplier<Level> level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    // 以这一格为中心、半径 radius 的方框里有没有村民：村民夜里睡在床上、白天在村里转，床在村里就看得见。
    @Override
    public boolean villagersNear(WorldPosition position, double radius) {
        Level current = level.get();
        if (current == null) return false;
        if (position.dimension() != null
                && !position.dimension().equals(current.dimension().location().toString())) {
            return false;
        }
        AABB around = new AABB(position.x() - radius, position.y() - radius, position.z() - radius,
                position.x() + 1 + radius, position.y() + 1 + radius, position.z() + 1 + radius);
        return !current.getEntitiesOfClass(Villager.class, around).isEmpty();
    }
}
