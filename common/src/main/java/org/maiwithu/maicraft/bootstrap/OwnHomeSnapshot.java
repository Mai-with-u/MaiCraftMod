// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.survival.beds.SleptBeds;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 圈出角色自己的家的一份快照：最近睡过的那张床（世界记忆）、她按图盖过的每一栋的范围（建筑档案，读现场建的不算）。
 *
 * <p>保护判断在后台线程上也会问，世界记忆和档案库只在客户端线程上读；档案库每读一次要把整栋蓝图解出来，
 * 所以客户端刻里每隔 {@link #REFRESH_MILLIS} 毫秒抄一次，问的时候读这份任何线程都能读的快照。
 */
final class OwnHomeSnapshot implements ReadsOwnHome {

    /** 多久重抄一次：刚睡过的床、刚盖好的房子几秒内就算进家里。 */
    static final long REFRESH_MILLIS = 5_000L;

    private final WorldMemory memory;
    private final BuildingArchives archives;
    private volatile Optional<WorldPosition> sleptBed = Optional.empty();
    private volatile List<Building> ownBuildings = List.of();
    private long refreshedAtMillis = Long.MIN_VALUE;

    /** 进世界时建好就先抄一次：刚进来就动手的任务也认得出家。 */
    OwnHomeSnapshot(WorldMemory memory, BuildingArchives archives, long nowMillis) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.archives = Objects.requireNonNull(archives, "archives");
        refresh(nowMillis);
    }

    /** 客户端刻里调：离上次抄过了 {@link #REFRESH_MILLIS} 才重抄。只在客户端线程调用。 */
    void refresh(long nowMillis) {
        if (refreshedAtMillis != Long.MIN_VALUE && nowMillis - refreshedAtMillis < REFRESH_MILLIS) return;
        refreshedAtMillis = nowMillis;
        sleptBed = SleptBeds.latest(memory);
        List<Building> buildings = new ArrayList<>();
        for (BuildingArchive archive : archives.all()) {
            // 读现场建的档可能是别人的房子：只认她按图盖的那些。
            if (archive.readFromSite()) continue;
            Blueprint.Bounds bounds = archive.bounds();
            buildings.add(new Building(archive.name(), archive.dimension(),
                    bounds.min().getX(), bounds.min().getY(), bounds.min().getZ(),
                    bounds.max().getX(), bounds.max().getY(), bounds.max().getZ()));
        }
        ownBuildings = List.copyOf(buildings);
    }

    @Override public Optional<WorldPosition> sleptBed() {
        return sleptBed;
    }

    @Override public List<Building> ownBuildings() {
        return ownBuildings;
    }
}
