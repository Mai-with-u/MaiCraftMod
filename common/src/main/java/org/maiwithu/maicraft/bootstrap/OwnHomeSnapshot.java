// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.survival.beds.SleptBeds;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 圈出角色自己的家的一份快照：最近真睡过的那张床（世界记忆）、她按图盖过的每一栋里真放下过的格
 * （建筑档案，读现场建的不算）。
 *
 * <p>保护判断在后台线程上也会问，世界记忆和档案库只在客户端线程上读，所以客户端刻里每隔 {@link #REFRESH_MILLIS}
 * 毫秒抄一次，问的时候读这份任何线程都能读的快照。档案库每读一栋要把整张蓝图解出来，只在档案库的记号变了才重读。
 */
final class OwnHomeSnapshot implements ReadsOwnHome {

    /** 多久重抄一次：刚睡过的床、刚盖好的房子几秒内就算进家里。 */
    static final long REFRESH_MILLIS = 5_000L;

    private final WorldMemory memory;
    private final BuildingArchives archives;
    private volatile Optional<WorldPosition> sleptBed = Optional.empty();
    private volatile List<Building> ownBuildings = List.of();
    /** 建筑档案里每一栋图上的格：维度 → 格 → 那一栋的名字。自动挑取材点时跳过这些格。 */
    private volatile Map<String, Map<BlockPos, String>> archivedCells = Map.of();
    private long refreshedAtMillis = Long.MIN_VALUE;
    /** 上次重读各栋时档案库的记号：没变就不再解码。 */
    private String archivesRevision;

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
        // 床几秒一抄：刚睡下的床马上算进家里。
        sleptBed = SleptBeds.latestReallySleptIn(memory);
        // 房子只在档案存过之后重读：盖完、改建、读现场建档都会换记号。
        String revision = archives.revision();
        if (revision.equals(archivesRevision)) return;
        archivesRevision = revision;
        List<Building> buildings = new ArrayList<>();
        Map<String, Map<BlockPos, String>> cells = new HashMap<>();
        for (BuildingArchive archive : archives.all()) {
            // 取材时不拆房子：每一栋图上的格都记下，读现场建的也算（多半是别人的房子，更不能拆）。
            Map<BlockPos, String> inDimension = cells.computeIfAbsent(archive.dimension(), dimension -> new HashMap<>());
            for (PlannedCell cell : archive.laid().cells()) inDimension.putIfAbsent(cell.pos().immutable(), archive.name());
            // 读现场建的档可能是别人的房子；没记下真放下过哪几格的旧档也不认：宁可认小。
            if (archive.readFromSite() || archive.placedCells().isEmpty()) continue;
            buildings.add(new Building(archive.name(), archive.dimension(), Set.copyOf(archive.placedCells())));
        }
        ownBuildings = List.copyOf(buildings);
        archivedCells = cells;
    }

    @Override public Optional<WorldPosition> sleptBed() {
        return sleptBed;
    }

    @Override public List<Building> ownBuildings() {
        return ownBuildings;
    }

    // 维度说不清时哪个维度的房子上有这一格都算：取材时宁可不挖。
    @Override public Optional<String> archivedBuildingAt(WorldPosition position) {
        BlockPos cell = new BlockPos(position.x(), position.y(), position.z());
        if (position.dimension() == null) {
            return archivedCells.values().stream().map(cells -> cells.get(cell)).filter(name -> name != null).findFirst();
        }
        Map<BlockPos, String> inDimension = archivedCells.get(position.dimension());
        return inDimension == null ? Optional.empty() : Optional.ofNullable(inDimension.get(cell));
    }
}
