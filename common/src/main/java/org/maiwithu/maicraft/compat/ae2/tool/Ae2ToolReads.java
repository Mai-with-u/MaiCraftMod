// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * AE2 手持工具、便携存储元件、石英扳手、陨石罗盘与内置手册的模组读写接缝。
 * 全部只用 Minecraft 与 Java 类型描述，由 NeoForge 读写端直接调用 AE2 公开接口实现，
 * 离线单元测试可直接用替身替换。
 */
public interface Ae2ToolReads {

    /** 未接入 NeoForge 读写端时的空实现：所有读数返回空、动作返回未发出。 */
    Ae2ToolReads NONE = new Ae2ToolReads() {};

    /** 这块方块能不能用石英扳手或网络工具潜行右键整块拆解（显式排除陨石箱子）。 */
    default boolean canDisassembleBlock(BlockState state) {
        return false;
    }

    /** 这件物品能不能作为 AE2 潜行拆解工具（满足 c:tools/wrench，含石英扳手与网络工具）。 */
    default boolean canDisassembleTool(ItemStack stack) {
        return false;
    }

    /** 这件物品能不能作为 AE2 普通右键旋转工具（石英扳手，显式排除网络工具）。 */
    default boolean canRotateTool(ItemStack stack) {
        return false;
    }

    /** 计算用石英扳手普通右键点击 clickedFace 后的目标方块状态（含样板供应器切换推送面）；不可转时给空。 */
    default Optional<BlockState> rotateBlock(BlockState state, Direction clickedFace) {
        return Optional.empty();
    }

    /** 这一格方块是不是 AE2 的线缆格（ae2:cable_bus）。 */
    default boolean isCableBus(BlockState state) {
        return false;
    }

    /** 读取线缆格当前可被扳手逐个拆解的目标列表（各面部件、中心线缆与伪装板的世界碰撞盒）。 */
    default List<CableTarget> cableDisassembleTargets(ClientLevel level, BlockPos cell) {
        return List.of();
    }

    /** 核对线缆格上某一具体部件、中心线缆或伪装板此刻是否仍在格内。 */
    default boolean cableTargetPresent(ClientLevel level, BlockPos cell, TargetKind kind, Direction side) {
        return false;
    }

    /** 免开界面直接读取一件便携存储元件的电量、容量与同步库存；不是便携存储元件时给空。 */
    default Optional<PortableCellSnapshot> readPortableCell(ItemStack stack) {
        return Optional.empty();
    }

    /** 当前打开的菜单是不是便携存储元件的终端界面（区分物品便携元件与流体便携元件）。 */
    default boolean isPortableCellMenu(AbstractContainerMenu menu, boolean fluid) {
        return false;
    }

    /** 这件物品是不是可放入石英切割刀刻名的金属锭（满足 ae2:metal_ingots）。 */
    default boolean isMetalIngot(ItemStack stack) {
        return false;
    }

    /** 当前打开的菜单是不是石英切割刀界面（ae2:quartzknife）。 */
    default boolean isQuartzKnifeMenu(AbstractContainerMenu menu) {
        return false;
    }

    /** 在打开的石英切割刀界面中设置刻名（同时更新客户端本地名称并向服务端发 GuiActionPacket("setName")）。 */
    default boolean setQuartzKnifeName(AbstractContainerMenu menu, String name) {
        return false;
    }

    /** 读取命名压印模板（ae2:name_press）上刻录的名称；不是或未刻名时给空。 */
    default Optional<String> namePressName(ItemStack stack) {
        return Optional.empty();
    }

    /**
     * 向 AE2 陨石罗盘缓存查询距离当前坐标最近的陨石坐标（触发 AE2 原生罗盘查询包）。
     * 调用方必须先核实角色确实携带了陨石罗盘，严禁在未携带时调用。
     */
    default Optional<BlockPos> closestMeteorite(BlockPos playerPos) {
        return Optional.empty();
    }

    /** 从客户端同步的 12 项 AEComponents 直接读取内存卡摘要；不是内存卡时给空。 */
    default Optional<MemoryCardSnapshot> readMemoryCard(ItemStack stack) {
        return Optional.empty();
    }

    /** 读取随身网络工具内部 9 格升级卡箱的槽位快照；不是网络工具时给空列表。 */
    default List<ItemStack> readNetworkToolbox(ItemStack stack) {
        return List.of();
    }

    /** 当前打开的菜单是不是网络工具升级卡箱界面（ae2:networktool）。 */
    default boolean isNetworkToolMenu(AbstractContainerMenu menu) {
        return false;
    }

    /** 读取任意带电 AE2 物品的当前电量与上限电量；不是带电物品时给空。 */
    default Optional<EnergyReading> readEnergy(ItemStack stack) {
        return Optional.empty();
    }

    /** 读取物质炮的电量、加速卡数与经客户端配方表查得的内部弹药清单；不是物质炮时给空。 */
    default Optional<MatterCannonSnapshot> readMatterCannon(ItemStack stack, Level level) {
        return Optional.empty();
    }

    /** 快速统计物质炮内部存储的弹药总件数（用于核对发射前后弹药减少）。 */
    default long matterCannonTotalAmmo(ItemStack stack) {
        return 0L;
    }

    /** 读取上色器的电量、当前选中颜色与内部各色颜料余量；不是上色器时给空。 */
    default Optional<ColorApplicatorSnapshot> readColorApplicator(ItemStack stack) {
        return Optional.empty();
    }

    /** 向服务端发送上色器切换颜色包（colorName 为小写 AEColor 名或 transparent）；发不出返回 false。 */
    default boolean selectApplicatorColor(String colorName) {
        return false;
    }

    /** 列出 AE2 内置指南目录（assets/ae2/ae2guide）下的全部相对路径（例如 items-blocks-machines/charger.md）。 */
    default List<String> listGuidePages() {
        return List.of();
    }

    /** 按相对路径读取 AE2 内置指南某篇 Markdown 原文；不存在时给空。 */
    default Optional<String> readGuidePage(String relativePath) {
        return Optional.empty();
    }

    /** 线缆格上可被扳手拆解的目标类型：面部件、中心线缆、面伪装板。 */
    enum TargetKind { PART, CABLE, FACADE }

    /**
     * 线缆格上单个可拆目标。
     *
     * @param kind 目标类型（PART/CABLE/FACADE）
     * @param side 所在面；中心线缆为 null
     * @param box  该目标在世界坐标系下的选中碰撞盒
     */
    record CableTarget(TargetKind kind, Direction side, AABB box) {
        public CableTarget {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(box, "box");
        }
    }

    /**
     * 带电物品的电量读数（单位 AE）。
     *
     * @param current 当前电量
     * @param max     最大容量
     */
    record EnergyReading(double current, double max) {
        public EnergyReading {
            current = Math.max(0.0, current);
            max = Math.max(0.0, max);
        }
    }

    /**
     * 便携存储元件内的一条存货记录。
     *
     * @param itemId      物品 ID（流体且有原版桶时为对应满桶的物品 ID）
     * @param count       数量（物品为件数，流体有桶时为整桶数）
     * @param fillsBucket 是否为流体装桶条目
     * @param rawId       原始流体或物品注册 ID
     * @param rawAmount   原始数量（物品件数或流体毫桶）
     */
    record CellStockEntry(String itemId, long count, boolean fillsBucket, String rawId, long rawAmount) {
        public CellStockEntry {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(rawId, "rawId");
        }

        /** 物品条目的简写构造。 */
        public static CellStockEntry item(String itemId, long count) {
            return new CellStockEntry(itemId, count, false, itemId, count);
        }

        /** 流体条目的简写构造（按 1000 mB 折算整桶数）。 */
        public static CellStockEntry fluid(String bucketItemId, String fluidId, long milliBuckets) {
            return new CellStockEntry(bucketItemId, Math.max(0L, milliBuckets / 1000L), true, fluidId, milliBuckets);
        }
    }

    /**
     * 便携存储元件的客户端直读快照。
     *
     * @param itemId        便携元件本身的物品 ID
     * @param fluidCell     是否为流体便携元件
     * @param currentEnergy 当前电量（AE）
     * @param maxEnergy     电量上限（AE）
     * @param totalBytes    总字节数
     * @param usedBytes     已用字节数
     * @param bytesPerType  每种物品/流体占用的类型字节数
     * @param storedTypes   已存种类数
     * @param totalTypes    种类上限
     * @param entries       盘内同步的存货清单
     */
    record PortableCellSnapshot(
            String itemId,
            boolean fluidCell,
            double currentEnergy,
            double maxEnergy,
            long totalBytes,
            long usedBytes,
            int bytesPerType,
            int storedTypes,
            int totalTypes,
            List<CellStockEntry> entries) {
        public PortableCellSnapshot {
            Objects.requireNonNull(itemId, "itemId");
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /**
     * 内存卡上客户端同步的全部导出组件汇总。
     *
     * @param source             导出源设备名（空字符串表示未保存）
     * @param customName         导出的自定义名称
     * @param priority           导出的优先级（null 表示未保存）
     * @param p2pType            导出的 P2P 物品 ID（空字符串表示未保存）
     * @param p2pFrequency       导出的 P2P 频率四位十六进制串（空字符串表示未保存）
     * @param color              内存卡八色编码摘要（空字符串表示默认色）
     * @param upgradesCount      导出的升级卡总张数
     * @param exportedSettings   导出的配置键值表
     * @param configFilterCount  导出的过滤槽条目数
     * @param levelEmitterValue  导出的发信器阈值（null 表示未保存）
     * @param exportedPatterns   导出的样板张数
     * @param pushDirection      导出的推送方向（空字符串表示未保存）
     * @param hasAnyExported     是否含任意一项导出组件或颜色编码
     */
    record MemoryCardSnapshot(
            String source,
            String customName,
            Integer priority,
            String p2pType,
            String p2pFrequency,
            String color,
            int upgradesCount,
            Map<String, String> exportedSettings,
            int configFilterCount,
            Long levelEmitterValue,
            int exportedPatterns,
            String pushDirection,
            boolean hasAnyExported) {
        public MemoryCardSnapshot {
            source = source == null ? "" : source;
            customName = customName == null ? "" : customName;
            p2pType = p2pType == null ? "" : p2pType;
            p2pFrequency = p2pFrequency == null ? "" : p2pFrequency;
            color = color == null ? "" : color;
            exportedSettings = exportedSettings == null ? Map.of() : Map.copyOf(exportedSettings);
            pushDirection = pushDirection == null ? "" : pushDirection;
        }
    }

    /**
     * 物质炮内的一条弹药记录。
     *
     * @param itemId    弹药物品 ID
     * @param count     数量
     * @param weight    配方查得的弹丸权重（用于折算穿透与伤害）
     * @param paintBall 是否为无伤害彩弹（PaintBallItem）
     */
    record CannonAmmoEntry(String itemId, long count, float weight, boolean paintBall) {
        public CannonAmmoEntry {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /**
     * 物质炮的客户端快照。
     *
     * @param currentEnergy 当前电量（AE）
     * @param maxEnergy     电量上限（AE）
     * @param speedCards    已安装的加速卡张数
     * @param ammoEntries   内部弹药清单（按内部存储顺序）
     */
    record MatterCannonSnapshot(double currentEnergy, double maxEnergy, int speedCards,
                                List<CannonAmmoEntry> ammoEntries) {
        public MatterCannonSnapshot {
            ammoEntries = ammoEntries == null ? List.of() : List.copyOf(ammoEntries);
        }
    }

    /**
     * 上色器的客户端快照。
     *
     * @param currentEnergy 当前电量（AE）
     * @param maxEnergy     电量上限（AE）
     * @param activeColor   当前选中颜色的小写名（含 transparent；未选时为空字符串）
     * @param pigments      各颜色对应的可用颜料总数（颜色小写名 → 件数）
     */
    record ColorApplicatorSnapshot(double currentEnergy, double maxEnergy, String activeColor,
                                   Map<String, Long> pigments) {
        public ColorApplicatorSnapshot {
            activeColor = activeColor == null ? "" : activeColor;
            pigments = pigments == null ? Map.of() : Map.copyOf(pigments);
        }
    }
}
