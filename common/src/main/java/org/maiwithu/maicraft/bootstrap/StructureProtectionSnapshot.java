// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.behavior.construction.CellKind;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.permission.ReadsStructureProtection;
import org.maiwithu.maicraft.behavior.permission.StructureProtection;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 建筑档案与在役世界机器档案的结构保护区快照：客户端刻里按修订记号懒刷新，供后台寻路线程与客户端取材路径无锁读取。
 *
 * <p>保护范围：
 * <ul>
 * <li>已登记建筑档案：未退役（{@code !removed}）且非空气格多于 1 格（过滤历史上误存的单格残档）；
 *     非空气格进入方块与流体保护，外框范围额外进入流体保护（防止从建筑内的水池、农田或喷泉舀水）。</li>
 * <li>在役世界机器档案：未拆除且当前在世界里（非物理结构地块上），全部非空气格（含流体源格）进入方块与流体保护。</li>
 * </ul>
 */
final class StructureProtectionSnapshot implements ReadsStructureProtection {

    /** 建筑档案至少需要这么多非空气格才生效，过滤历史上误存的单格残档。 */
    static final int MIN_VALID_BUILDING_CELLS = 2;
    /** 客户端刻里每隔这么久核一次档案修订记号，确保新建档或退役后迅速生效。 */
    static final long REFRESH_MILLIS = 1_000L;

    private final BuildingArchives buildingArchives;
    private final ArchivedMachines machineArchives;
    private volatile Snapshot current = Snapshot.EMPTY;
    private long refreshedAtMillis = Long.MIN_VALUE;
    private String lastBuildingRevision;
    private String lastMachineRevision;

    private record BuildingBounds(String name, String dimension,
                                  int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(WorldPosition pos) {
            return (pos.dimension() == null || dimension.equals(pos.dimension()))
                    && pos.x() >= minX && pos.x() <= maxX
                    && pos.y() >= minY && pos.y() <= maxY
                    && pos.z() >= minZ && pos.z() <= maxZ;
        }
    }

    private record Snapshot(
            Map<String, Map<BlockPos, StructureProtection>> protectedBlocks,
            List<BuildingBounds> buildingBounds) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());
    }

    StructureProtectionSnapshot(BuildingArchives buildingArchives, ArchivedMachines machineArchives, long nowMillis) {
        this.buildingArchives = Objects.requireNonNull(buildingArchives, "buildingArchives");
        this.machineArchives = Objects.requireNonNull(machineArchives, "machineArchives");
        refresh(nowMillis);
    }

    /** 客户端刻调用：节流检查两个档案库的修订记号，任一有变时整份重抄为不可变快照。 */
    void refresh(long nowMillis) {
        if (refreshedAtMillis != Long.MIN_VALUE && nowMillis - refreshedAtMillis < REFRESH_MILLIS) return;
        refreshedAtMillis = nowMillis;
        String buildingRev = buildingArchives.revision();
        String machineRev = machineArchives.revision();
        if (buildingRev.equals(lastBuildingRevision) && machineRev.equals(lastMachineRevision)) return;
        lastBuildingRevision = buildingRev;
        lastMachineRevision = machineRev;
        rebuild();
    }

    // 重建整份不可变快照：先录在役机器（优先级更高，机器常摆在建筑内），再录在役有效建筑及其外框。
    private void rebuild() {
        Map<String, Map<BlockPos, StructureProtection>> byDim = new HashMap<>();
        List<BuildingBounds> bounds = new ArrayList<>();

        for (ArchivedMachines.ActiveMachine machine : machineArchives.activeInWorld()) {
            Map<BlockPos, StructureProtection> inDim = byDim.computeIfAbsent(machine.dimension(), d -> new HashMap<>());
            StructureProtection tag = StructureProtection.machine(machine.name());
            for (BlockPos cell : machine.cells()) {
                inDim.putIfAbsent(cell.immutable(), tag);
            }
        }

        for (BuildingArchive archive : buildingArchives.all()) {
            // 已退役建筑或历史单格残档不纳入结构保护区。
            if (archive.removed() || archive.nonAirCellCount() < MIN_VALID_BUILDING_CELLS) continue;
            Map<BlockPos, StructureProtection> inDim = byDim.computeIfAbsent(archive.dimension(), d -> new HashMap<>());
            StructureProtection tag = StructureProtection.building(archive.name());
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            boolean hasNonAir = false;
            for (PlannedCell cell : archive.laid().cells()) {
                if (cell.kind() == CellKind.AIR) continue;
                BlockPos pos = cell.pos().immutable();
                inDim.putIfAbsent(pos, tag);
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
                hasNonAir = true;
            }
            if (hasNonAir) {
                bounds.add(new BuildingBounds(archive.name(), archive.dimension(), minX, minY, minZ, maxX, maxY, maxZ));
            }
        }

        Map<String, Map<BlockPos, StructureProtection>> frozen = new HashMap<>();
        byDim.forEach((dim, map) -> frozen.put(dim, Map.copyOf(map)));
        current = new Snapshot(Map.copyOf(frozen), List.copyOf(bounds));
    }

    @Override
    public StructureProtection blockAt(WorldPosition position) {
        if (position == null) return StructureProtection.NONE;
        return blockAt(current, position);
    }

    // 在同一份不可变快照内查找方块级结构保护（在役机器优先于建筑档案）。
    private static StructureProtection blockAt(Snapshot snapshot, WorldPosition position) {
        BlockPos cell = new BlockPos(position.x(), position.y(), position.z());
        if (position.dimension() == null) {
            for (Map<BlockPos, StructureProtection> inDim : snapshot.protectedBlocks().values()) {
                StructureProtection hit = inDim.get(cell);
                if (hit != null) return hit;
            }
            return StructureProtection.NONE;
        }
        Map<BlockPos, StructureProtection> inDim = snapshot.protectedBlocks().get(position.dimension());
        if (inDim == null) return StructureProtection.NONE;
        return inDim.getOrDefault(cell, StructureProtection.NONE);
    }

    @Override
    public StructureProtection fluidAt(WorldPosition position) {
        if (position == null) return StructureProtection.NONE;
        // 单次原子读取当前快照，确保块格检查与建筑外框检查不会跨越并发快照切换。
        Snapshot snapshot = current;
        StructureProtection direct = blockAt(snapshot, position);
        if (direct.protects()) return direct;
        // 建筑外框内的流体（水池、室内喷泉、建筑内农田水）一并受该建筑保护，防止自动舀水抽干。
        for (BuildingBounds bound : snapshot.buildingBounds()) {
            if (bound.contains(position)) {
                return StructureProtection.building(bound.name());
            }
        }
        return StructureProtection.NONE;
    }
}
