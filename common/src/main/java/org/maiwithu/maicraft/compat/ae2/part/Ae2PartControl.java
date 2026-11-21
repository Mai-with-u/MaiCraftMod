// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * AE2 线缆部件、元件工作台、ME IO 传输站与无线访问点的客户端读写与专用发包接缝。
 *
 * <p>只使用 Minecraft 与 Java 类型，避免通用模块依赖任何模组类；直接调用 AE2 公开 API 的实现
 * 放在 NeoForge 读写端，离线测试使用替身实现。
 */
public interface Ae2PartControl {

    /** 未接入模组读写端时的空实现：所有读数为空，所有专用发包返回 false。 */
    Ae2PartControl NONE = new Ae2PartControl() {
        @Override public Optional<AABB> partBox(ClientLevel level, BlockPos host, Direction side) {
            return Optional.empty();
        }
        @Override public Optional<ClientPartReading> readClientPart(ClientLevel level, BlockPos host, Direction side) {
            return Optional.empty();
        }
        @Override public Optional<OpenMenuSnapshot> readOpenMenu() {
            return Optional.empty();
        }
        @Override public boolean selectKeyType(String keyType, boolean enabled) {
            return false;
        }
        @Override public boolean setCellWorkbenchFuzzy(String fuzzyMode) {
            return false;
        }
        @Override public Optional<ItemStack> filterStackOf(String targetId) {
            return Optional.empty();
        }
        @Override public Optional<String> p2pAttunementOf(ItemStack stack) {
            return Optional.empty();
        }
        @Override public boolean isTransferableCell(ItemStack stack) {
            return false;
        }
        @Override public boolean isMemoryCard(ItemStack stack) {
            return false;
        }
        @Override public Optional<String> memoryCardP2pType(ItemStack stack) {
            return Optional.empty();
        }
        @Override public OptionalInt memoryCardP2pFrequency(ItemStack stack) {
            return OptionalInt.empty();
        }
        @Override public boolean isMenuType(AbstractContainerMenu menu, String menuTypeId) {
            return false;
        }
        @Override public boolean isFluidContainerFor(ItemStack stack, String fluidId) {
            return false;
        }
    };

    /**
     * 线缆部件客户端已同步的直读快照（不依赖开窗或服务端查询）。
     *
     * @param hasMonitor   是不是存储监视器或转换监视器
     * @param displayed    监视器展示的物品或流体 ID；未设置时为 null
     * @param amount       监视器当前显示数量
     * @param canCraft     监视器展示物能否自动合成
     * @param locked       监视器是否已锁定
     * @param hasP2p       是不是 P2P 通道部件
     * @param p2pFrequency P2P 通道频率（无符号 0..65535；0 表示未分配频率）
     */
    record ClientPartReading(boolean hasMonitor, String displayed, long amount, boolean canCraft, boolean locked,
                             boolean hasP2p, int p2pFrequency) {

        /** 监视器读数。 */
        public static ClientPartReading monitor(String displayed, long amount, boolean canCraft, boolean locked) {
            return new ClientPartReading(true, displayed, amount, canCraft, locked, false, 0);
        }

        /** P2P 通道读数。 */
        public static ClientPartReading p2p(int frequency) {
            return new ClientPartReading(false, null, 0L, false, false, true, frequency & 0xFFFF);
        }
    }

    /**
     * 当前打开的 AE2 菜单里某一语义组下的单个槽位读数。
     *
     * @param menuIndex  菜单中的真实槽位编号（用于点击或发 SET_FILTER）
     * @param localIndex 在本语义组内的顺序号（从 0 起，用于 filter.N / stock.N / upgrade.N）
     * @param enabled    该槽位当前是否已启用（未插容量卡或当前元件无此升级槽时为 false）
     * @param itemId     槽位内的物品、流体或化学品注册 ID；空槽为 null
     * @param count      槽位内的堆叠或备料数量；空槽为 0
     * @param keyType    介质键类别（{@code item} / {@code fluid} / {@code chemical}，未标明时为空串）
     */
    record MenuSlotEntry(int menuIndex, int localIndex, boolean enabled, String itemId, long count, String keyType) {

        public MenuSlotEntry {
            keyType = keyType == null ? "" : keyType;
        }

        /** 兼容未区分介质键类别的旧构造与离线替身。 */
        public MenuSlotEntry(int menuIndex, int localIndex, boolean enabled, String itemId, long count) {
            this(menuIndex, localIndex, enabled, itemId, count, "");
        }

        /** 该槽位是否为空。 */
        public boolean isEmpty() {
            return itemId == null || itemId.isEmpty() || count <= 0;
        }
    }

    /**
     * 当前打开的 AE2 菜单的实时只读快照，汇总所有同步设置、子菜单数值与语义槽位内容。
     */
    record OpenMenuSnapshot(String menuTypeId, Map<String, String> settings, Map<String, Boolean> keyTypes,
                            String connectedTo, OptionalLong reportingValue, OptionalInt priority,
                            OptionalInt stockInitialAmount, OptionalInt stockMaxAmount, boolean hasRedstoneCard,
                            OptionalInt wirelessBoosters, List<MenuSlotEntry> configSlots,
                            List<MenuSlotEntry> upgradeSlots, List<MenuSlotEntry> storageSlots,
                            List<MenuSlotEntry> cellSlots, List<MenuSlotEntry> inputSlots,
                            List<MenuSlotEntry> outputSlots) {

        public OpenMenuSnapshot {
            menuTypeId = menuTypeId == null ? "" : menuTypeId;
            settings = Map.copyOf(Objects.requireNonNull(settings, "settings"));
            keyTypes = Map.copyOf(Objects.requireNonNull(keyTypes, "keyTypes"));
            reportingValue = Objects.requireNonNull(reportingValue, "reportingValue");
            priority = Objects.requireNonNull(priority, "priority");
            stockInitialAmount = Objects.requireNonNull(stockInitialAmount, "stockInitialAmount");
            stockMaxAmount = Objects.requireNonNull(stockMaxAmount, "stockMaxAmount");
            wirelessBoosters = Objects.requireNonNull(wirelessBoosters, "wirelessBoosters");
            configSlots = List.copyOf(Objects.requireNonNull(configSlots, "configSlots"));
            upgradeSlots = List.copyOf(Objects.requireNonNull(upgradeSlots, "upgradeSlots"));
            storageSlots = List.copyOf(Objects.requireNonNull(storageSlots, "storageSlots"));
            cellSlots = List.copyOf(Objects.requireNonNull(cellSlots, "cellSlots"));
            inputSlots = List.copyOf(Objects.requireNonNull(inputSlots, "inputSlots"));
            outputSlots = List.copyOf(Objects.requireNonNull(outputSlots, "outputSlots"));
        }
    }

    /** 计算线缆格某一面部件在世界坐标下的交互包围盒，用于精准靠近与右键点中该面部件。 */
    Optional<AABB> partBox(ClientLevel level, BlockPos host, Direction side);

    /** 读取客户端流直接同步的部件状态（监视器展示/锁定/数量/可合成、P2P 通道频率）。 */
    Optional<ClientPartReading> readClientPart(ClientLevel level, BlockPos host, Direction side);

    /** 读取当前打开的 AE2 菜单状态与全部语义槽位；未打开 AE2 菜单时为空。 */
    Optional<OpenMenuSnapshot> readOpenMenu();

    /** 切换输入总线的导入键类型（item / fluid / chemical），发送 SelectKeyTypePacket 并更新客户端选中态。 */
    boolean selectKeyType(String keyType, boolean enabled);

    /** 设置元件工作台当前元件的模糊模式，走 setFuzzyMode 界面动作写入元件组件，不走 ConfigButtonPacket。 */
    boolean setCellWorkbenchFuzzy(String fuzzyMode);

    /** 将物品、流体或化学品注册 ID（支持 {@code chemical:<id>} 前缀或 empty）包装成可直接发给幽灵过滤槽的 ItemStack；无法识别时为空。 */
    Optional<ItemStack> filterStackOf(String targetId);

    /** 判定这件物品能把 P2P 通道调谐成哪一种类型（me / redstone / item / fluid / fe / light / chemical）；不能调谐为空。 */
    Optional<String> p2pAttunementOf(ItemStack stack);

    /** 这件物品能不能放进 ME IO 传输站倒盘（存储元件或实现 IBasicCellItem 的物质炮、上色器等工具）。 */
    boolean isTransferableCell(ItemStack stack);

    /** 这件物品是不是 AE2 内存卡。 */
    boolean isMemoryCard(ItemStack stack);

    /** 读取内存卡上已导出的 P2P 通道类型物品 ID；未存 P2P 时为空。 */
    Optional<String> memoryCardP2pType(ItemStack stack);

    /** 读取内存卡上已导出的 P2P 通道频率（无符号 0..65535）；未存频率时为空。 */
    OptionalInt memoryCardP2pFrequency(ItemStack stack);

    /** 核对这份已打开菜单的注册 ID 是否等于目标菜单 ID（供换页跟随时判定）。 */
    boolean isMenuType(AbstractContainerMenu menu, String menuTypeId);

    /** 判断这件物品是不是盛有指定流体或化学品的容器物品（支持 {@code chemical:<id>} 消歧，供监视器二次右键切换内含物）。 */
    boolean isFluidContainerFor(ItemStack stack, String fluidId);
}
