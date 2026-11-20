// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.compat.ae2.Ae2MachineCatalog;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;

/**
 * AE2 工作方块与完整自动合成闭环的对外统一汇编入口：
 * 供最终汇总接线一次性取走增补设备种类、各类方块设备操作委托、三大自动合成线缆部件委托、
 * 本组独占的语义界面布局规则、木质曲柄放置规则，以及合成终端 3×3 手搓合成来源。
 */
public final class Ae2Machines {

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache = new Ae2MachineCache();
    private final PatternProviderOps patternProviderOps;

    public Ae2Machines(Ae2Compat compat, Ae2MachineReads reads) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.patternProviderOps = new PatternProviderOps(compat, reads, cache);
    }

    /** 本组增补的 3 种 AE2 设备目录项：木质曲柄、陨石储罐、水晶催生器。 */
    public List<Ae2MachineCatalog.Kind> extraKinds() {
        return List.of(
                Ae2MachineCatalog.of("crank", "木质曲柄", MachineRole.CONTROL, false),
                Ae2MachineCatalog.of("sky_stone_tank", "陨石储罐", MachineRole.STORAGE, false),
                Ae2MachineCatalog.of("growth_accelerator", "水晶催生器", MachineRole.PROCESSING, true));
    }

    /** 全部工作方块与自动合成方块设备的操作委托映射（按设备注册 ID 路由）。 */
    public Map<String, Ae2DeviceOps> deviceOps() {
        Map<String, Ae2DeviceOps> ops = new LinkedHashMap<>();
        ops.put("ae2:charger", new ChargerDeviceOps(reads));
        ops.put("ae2:crank", new ChargerDeviceOps.CrankDeviceOps(reads));
        ops.put("ae2:vibration_chamber", new VibrationChamberOps(compat, reads, cache));
        ops.put("ae2:condenser", new CondenserOps(compat, reads, cache));
        ops.put("ae2:chest", new MeChestOps(compat, reads));
        ops.put("ae2:sky_stone_tank", new SkyStoneTankOps(reads));
        ops.put("ae2:growth_accelerator", new SkyStoneTankOps.GrowthAcceleratorOps(reads));
        ops.put("ae2:pattern_provider", patternProviderOps);
        ops.put("ae2:molecular_assembler", new MolecularAssemblerOps(compat, reads, cache));
        ops.put("ae2:inscriber", new InscriberSettingsOps(compat, reads, cache));
        ops.put("ae2:crafting_unit", new CraftingCpuOps(compat, reads, cache));
        return Map.copyOf(ops);
    }

    /** 线缆格三大自动合成部件的操作委托（样板编码终端、样板管理终端、线缆版样板供应器）。 */
    public Ae2DeviceOps cablePartOps() {
        return new CableMachinePartOps(compat, reads, cache, patternProviderOps);
    }

    /**
     * 本组独占且包含完整 36 格角色背包的 6 个 AE2 菜单语义布局规则：
     * 绝不与既有 6 个布局证明撞 ID，也不包含 ae2:priority / ae2:craftingcpu / ae2:craftingstatus 等无 36 格背包子菜单。
     */
    public Map<String, Ae2SemanticsLayout.Rule> layoutRules() {
        Map<String, Ae2SemanticsLayout.Rule> rules = new LinkedHashMap<>();
        // 振动室（VibrationChamberMenu）：1 个燃料输入槽 + 3 个升级槽，排除网络工具箱 9 槽。
        rules.put("ae2:vibrationchamber", new Ae2SemanticsLayout.Rule(
                List.of("MACHINE_INPUT", "UPGRADE"), Set.of("MACHINE_INPUT")));
        // 物质聚合器（CondenserMenu）：1 个垃圾输入槽 + 1 个输出槽 + 1 个存储组件槽。
        rules.put("ae2:condenser", new Ae2SemanticsLayout.Rule(
                List.of("MACHINE_INPUT", "MACHINE_OUTPUT", "STORAGE_CELL"),
                Set.of("MACHINE_INPUT", "MACHINE_OUTPUT")));
        // ME 箱子换盘界面（MEChestMenu）：1 个存储元件槽。
        rules.put("ae2:me_chest", new Ae2SemanticsLayout.Rule(
                List.of("STORAGE_CELL"), Set.of()));
        // ME 箱子顶面存储界面（BasicCellChestMenu，继承 MEStorageMenu）：纯终端存取走 MEInteractionPacket。
        rules.put("ae2:basic_cell_chest", Ae2SemanticsLayout.Rule.configOnly());
        // 分子组装室（MolecularAssemblerMenu）：9 个合成格 + 1 个输出槽 + 1 个单机样板槽 + 5 个加速卡槽。
        rules.put("ae2:molecular_assembler", new Ae2SemanticsLayout.Rule(
                List.of("MACHINE_CRAFTING_GRID", "MACHINE_OUTPUT", "ENCODED_PATTERN", "UPGRADE"),
                Set.of("MACHINE_CRAFTING_GRID", "MACHINE_OUTPUT")));
        // 样板管理终端（PatternAccessTermMenu）：仅 36 格玩家槽，远端样板槽经 QuickMovePatternPacket 交互。
        rules.put("ae2:patternaccessterminal", Ae2SemanticsLayout.Rule.configOnly());
        return Map.copyOf(rules);
    }

    /** 木质曲柄的放置前置依赖与有效附着面规则。 */
    public List<PlacementRules> placementRules() {
        return List.of(new CrankPlacementRule(reads));
    }

    /** 合成终端与无线合成终端 3×3 手搓合成来源。 */
    public List<ItemSource> itemSources(PlayerServices services) {
        return List.of(new CraftingTermSource(compat, reads, services));
    }
}
