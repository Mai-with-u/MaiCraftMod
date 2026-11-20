// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import appeng.api.ids.AEComponents;
import appeng.api.implementations.items.ISpatialStorageCell;
import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import appeng.blockentity.spatial.SpatialAnchorBlockEntity;
import appeng.blockentity.spatial.SpatialIOPortBlockEntity;
import appeng.blockentity.spatial.SpatialPylonBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.items.storage.SpatialPlotInfo;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.SpatialAnchorMenu;
import appeng.menu.implementations.SpatialIOPortMenu;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads;

/**
 * AE2 量子环与空间存储客户端读数的 NeoForge 读写端：零反射直接调用 AE2 公开方块实体、菜单与物品组件，
 * 把游戏对象翻译成 {@link Ae2AdvancedReads} 的纯数据快照；不在此做任何业务判断。
 */
public final class AppliedEnergisticsAdvanced implements Ae2AdvancedReads {

    @Override public Optional<QuantumCell> quantum(ClientLevel level, BlockPos at) {
        if (level == null || at == null || !level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (!(entity instanceof QuantumBridgeBlockEntity quantum)) return Optional.empty();
        boolean center = entity.getBlockState().is(AEBlocks.QUANTUM_LINK.block());
        return Optional.of(new QuantumCell(
                center,
                quantum.isFormed(),
                quantum.isCorner(),
                quantum.isPowered(),
                quantum.hasQES(),
                quantum.getQEFrequency()));
    }

    @Override public Optional<Long> entangledSingularityId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        Long id = stack.get(AEComponents.ENTANGLED_SINGULARITY_ID);
        return id == null || id == 0L ? Optional.empty() : Optional.of(id);
    }

    @Override public Optional<SpatialPylonCell> spatialPylon(ClientLevel level, BlockPos at) {
        if (level == null || at == null || !level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (!(entity instanceof SpatialPylonBlockEntity pylon)) return Optional.empty();
        SpatialPylonBlockEntity.ClientState visual = pylon.getClientState();
        return Optional.of(new SpatialPylonCell(
                visual.powered(),
                visual.online(),
                visual.axis().getName(),
                visual.axisPosition().name().toLowerCase(Locale.ROOT)));
    }

    @Override public Optional<SpatialTransferCell> spatialTransfer(ClientLevel level, BlockPos at) {
        if (level == null || at == null || !level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (!(entity instanceof SpatialIOPortBlockEntity spatial)) return Optional.empty();
        return Optional.of(new SpatialTransferCell(spatial.isActive()));
    }

    // SpatialIOPortMenu 的电量与效率字段由服务端按 ×100 定点同步，这里还原为实际数值。
    @Override public Optional<SpatialMenuStatus> openSpatialTransferMenu(AbstractContainerMenu menu) {
        if (!(menu instanceof SpatialIOPortMenu spatialMenu)) return Optional.empty();
        Optional<SpatialCellSpec> inputCell = readFirstSemanticCell(spatialMenu, SlotSemantics.MACHINE_INPUT);
        Optional<SpatialCellSpec> outputCell = readFirstSemanticCell(spatialMenu, SlotSemantics.MACHINE_OUTPUT);
        return Optional.of(new SpatialMenuStatus(
                spatialMenu.getCurrentPower() / 100.0,
                spatialMenu.getMaxPower() / 100.0,
                spatialMenu.getRequiredPower() / 100.0,
                spatialMenu.getEfficency() / 100.0,
                spatialMenu.xSize,
                spatialMenu.ySize,
                spatialMenu.zSize,
                inputCell,
                outputCell));
    }

    @Override public Optional<SpatialCellSpec> spatialCell(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        if (!(stack.getItem() instanceof ISpatialStorageCell cell) || !cell.isSpatialStorage(stack)) {
            return Optional.empty();
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        SpatialPlotInfo binding = stack.get(AEComponents.SPATIAL_PLOT_INFO);
        int lockedX = binding != null && binding.size() != null ? binding.size().getX() : 0;
        int lockedY = binding != null && binding.size() != null ? binding.size().getY() : 0;
        int lockedZ = binding != null && binding.size() != null ? binding.size().getZ() : 0;
        return Optional.of(new SpatialCellSpec(
                itemId,
                cell.getMaxStoredDim(stack),
                cell.getAllocatedPlotId(stack),
                lockedX,
                lockedY,
                lockedZ));
    }

    @Override public Optional<SpatialAnchorCell> spatialAnchor(ClientLevel level, BlockPos at) {
        if (level == null || at == null || !level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (!(entity instanceof SpatialAnchorBlockEntity anchor)) return Optional.empty();
        return Optional.of(new SpatialAnchorCell(anchor.isActive(), anchor.countLoadedChunks()));
    }

    @Override public Optional<SpatialAnchorMenuStatus> openSpatialAnchorMenu(AbstractContainerMenu menu) {
        if (!(menu instanceof SpatialAnchorMenu anchorMenu)) return Optional.empty();
        return Optional.of(new SpatialAnchorMenuStatus(
                anchorMenu.powerConsumption,
                anchorMenu.loadedChunks,
                anchorMenu.getOverlayMode().name().toLowerCase(Locale.ROOT),
                anchorMenu.allLoadedWorlds,
                anchorMenu.allLoadedChunks,
                anchorMenu.allWorlds,
                anchorMenu.allChunks));
    }

    private Optional<SpatialCellSpec> readFirstSemanticCell(SpatialIOPortMenu menu, SlotSemantic semantic) {
        List<Slot> slots = menu.getSlots(semantic);
        if (slots.isEmpty()) return Optional.empty();
        return spatialCell(slots.getFirst().getItem());
    }
}
