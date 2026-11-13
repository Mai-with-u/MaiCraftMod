// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.Map;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 营地范围判断：回答一格地面是不是在床、箱子、工作台或记住的家与营地附近。
 *
 * <p>玩家取材不会在营地的地面往下掏坑，也不会紧挨着营地箱子往下挖露天楼梯，免得大家进出总掉坑里；
 * 往下挖楼梯时若站在营地范围内，先走到营地外的天然地面再开挖。只读接缝，纯判断可离线测试。
 */
@FunctionalInterface
public interface Campground {

    /** 营地设施与营地地标周边的保护半径（格）：床、工作站、开过的储物箱周围十格内不在平地往下挖。 */
    double RADIUS_BLOCKS = 10.0;

    /** 没接世界记忆时（离线替身）到处都不算营地。 */
    Campground NOWHERE = position -> false;

    /** 这一格是不是落在营地范围内。 */
    boolean covers(WorldPosition position);

    /**
     * 组合自家范围与世界记忆里的营地线索：自家、名字叫家或营地或基地的地点、工作站、建筑、机器以及用过的容器周围都算营地；
     * 路过瞥见但没打开过的野外遗迹箱子不算营地，免得在矿井或遗迹里不敢挖矿。
     */
    static Campground of(PermissionCheck permission, WorldMemory memory) {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(memory, "memory");
        return position -> {
            if (position == null || position.dimension() == null) return false;
            if (permission.atOwnHome(position)) return true;
            for (Map.Entry<String, WorldPosition> entry : memory.places().entrySet()) {
                if (isCampName(entry.getKey()) && near(entry.getValue(), position)) {
                    return true;
                }
            }
            for (MemoryRecord record : memory.recordsNear(position, RADIUS_BLOCKS)) {
                if (isCampFacility(record)) return true;
            }
            return false;
        };
    }

    private static boolean isCampName(String name) {
        if (name == null) return false;
        return name.equals("家") || name.equals("营地") || name.equals("基地")
                || name.contains("营地") || name.contains("基地");
    }

    private static boolean isCampFacility(MemoryRecord record) {
        MemoryKind kind = record.kind();
        if (kind == MemoryKind.WORKSTATION || kind == MemoryKind.BUILDING || kind == MemoryKind.MACHINE) {
            return true;
        }
        return kind == MemoryKind.CONTAINER && record.origin() == MemoryOrigin.USED;
    }

    private static boolean near(WorldPosition center, WorldPosition target) {
        if (center.dimension() == null || !center.dimension().equals(target.dimension())) return false;
        double dx = center.x() - target.x();
        double dy = center.y() - target.y();
        double dz = center.z() - target.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= RADIUS_BLOCKS;
    }
}
