// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * AE2 量子环与空间存储的客户端读写接缝：只读客户端已同步的方块实体字段、打开菜单时的同步数值
 * 及物品组件（奇点频率与空间元件绑定尺寸）。直接调 AE2 公开类的实现放在 NeoForge 模块，测试用替身。
 */
public interface Ae2AdvancedReads {

    /** 没接读写端时的空实现：所有读数均返回空。 */
    Ae2AdvancedReads NONE = new Ae2AdvancedReads() {
        @Override public Optional<QuantumCell> quantum(ClientLevel level, BlockPos at) { return Optional.empty(); }
        @Override public Optional<Long> entangledSingularityId(ItemStack stack) { return Optional.empty(); }
        @Override public Optional<SpatialPylonCell> spatialPylon(ClientLevel level, BlockPos at) { return Optional.empty(); }
        @Override public Optional<SpatialTransferCell> spatialTransfer(ClientLevel level, BlockPos at) { return Optional.empty(); }
        @Override public Optional<SpatialMenuStatus> openSpatialTransferMenu(AbstractContainerMenu menu) { return Optional.empty(); }
        @Override public Optional<SpatialCellSpec> spatialCell(ItemStack stack) { return Optional.empty(); }
        @Override public Optional<SpatialAnchorCell> spatialAnchor(ClientLevel level, BlockPos at) { return Optional.empty(); }
        @Override public Optional<SpatialAnchorMenuStatus> openSpatialAnchorMenu(AbstractContainerMenu menu) { return Optional.empty(); }
    };

    /** 读取一格量子环或量子链接仓客户端同步的状态。 */
    Optional<QuantumCell> quantum(ClientLevel level, BlockPos at);

    /** 读取一件量子缠绕奇点物品上的频率编号；非缠绕奇点或未赋频时返回空。 */
    Optional<Long> entangledSingularityId(ItemStack stack);

    /** 读取一格空间塔客户端同步的供电、在线与轴向位置。 */
    Optional<SpatialPylonCell> spatialPylon(ClientLevel level, BlockPos at);

    /** 读取一格空间传输站客户端同步的在线状态。 */
    Optional<SpatialTransferCell> spatialTransfer(ClientLevel level, BlockPos at);

    /** 读取当前打开的空间传输站菜单里服务端同步的电量、效率、尺寸与两格元件状态。 */
    Optional<SpatialMenuStatus> openSpatialTransferMenu(AbstractContainerMenu menu);

    /** 识别一件物品是否为空间存储元件，并读出最大维度与已绑定尺寸。 */
    Optional<SpatialCellSpec> spatialCell(ItemStack stack);

    /** 读取一格空间锚客户端同步的在线状态与覆盖层可见区块数。 */
    Optional<SpatialAnchorCell> spatialAnchor(ClientLevel level, BlockPos at);

    /** 读取当前打开的空间锚菜单里服务端同步的能耗、加载区块数、覆盖层模式与全网统计。 */
    Optional<SpatialAnchorMenuStatus> openSpatialAnchorMenu(AbstractContainerMenu menu);

    /**
     * 一格量子环或量子链接仓的客户端读数。
     *
     * @param center         是否为中心量子链接仓（ae2:quantum_link）
     * @param formed         是否已成型为 3x3 平面结构
     * @param corner         是否为四角环块（四角不可接外部 ME 线缆）
     * @param powered        是否通电在线
     * @param hasSingularity 中心链接仓是否放有缠绕奇点
     * @param frequency      奇点频率；客户端未开菜单且无同步时为 0
     */
    record QuantumCell(boolean center, boolean formed, boolean corner, boolean powered,
                       boolean hasSingularity, long frequency) {
    }

    /**
     * 一格空间塔的客户端同步状态。
     *
     * @param powered      是否通电
     * @param online       所在空间塔列是否有效且在线
     * @param axis         沿哪条轴成线（x / y / z）
     * @param axisPosition 在这条塔列里的位置（none / start / middle / end）
     */
    record SpatialPylonCell(boolean powered, boolean online, String axis, String axisPosition) {
        public SpatialPylonCell {
            axis = Objects.requireNonNull(axis, "axis");
            axisPosition = Objects.requireNonNull(axisPosition, "axisPosition");
        }
    }

    /** 一格空间传输站的客户端同步状态。 */
    record SpatialTransferCell(boolean active) {
    }

    /**
     * 一件空间存储元件的规格与已绑定区域状态。
     *
     * @param itemId          元件注册 ID
     * @param maxDim          允许的最大单轴尺寸（2 / 16 / 128）
     * @param allocatedPlotId 已分配的空间地块编号；未分配为 -1
     * @param lockedX         已绑定区域的锁定 X 尺寸；未绑定为 0
     * @param lockedY         已绑定区域的锁定 Y 尺寸；未绑定为 0
     * @param lockedZ         已绑定区域的锁定 Z 尺寸；未绑定为 0
     */
    record SpatialCellSpec(String itemId, int maxDim, int allocatedPlotId, int lockedX, int lockedY, int lockedZ) {
        public SpatialCellSpec {
            itemId = Objects.requireNonNull(itemId, "itemId");
        }

        /** 是否已绑定固定尺寸的空间地块。 */
        public boolean allocated() {
            return allocatedPlotId >= 0 || (lockedX > 0 && lockedY > 0 && lockedZ > 0);
        }

        /** 格式化锁定尺寸文本（例如 3x3x3）；未绑定时返回空串。 */
        public String lockedSizeText() {
            return lockedX > 0 && lockedY > 0 && lockedZ > 0 ? lockedX + "x" + lockedY + "x" + lockedZ : "";
        }
    }

    /**
     * 空间传输站界面打开时同步出来的权威数值与两格空间元件。
     *
     * @param currentPower  当前电网电量（AE）
     * @param maxPower      电网最大存电量（AE）
     * @param requiredPower 切换当前区域所需电量（AE）
     * @param efficiency    当前空间塔效率（百分比 0..100）
     * @param xSize         有效区域内腔 X 尺寸
     * @param ySize         有效区域内腔 Y 尺寸
     * @param zSize         有效区域内腔 Z 尺寸
     * @param inputCell     槽 0 空间元件状态；空槽为 empty
     * @param outputCell    槽 1 空间元件状态；空槽为 empty
     */
    record SpatialMenuStatus(double currentPower, double maxPower, double requiredPower, double efficiency,
                             int xSize, int ySize, int zSize,
                             Optional<SpatialCellSpec> inputCell, Optional<SpatialCellSpec> outputCell) {
        public SpatialMenuStatus {
            inputCell = Objects.requireNonNull(inputCell, "inputCell");
            outputCell = Objects.requireNonNull(outputCell, "outputCell");
        }
    }

    /**
     * 一格空间锚的客户端同步状态。
     *
     * @param active             是否通电在线
     * @param overlayChunkCount  开启覆盖层时客户端同步到的区块数；关闭覆盖层时为 0
     */
    record SpatialAnchorCell(boolean active, int overlayChunkCount) {
    }

    /**
     * 空间锚界面打开时同步出来的权威数值。
     *
     * @param powerConsumption 本锚实时耗电（AE/t）
     * @param loadedChunks     本锚维持加载的区块数
     * @param overlayMode      是否开启范围覆盖层显示（yes / no）
     * @param allLoadedWorlds  全网有空间锚维持加载的维度数
     * @param allLoadedChunks  全网被空间锚维持加载的总区块数
     * @param allWorlds        整张 ME 网横跨的维度数
     * @param allChunks        整张 ME 网横跨的总区块数
     */
    record SpatialAnchorMenuStatus(long powerConsumption, int loadedChunks, String overlayMode,
                                   int allLoadedWorlds, int allLoadedChunks, int allWorlds, int allChunks) {
        public SpatialAnchorMenuStatus {
            overlayMode = Objects.requireNonNull(overlayMode, "overlayMode");
        }
    }
}
