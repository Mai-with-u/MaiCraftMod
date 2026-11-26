// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.behavior.construction.Blueprint;
import org.maiwithu.maicraft.behavior.construction.CellPlacements;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/**
 * 机器施工蓝图筹划：把机器蓝图按现场安装段状态换算成施工引擎要落的第一遍蓝图、装完安装段后的第二遍蓝图，
 * 以及拆整台的清空蓝图；纯安装段/部件/设置这类没有逐格方块的蓝图直接返回空，不构造空蓝图。
 */
final class MachineSitePlans {

    /** 第一遍施工筹划结果：要交给施工引擎落的蓝图（无格可落为空），以及要等安装段装完再放的格。 */
    record FirstPass(Optional<Blueprint> blueprint, List<PlannedCell> afterInstall) {
    }

    private MachineSitePlans() {
    }

    /**
     * 施工要落的第一遍蓝图，开工那一刻按现场定：安装段占的格里，蓝图写了方块的照常交给引擎（传送带两端的轴、
     * 中间要清空的格），没写的标成由机器放（引擎不放、不清）；已经装成的安装段整段交给机器，不让引擎把带子拆回轴。
     * 逐格清单为空（纯安装段/部件/设置，如纯 AE2 线缆铺设）时直接返回空蓝图，跳过逐格施工。
     */
    static FirstPass firstPass(MachineBlueprint blueprint, BlockPos anchor, String dimension,
            MachineServices services, CellPlacements placements) {
        if (blueprint.cells().isEmpty()) {
            return new FirstPass(Optional.empty(), List.of());
        }
        Blueprint laid = Blueprint.at(dimension, anchor, Rotation.NONE, blueprint.cells());
        Set<BlockPos> settled = installedCells(blueprint, anchor, services);
        Set<BlockPos> undeclared = installationCells(blueprint, anchor);
        Set<BlockPos> notInstalled = installationCells(blueprint, anchor);
        notInstalled.removeAll(settled);
        List<PlannedCell> held = new ArrayList<>();
        List<PlannedCell> adjusted = new ArrayList<>();
        for (PlannedCell cell : laid.cells()) {
            undeclared.remove(cell.pos());
            // 联动放置规则说要等的格落在还没装的安装段上（带漏斗等下面的带子）：这一遍不放，装完安装段再放。
            if (placements.waitsFor(cell).stream().anyMatch(notInstalled::contains)) {
                held.add(cell);
                continue;
            }
            adjusted.add(settled.contains(cell.pos()) ? cell.byMachine() : cell);
        }
        for (BlockPos left : undeclared) {
            // 蓝图没逐格声明的安装段格：照样标给机器，引擎不碰，核对时由机器认。
            adjusted.add(PlannedCell.air(left).byMachine());
        }
        Optional<Blueprint> plan = adjusted.isEmpty()
                ? Optional.empty() : Optional.of(new Blueprint(laid.dimension(), anchor, adjusted));
        return new FirstPass(plan, List.copyOf(held));
    }

    /** 第二遍施工的蓝图：压在安装段上的格，压着的段装成了才放；段没装成的照实记下不放，全都放不了为空。 */
    static Optional<Blueprint> afterInstallPlan(MachineBlueprint blueprint, BlockPos anchor, String dimension,
            List<PlannedCell> afterInstall, MachineServices services, CellPlacements placements,
            Consumer<BlockPos> skipped) {
        Set<BlockPos> notInstalled = installationCells(blueprint, anchor);
        notInstalled.removeAll(installedCells(blueprint, anchor, services));
        List<PlannedCell> ready = new ArrayList<>();
        for (PlannedCell cell : afterInstall) {
            if (placements.waitsFor(cell).stream().anyMatch(notInstalled::contains)) {
                skipped.accept(cell.pos());
            } else {
                ready.add(cell);
            }
        }
        return ready.isEmpty() ? Optional.empty() : Optional.of(new Blueprint(dimension, anchor, ready));
    }

    /**
     * 拆整台的施工蓝图：档案 cells 逐格换成清空，安装段只加上它自己放下的格（机械臂、ME 线缆），交给施工引擎挖。
     * 安装段连到的另一头不拆：实机拆一份只为拉链条的档案，把链条另一头主城竖轴顶上的链条输送机也拆了，主城断了动力。
     * 一格都没有可拆的方块时返回空，避免构造空蓝图崩溃。
     */
    static Optional<Blueprint> removalPlan(MachineBlueprint from, BlockPos anchor, String dimension,
            MachineServices services) {
        Set<BlockPos> declared = new LinkedHashSet<>();
        List<PlannedCell> cells = new ArrayList<>();
        for (PlannedCell cell : from.cells()) {
            cells.add(PlannedCell.air(anchor.offset(cell.pos())));
            declared.add(anchor.offset(cell.pos()));
        }
        for (MachineBlueprint.Segment segment : from.installations()) {
            for (BlockPos at : placedBy(absolute(segment, anchor), services)) {
                if (declared.add(at)) {
                    cells.add(PlannedCell.air(at));
                }
            }
        }
        return cells.isEmpty() ? Optional.empty() : Optional.of(new Blueprint(dimension, anchor, cells));
    }

    static Installation absolute(MachineBlueprint.Segment segment, BlockPos anchor) {
        return new Installation(segment.kind(), segment.offsets().stream().map(anchor::offset).toList(), segment.roles());
    }

    /** 此刻已经按要求装成的安装段占的格：这些格整段由机器管。 */
    private static Set<BlockPos> installedCells(MachineBlueprint blueprint, BlockPos anchor, MachineServices services) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (MachineBlueprint.Segment segment : blueprint.installations()) {
            if (services.installedBy(absolute(segment, anchor)).isPresent()) {
                segment.offsets().forEach(offset -> cells.add(anchor.offset(offset)));
            }
        }
        return cells;
    }

    private static Set<BlockPos> installationCells(MachineBlueprint blueprint, BlockPos anchor) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (MachineBlueprint.Segment segment : blueprint.installations()) {
            for (BlockPos offset : segment.offsets()) {
                cells.add(anchor.offset(offset));
            }
        }
        return cells;
    }

    // 问遍机器类型，认这种安装段的第一家说这一段自己放了哪些格；都不认（模组停用了）就一格不拆，宁可留下也不拆错。
    private static List<BlockPos> placedBy(Installation installation, MachineServices services) {
        for (MachineType type : services.machineTypes()) {
            Optional<List<BlockPos>> placed = type.installations().orElse(null) == null
                    ? Optional.empty() : type.installations().get().placedBy(installation);
            if (placed.isPresent()) return placed.get();
        }
        return List.of();
    }
}
