// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * AE2 扩展客户端读写与发包接缝：在 NeoForge 模块零反射调用 ExtendedAE 与 AE2 公开接口实现，
 * common 模块与离线测试仅依赖此接口与不可变快照，彻底隔离模组类加载。
 */
public interface ExtendedAeReads {

    /** 无线集线器固定通道总数。F 游戏事实。 */
    int WIRELESS_HUB_SLOTS = 8;

    /** 无读写端时的空实现：所有读数给空，所有发包返回假。 */
    ExtendedAeReads NONE = new ExtendedAeReads() {};

    /** 当前角色是否正打开着 AE2 或 ExtendedAE 菜单。 */
    default boolean isMenuOpen() { return false; }

    /**
     * 当前打开菜单中属于指定语义且处于启用状态（{@code isSlotEnabled() == true}）的槽位索引列表。
     * 分页界面仅返回当前启用页的槽位，避免普通搬运点到隐藏页。
     */
    default List<Integer> enabledSlotsOf(String semanticId) { return List.of(); }

    /**
     * 当前打开菜单中属于指定语义的全部槽位索引（不区分启用态），用于安全排除工具箱、过滤槽与储罐槽。
     */
    default List<Integer> allSlotsOf(String semanticId) { return List.of(); }

    /** 发送 AE2 原生配置按钮切换包（{@code ConfigButtonPacket}）。 */
    default boolean sendConfigButton(String settingName, boolean backwards) { return false; }

    /** 发送 ExtendedAE 通用界面动作包（{@code CEAEGenericPacket}）。 */
    default boolean sendGenericAction(String action, Object... args) { return false; }

    /** 发送 ExtendedAE 分页切换包（{@code CUpdatePage}）。 */
    default boolean sendUpdatePage(int page) { return false; }

    /** 发送 AE2 原生 {@code InventoryActionPacket(EMPTY_ITEM, tankSlotIndex, 0)} 将光标满桶倒入储罐槽。 */
    default boolean sendEmptyItemToTank(int tankSlotIndex) { return false; }

    /** 打开指定注册 ID 的 AE2 子界面（例如 {@code ae2:priority}）。 */
    default boolean openSubMenu(String menuTypeId) { return false; }

    /** 从当前 AE2 子界面返回上一级主界面。 */
    default boolean returnToParentMenu() { return false; }

    /** 读取当前打开的优先级子界面里的优先级数值。 */
    default Optional<Integer> openPriorityMenu() { return Optional.empty(); }

    /** 向当前打开的优先级子界面提交新的优先级整数。 */
    default boolean sendSetPriority(int priority) { return false; }

    /** 读取水晶组装器客户端同步状态。 */
    default Optional<CrystalAssemblerView> crystalAssemblerAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取电路切片器客户端同步状态。 */
    default Optional<CircuitCutterView> circuitCutterAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取扩展压印器客户端同步状态（含 4 页槽位、工作态与堆叠上限）。 */
    default Optional<ExInscriberView> exInscriberAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取扩展充能器客户端同步状态（含工作态与 4 槽物品/电量）。 */
    default Optional<ExChargerView> exChargerAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 查询产出指定物品的 AE2 充能配方。 */
    default Optional<ChargerRecipeView> chargerRecipeForOutput(ClientLevel level, String outputItemId) {
        return Optional.empty();
    }

    /** 读取一件带电物品的当前与最大 AE 电量；非 AE2 带电物品返回空。 */
    default Optional<ItemPowerView> itemPower(ItemStack stack) { return Optional.empty(); }

    /** 读取水晶修复器客户端同步状态（含朝向与槽 0 燃料）。 */
    default Optional<CrystalFixerView> crystalFixerAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取 ME 扩展驱动器客户端同步的 20 槽磁盘与在线状态。 */
    default Optional<ExDriveView> exDriveAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取扩展分子组装室客户端同步的供电与在线状态。 */
    default Optional<NetworkDeviceView> exMolecularAssemblerAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取 ME 装罐机客户端同步的朝向、电网状态与工作模式。 */
    default Optional<CanerView> canerAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取组装矩阵多方块任一构成块的成型、供电与在线状态。 */
    default Optional<MatrixView> matrixBlockAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取任意扩展 ME 网络设备的通用供电与在线状态。 */
    default Optional<NetworkDeviceView> networkDeviceAt(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /** 读取当前打开的水晶组装器菜单实时状态（含储罐内容与输出面）。 */
    default Optional<CrystalAssemblerMenuView> openCrystalAssemblerMenu() { return Optional.empty(); }

    /** 读取当前打开的电路切片器菜单实时状态。 */
    default Optional<CircuitCutterMenuView> openCircuitCutterMenu() { return Optional.empty(); }

    /** 读取当前打开的扩展压印器菜单实时状态。 */
    default Optional<ExInscriberMenuView> openExInscriberMenu() { return Optional.empty(); }

    /** 读取当前打开的扩展样板供应器菜单实时状态。 */
    default Optional<PatternProviderMenuView> openPatternProviderMenu() { return Optional.empty(); }

    /** 读取当前打开的 ME 扩展 IO 传输站菜单实时状态。 */
    default Optional<CellTransferMenuView> openExCellTransferMenu() { return Optional.empty(); }

    /** 读取当前打开的 ME 装罐机菜单实时状态。 */
    default Optional<CanerMenuView> openCanerMenu() { return Optional.empty(); }

    /** 读取当前打开的 ME 无线集线器菜单实时状态。 */
    default Optional<WirelessHubMenuView> openWirelessHubMenu() { return Optional.empty(); }

    /** 当前是否正打开着组装矩阵界面。 */
    default boolean isAssemblerMatrixMenuOpen() { return false; }

    /** 水晶组装器客户端视图。 */
    record CrystalAssemblerView(boolean working, boolean powered, boolean active, Direction topFace) {}

    /** 电路切片器客户端视图。 */
    record CircuitCutterView(boolean working, boolean powered, boolean active, Direction front, Direction back,
            MachineState.Shown input, MachineState.Shown renderOutput) {}

    /** 扩展压印器单页四个工作格的客户端视图。 */
    record InscriberPageView(int page, MachineState.Shown top, MachineState.Shown bottom,
            MachineState.Shown middle, MachineState.Shown output) {
        /** 按槽位下标（0 顶、1 底、2 中间、3 输出）取内容。 */
        public MachineState.Shown slot(int index) {
            return switch (index) {
                case 0 -> top;
                case 1 -> bottom;
                case 2 -> middle;
                case 3 -> output;
                default -> null;
            };
        }

        /** 这一页四个工作格是否全部为空。 */
        public boolean empty() {
            return top == null && bottom == null && middle == null && output == null;
        }
    }

    /** 扩展压印器客户端视图。 */
    record ExInscriberView(Direction front, boolean powered, boolean active, boolean smashing, int stackSize,
            List<InscriberPageView> pages) {
        public ExInscriberView {
            pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        }
    }

    /** 扩展充能器单槽客户端视图。 */
    record ChargerSlotView(int slot, String itemId, int count, boolean chargeable, double energy, double maxEnergy) {
        public ChargerSlotView {
            itemId = itemId == null ? "" : itemId;
        }

        public boolean empty() { return itemId.isEmpty() || count <= 0; }

        public boolean fullyCharged() {
            return chargeable && maxEnergy > 0 && energy + 0.01 >= maxEnergy;
        }
    }

    /** 扩展充能器客户端视图。 */
    record ExChargerView(Direction front, boolean working, boolean powered, List<ChargerSlotView> slots) {
        public ExChargerView {
            slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        }
    }

    /** AE2 充能配方视图。 */
    record ChargerRecipeView(String inputItemId, String outputItemId) {
        public ChargerRecipeView {
            Objects.requireNonNull(inputItemId, "inputItemId");
            Objects.requireNonNull(outputItemId, "outputItemId");
        }
    }

    /** 带电物品电量视图。 */
    record ItemPowerView(double current, double max) {
        public boolean full() { return max > 0 && current + 0.01 >= max; }
    }

    /** 水晶修复器客户端视图。 */
    record CrystalFixerView(Direction front, Direction back, boolean powered, String fuelItemId, int fuelCount) {
        public CrystalFixerView {
            fuelItemId = fuelItemId == null ? "" : fuelItemId;
        }
    }

    /** 扩展驱动器单槽磁盘视图。 */
    record DriveCellView(int slot, String itemId, String status) {
        public DriveCellView {
            itemId = itemId == null ? "" : itemId;
            status = status == null ? "absent" : status;
        }
    }

    /** 扩展驱动器客户端视图（20 槽）。 */
    record ExDriveView(boolean online, List<DriveCellView> slots) {
        public ExDriveView {
            slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        }
    }

    /** 通用扩展 ME 网络设备客户端视图。 */
    record NetworkDeviceView(boolean powered, boolean active) {}

    /** ME 装罐机客户端视图。 */
    record CanerView(Direction topFace, Direction bottomFace, boolean powered, boolean active, String mode) {
        public CanerView {
            mode = mode == null ? "" : mode;
        }
    }

    /** 组装矩阵方块客户端视图。 */
    record MatrixView(boolean formed, boolean powered, boolean active) {}

    /** 水晶组装器菜单实时视图。 */
    record CrystalAssemblerMenuView(int processingTime, int maxProcessingTime, String autoExport,
            List<String> outputSides, int tankSlotIndex, String tankFluidId, long tankAmountMb,
            long tankCapacityMb, List<String> upgrades) {
        public CrystalAssemblerMenuView {
            autoExport = autoExport == null ? "no" : autoExport;
            outputSides = List.copyOf(Objects.requireNonNull(outputSides, "outputSides"));
            tankFluidId = tankFluidId == null ? "" : tankFluidId;
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
        }
    }

    /** 电路切片器菜单实时视图。 */
    record CircuitCutterMenuView(int processingTime, int maxProcessingTime, String autoExport,
            List<String> outputSides, List<String> upgrades) {
        public CircuitCutterMenuView {
            autoExport = autoExport == null ? "no" : autoExport;
            outputSides = List.copyOf(Objects.requireNonNull(outputSides, "outputSides"));
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
        }
    }

    /** 扩展压印器菜单实时视图。 */
    record ExInscriberMenuView(int page, String separateSides, String autoExport, int stackMode,
            int processingTime, int maxProcessingTime, List<String> upgrades, boolean currentPageSlotsEnabled) {
        public ExInscriberMenuView {
            separateSides = separateSides == null ? "no" : separateSides;
            autoExport = autoExport == null ? "no" : autoExport;
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
        }

        /** 将菜单内部模式编号（0→64、1→1、2→4）换算为对外堆叠上限字符串。 */
        public String inputCapacity() {
            return switch (stackMode) {
                case 1 -> "1";
                case 2 -> "4";
                default -> "64";
            };
        }
    }

    /** 扩展样板供应器菜单实时视图。 */
    record PatternProviderMenuView(String blockingMode, String lockCraftingMode, String showInAccessTerminal) {
        public PatternProviderMenuView {
            blockingMode = blockingMode == null ? "no" : blockingMode;
            lockCraftingMode = lockCraftingMode == null ? "none" : lockCraftingMode;
            showInAccessTerminal = showInAccessTerminal == null ? "yes" : showInAccessTerminal;
        }
    }

    /** ME 扩展 IO 传输站菜单实时视图。 */
    record CellTransferMenuView(String fullnessMode, String operationMode, List<String> upgrades) {
        public CellTransferMenuView {
            fullnessMode = fullnessMode == null ? "empty" : fullnessMode;
            operationMode = operationMode == null ? "empty" : operationMode;
            upgrades = List.copyOf(Objects.requireNonNull(upgrades, "upgrades"));
        }
    }

    /** ME 装罐机菜单实时视图。 */
    record CanerMenuView(String mode) {
        public CanerMenuView {
            mode = mode == null ? "fill" : mode;
        }
    }

    /** 无线集线器单条通道状态视图。 */
    record HubLinkView(int index, String status, String remotePos, int remoteChannel) {
        public HubLinkView {
            status = status == null ? "unconnected" : status;
            remotePos = remotePos == null ? "" : remotePos;
        }
    }

    /** ME 无线集线器菜单实时视图。 */
    record WirelessHubMenuView(double powerUse, int usedChannel, int maxChannel, List<HubLinkView> links) {
        public WirelessHubMenuView {
            links = List.copyOf(Objects.requireNonNull(links, "links"));
        }
    }
}
