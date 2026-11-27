// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.game.serverlink.ServerReads;

/**
 * AE2 扩展（ExtendedAE）客户端联动入口：统一向联动登记表贡献语义界面布局证明、
 * 全部核心加工机与扩展网络设备/多方块机器类型、服务端只读操作 {@code extendedae.device}（版本 1）
 * 与中文知识页。所有模组读写均经 {@link #call} 包装，装入不兼容版本时自动停用不让游戏崩溃。
 */
public final class ExtendedAeCompat extends CompatModule {

    public static final String MOD_ID = "extendedae";
    public static final String DEVICE_OPERATION = ExtendedAeDeviceReadings.DEVICE_OPERATION;

    private final ExtendedAeReads reads;

    public ExtendedAeCompat(ExtendedAeReads reads) {
        super(MOD_ID, "AE2 扩展");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public void contribute(CompatRegistry registry) {
        registry.menuLayout(this, new ExtendedAeSemanticsLayout(this));
        for (ExtendedAeMachineCatalog.Kind kind : ExtendedAeMachineCatalog.kinds()) {
            registry.machineType(this, services -> {
                ExtendedAeDeviceReadings deviceReadings = new ExtendedAeDeviceReadings(
                        services == null ? ServerReads.NONE : services.serverReads());
                Map<String, ExtendedAeDeviceOps> opsById = createDeviceOps(deviceReadings);
                return new ExtendedAeMachineType(kind, this, services, blocksOf(kind.blocks()),
                        opsById.get(kind.id()));
            });
        }
        registry.serverRead(this, DEVICE_OPERATION, ExtendedAeDeviceReadings.DEVICE_OPERATION_VERSION);
        registry.knowledgeSource(this, new ExtendedAeKnowledge());
    }

    private Map<String, ExtendedAeDeviceOps> createDeviceOps(ExtendedAeDeviceReadings deviceReadings) {
        Map<String, ExtendedAeDeviceOps> map = new LinkedHashMap<>();
        map.put(ExtendedAeMachineCatalog.CRYSTAL_ASSEMBLER, new CrystalAssemblerOps(this, deviceReadings));
        map.put(ExtendedAeMachineCatalog.CIRCUIT_CUTTER, new CircuitCutterOps(this, deviceReadings));
        map.put(ExtendedAeMachineCatalog.EX_INSCRIBER, new ExInscriberOps(this, deviceReadings));
        map.put(ExtendedAeMachineCatalog.EX_CHARGER, new ExChargerOps(this));
        map.put(ExtendedAeMachineCatalog.CRYSTAL_FIXER, new CrystalFixerOps(this));
        map.put(ExtendedAeMachineCatalog.EX_PATTERN_PROVIDER,
                new ExtendedAeNetworkOps.PatternProviderOps(this, deviceReadings, null));
        map.put(ExtendedAeMachineCatalog.EX_DRIVE, new ExtendedAeNetworkOps.DriveOps(this));
        map.put(ExtendedAeMachineCatalog.EX_INTERFACE,
                new ExtendedAeNetworkOps.InterfaceOps(this, deviceReadings, false));
        map.put(ExtendedAeMachineCatalog.OVERSIZE_INTERFACE,
                new ExtendedAeNetworkOps.InterfaceOps(this, deviceReadings, true));
        map.put(ExtendedAeMachineCatalog.EX_MOLECULAR_ASSEMBLER,
                new ExtendedAeNetworkOps.MolecularAssemblerOps(this, deviceReadings, null));
        map.put(ExtendedAeMachineCatalog.EX_IO_TRANSFER,
                new ExtendedAeNetworkOps.ExCellTransferOps(this, deviceReadings, null));
        map.put(ExtendedAeMachineCatalog.INGREDIENT_BUFFER,
                new ExtendedAeFacilityOps.IngredientBufferOps(deviceReadings));
        map.put(ExtendedAeMachineCatalog.CANER, new ExtendedAeFacilityOps.CanerOps(this, deviceReadings, null));
        map.put(ExtendedAeMachineCatalog.WIRELESS_CONNECT,
                new ExtendedAeFacilityOps.WirelessOps(this, deviceReadings, false, null));
        map.put(ExtendedAeMachineCatalog.WIRELESS_HUB,
                new ExtendedAeFacilityOps.WirelessOps(this, deviceReadings, true, null));
        map.put(ExtendedAeMachineCatalog.ASSEMBLER_MATRIX,
                new ExtendedAeFacilityOps.AssemblerMatrixOps(this, deviceReadings, null));
        return map;
    }

    static Set<Block> blocksOf(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }

    public boolean isMenuOpen() {
        return call("检查 ExtendedAE 界面是否打开", reads::isMenuOpen);
    }

    public List<Integer> enabledMenuSlotsOf(String semanticId) {
        return call("按语义查 ExtendedAE 启用槽位", () -> reads.enabledSlotsOf(semanticId));
    }

    public List<Integer> allMenuSlotsOf(String semanticId) {
        return call("按语义查 ExtendedAE 全部槽位", () -> reads.allSlotsOf(semanticId));
    }

    public boolean sendConfigButton(String settingName, boolean backwards) {
        return call("发送 AE2 配置按钮包", () -> reads.sendConfigButton(settingName, backwards));
    }

    public boolean sendGenericAction(String action, Object... args) {
        return call("发送 ExtendedAE 通用动作包", () -> reads.sendGenericAction(action, args));
    }

    public boolean sendUpdatePage(int page) {
        return call("发送 ExtendedAE 切页包", () -> reads.sendUpdatePage(page));
    }

    public boolean sendEmptyItemToTank(int tankSlotIndex) {
        return call("发送储罐倒桶包", () -> reads.sendEmptyItemToTank(tankSlotIndex));
    }

    public boolean openSubMenu(String menuTypeId) {
        return call("打开 AE2 子界面", () -> reads.openSubMenu(menuTypeId));
    }

    public boolean returnToParentMenu() {
        return call("返回 AE2 上级界面", reads::returnToParentMenu);
    }

    public Optional<Integer> openPriorityMenu() {
        return call("读优先级子界面数值", reads::openPriorityMenu);
    }

    public boolean sendSetPriority(int priority) {
        return call("写入优先级子界面数值", () -> reads.sendSetPriority(priority));
    }

    public Optional<ExtendedAeReads.CrystalAssemblerView> crystalAssemblerAt(ClientLevel level, BlockPos at) {
        return call("读水晶组装器状态", () -> reads.crystalAssemblerAt(level, at));
    }

    public Optional<ExtendedAeReads.CircuitCutterView> circuitCutterAt(ClientLevel level, BlockPos at) {
        return call("读电路切片器状态", () -> reads.circuitCutterAt(level, at));
    }

    public Optional<ExtendedAeReads.ExInscriberView> exInscriberAt(ClientLevel level, BlockPos at) {
        return call("读扩展压印器状态", () -> reads.exInscriberAt(level, at));
    }

    public Optional<ExtendedAeReads.ExChargerView> exChargerAt(ClientLevel level, BlockPos at) {
        return call("读扩展充能器状态", () -> reads.exChargerAt(level, at));
    }

    public Optional<ExtendedAeReads.ChargerRecipeView> chargerRecipeForOutput(ClientLevel level, String outputItemId) {
        return call("查充能配方", () -> reads.chargerRecipeForOutput(level, outputItemId));
    }

    public Optional<ExtendedAeReads.ItemPowerView> itemPower(ItemStack stack) {
        return call("读带电物品电量", () -> reads.itemPower(stack));
    }

    public Optional<ExtendedAeReads.CrystalFixerView> crystalFixerAt(ClientLevel level, BlockPos at) {
        return call("读水晶修复器状态", () -> reads.crystalFixerAt(level, at));
    }

    public Optional<ExtendedAeReads.ExDriveView> exDriveAt(ClientLevel level, BlockPos at) {
        return call("读 ME 扩展驱动器槽位", () -> reads.exDriveAt(level, at));
    }

    public Optional<ExtendedAeReads.NetworkDeviceView> exMolecularAssemblerAt(ClientLevel level, BlockPos at) {
        return call("读扩展分子组装室状态", () -> reads.exMolecularAssemblerAt(level, at));
    }

    public Optional<ExtendedAeReads.CanerView> canerAt(ClientLevel level, BlockPos at) {
        return call("读 ME 装罐机状态", () -> reads.canerAt(level, at));
    }

    public Optional<ExtendedAeReads.MatrixView> matrixBlockAt(ClientLevel level, BlockPos at) {
        return call("读组装矩阵方块状态", () -> reads.matrixBlockAt(level, at));
    }

    public Optional<ExtendedAeReads.NetworkDeviceView> networkDeviceAt(ClientLevel level, BlockPos at) {
        return call("读扩展 ME 网络设备状态", () -> reads.networkDeviceAt(level, at));
    }

    public Optional<ExtendedAeReads.CrystalAssemblerMenuView> openCrystalAssemblerMenu() {
        return call("读水晶组装器界面状态", reads::openCrystalAssemblerMenu);
    }

    public Optional<ExtendedAeReads.CircuitCutterMenuView> openCircuitCutterMenu() {
        return call("读电路切片器界面状态", reads::openCircuitCutterMenu);
    }

    public Optional<ExtendedAeReads.ExInscriberMenuView> openExInscriberMenu() {
        return call("读扩展压印器界面状态", reads::openExInscriberMenu);
    }

    public Optional<ExtendedAeReads.PatternProviderMenuView> openPatternProviderMenu() {
        return call("读扩展样板供应器界面状态", reads::openPatternProviderMenu);
    }

    public Optional<ExtendedAeReads.CellTransferMenuView> openExCellTransferMenu() {
        return call("读 ME 扩展 IO 传输站界面状态", reads::openExCellTransferMenu);
    }

    public Optional<ExtendedAeReads.CanerMenuView> openCanerMenu() {
        return call("读 ME 装罐机界面状态", reads::openCanerMenu);
    }

    public Optional<ExtendedAeReads.WirelessHubMenuView> openWirelessHubMenu() {
        return call("读 ME 无线集线器界面状态", reads::openWirelessHubMenu);
    }

    public boolean isAssemblerMatrixMenuOpen() {
        return call("检查组装矩阵界面是否打开", reads::isAssemblerMatrixMenuOpen);
    }
}
