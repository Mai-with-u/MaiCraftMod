// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.extendedae;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import appeng.api.implementations.IPowerChannelState;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.inventories.InternalInventory;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;
import appeng.api.storage.cells.CellState;
import appeng.blockentity.misc.ChargerRecipes;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.recipes.handlers.ChargerRecipe;
import appeng.util.Platform;
import com.glodblock.github.extendedae.api.CanerMode;
import com.glodblock.github.extendedae.common.blocks.BlockCircuitCutter;
import com.glodblock.github.extendedae.common.blocks.BlockCrystalAssembler;
import com.glodblock.github.extendedae.common.tileentities.TileCaner;
import com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter;
import com.glodblock.github.extendedae.common.tileentities.TileCrystalAssembler;
import com.glodblock.github.extendedae.common.tileentities.TileCrystalFixer;
import com.glodblock.github.extendedae.common.tileentities.TileExCharger;
import com.glodblock.github.extendedae.common.tileentities.TileExDrive;
import com.glodblock.github.extendedae.common.tileentities.TileExInscriber;
import com.glodblock.github.extendedae.common.tileentities.TileExMolecularAssembler;
import com.glodblock.github.extendedae.common.tileentities.matrix.TileAssemblerMatrixBase;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.compat.extendedae.ExtendedAeReads;

/**
 * ExtendedAE 客户端世界与方块实体读写端（NeoForge {@code compileOnly} 零反射实现）：
 * 直接调用 ExtendedAE 与 AE2 公开方块实体接口读取水晶组装器、电路切片器、扩展压印器、
 * 扩展充能器、水晶修复器、扩展驱动器、扩展分子组装室、装罐机与组装矩阵的客户端同步状态；
 * 菜单读数与网络包发送委托给同包 {@link ExtendedAeMenuActions}。
 */
public final class ExtendedAeClientReads implements ExtendedAeReads {

    private final ExtendedAeMenuActions menuActions = new ExtendedAeMenuActions();

    @Override public Optional<CrystalAssemblerView> crystalAssemblerAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileCrystalAssembler tile)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        boolean working = tile.isWorking()
                || (state.hasProperty(BlockCrystalAssembler.WORKING) && state.getValue(BlockCrystalAssembler.WORKING));
        boolean powered = readPowered(tile, state);
        boolean active = readActive(tile, powered);
        Direction top = tile.getOrientation().getSide(RelativeSide.TOP);
        return Optional.of(new CrystalAssemblerView(working, powered, active, top));
    }

    @Override public Optional<CircuitCutterView> circuitCutterAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileCircuitCutter tile)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        boolean working = tile.isWorking()
                || (state.hasProperty(BlockCircuitCutter.WORKING) && state.getValue(BlockCircuitCutter.WORKING));
        boolean powered = readPowered(tile, state);
        boolean active = readActive(tile, powered);
        BlockOrientation orientation = tile.getOrientation();
        ItemStack input = tile.getInput().getStackInSlot(0);
        ItemStack renderOut = tile.getRenderOutput();
        return Optional.of(new CircuitCutterView(working, powered, active,
                orientation.getSide(RelativeSide.FRONT), orientation.getSide(RelativeSide.BACK),
                toShown(input), toShown(renderOut)));
    }

    @Override public Optional<ExInscriberView> exInscriberAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileExInscriber tile)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        boolean powered = readPowered(tile, state);
        boolean active = readActive(tile, powered);
        Direction front = tile.getOrientation().getSide(RelativeSide.FRONT);
        List<InscriberPageView> pages = new ArrayList<>(4);
        for (int p = 0; p < 4; p++) {
            InternalInventory inv = tile.getIndexInventory(p);
            MachineState.Shown top = inv != null && inv.size() > 0 ? toShown(inv.getStackInSlot(0)) : null;
            MachineState.Shown bottom = inv != null && inv.size() > 1 ? toShown(inv.getStackInSlot(1)) : null;
            MachineState.Shown middle = inv != null && inv.size() > 2 ? toShown(inv.getStackInSlot(2)) : null;
            MachineState.Shown output = inv != null && inv.size() > 3 ? toShown(inv.getStackInSlot(3)) : null;
            pages.add(new InscriberPageView(p, top, bottom, middle, output));
        }
        return Optional.of(new ExInscriberView(front, powered, active, tile.isSmash(), tile.getInvStackSize(), pages));
    }

    @Override public Optional<ExChargerView> exChargerAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileExCharger tile)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        Direction front = tile.getOrientation().getSide(RelativeSide.FRONT);
        InternalInventory inv = tile.getInternalInventory();
        int count = Math.min(4, inv.size());
        List<ChargerSlotView> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                slots.add(new ChargerSlotView(i, "", 0, false, 0.0, 0.0));
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean chargeable = Platform.isChargeable(stack) && stack.getItem() instanceof IAEItemPowerStorage;
            double energy = 0.0;
            double maxEnergy = 0.0;
            if (chargeable) {
                IAEItemPowerStorage storage = (IAEItemPowerStorage) stack.getItem();
                energy = storage.getAECurrentPower(stack);
                maxEnergy = storage.getAEMaxPower(stack);
            }
            slots.add(new ChargerSlotView(i, id, stack.getCount(), chargeable, energy, maxEnergy));
        }
        return Optional.of(new ExChargerView(front, tile.isWorking(), readPowered(tile, state), slots));
    }

    @Override public Optional<ChargerRecipeView> chargerRecipeForOutput(ClientLevel level, String outputItemId) {
        if (level == null || outputItemId == null || outputItemId.isBlank()) return Optional.empty();
        for (RecipeHolder<ChargerRecipe> holder : ChargerRecipes.getRecipes(level)) {
            ChargerRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem();
            if (result.isEmpty()) continue;
            String outId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            if (!outputItemId.equals(outId)) continue;
            ItemStack[] inputs = recipe.getIngredient().getItems();
            if (inputs.length == 0 || inputs[0].isEmpty()) continue;
            String inId = BuiltInRegistries.ITEM.getKey(inputs[0].getItem()).toString();
            return Optional.of(new ChargerRecipeView(inId, outId));
        }
        return Optional.empty();
    }

    @Override public Optional<ItemPowerView> itemPower(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof IAEItemPowerStorage storage)) {
            return Optional.empty();
        }
        return Optional.of(new ItemPowerView(storage.getAECurrentPower(stack), storage.getAEMaxPower(stack)));
    }

    @Override public Optional<CrystalFixerView> crystalFixerAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileCrystalFixer tile)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        BlockOrientation orientation = tile.getOrientation();
        ItemStack fuel = tile.getInternalInventory().getStackInSlot(0);
        String fuelId = fuel.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString();
        return Optional.of(new CrystalFixerView(
                orientation.getSide(RelativeSide.FRONT), orientation.getSide(RelativeSide.BACK),
                readPowered(tile, state), fuelId, fuel.getCount()));
    }

    @Override public Optional<ExDriveView> exDriveAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileExDrive drive)) return Optional.empty();
        int count = drive.getCellCount();
        List<DriveCellView> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Item item = drive.getCellItem(i);
            String itemId = item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString();
            CellState status = drive.getCellStatus(i);
            String statusName = status == null ? "absent" : status.name().toLowerCase(Locale.ROOT);
            slots.add(new DriveCellView(i, itemId, statusName));
        }
        return Optional.of(new ExDriveView(drive.isPowered(), slots));
    }

    @Override public Optional<NetworkDeviceView> exMolecularAssemblerAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileExMolecularAssembler tile)) return Optional.empty();
        return Optional.of(new NetworkDeviceView(tile.isPowered(), tile.isActive()));
    }

    @Override public Optional<CanerView> canerAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileCaner tile)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        BlockOrientation orientation = tile.getOrientation();
        CanerMode mode = tile.getMode();
        String modeName = mode == null ? "" : mode.name().toLowerCase(Locale.ROOT);
        boolean powered = readPowered(tile, state);
        return Optional.of(new CanerView(
                orientation.getSide(RelativeSide.TOP), orientation.getSide(RelativeSide.BOTTOM),
                powered, readActive(tile, powered), modeName));
    }

    @Override public Optional<MatrixView> matrixBlockAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof TileAssemblerMatrixBase tile)) return Optional.empty();
        return Optional.of(new MatrixView(tile.isFormed(), tile.isPowered(), tile.isActive()));
    }

    @Override public Optional<NetworkDeviceView> networkDeviceAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(at);
        if (entity == null) return Optional.empty();
        BlockState state = level.getBlockState(at);
        boolean powered = readPowered(entity, state);
        return Optional.of(new NetworkDeviceView(powered, readActive(entity, powered)));
    }

    @Override public boolean isMenuOpen() { return menuActions.isMenuOpen(); }
    @Override public List<Integer> enabledSlotsOf(String semanticId) { return menuActions.enabledSlotsOf(semanticId); }
    @Override public List<Integer> allSlotsOf(String semanticId) { return menuActions.allSlotsOf(semanticId); }
    @Override public boolean sendConfigButton(String settingName, boolean backwards) {
        return menuActions.sendConfigButton(settingName, backwards);
    }
    @Override public boolean sendGenericAction(String action, Object... args) {
        return menuActions.sendGenericAction(action, args);
    }
    @Override public boolean sendUpdatePage(int page) { return menuActions.sendUpdatePage(page); }
    @Override public boolean sendEmptyItemToTank(int tankSlotIndex) {
        return menuActions.sendEmptyItemToTank(tankSlotIndex);
    }
    @Override public boolean openSubMenu(String menuTypeId) { return menuActions.openSubMenu(menuTypeId); }
    @Override public boolean returnToParentMenu() { return menuActions.returnToParentMenu(); }
    @Override public Optional<Integer> openPriorityMenu() { return menuActions.openPriorityMenu(); }
    @Override public boolean sendSetPriority(int priority) { return menuActions.sendSetPriority(priority); }
    @Override public Optional<CrystalAssemblerMenuView> openCrystalAssemblerMenu() {
        return menuActions.openCrystalAssemblerMenu();
    }
    @Override public Optional<CircuitCutterMenuView> openCircuitCutterMenu() {
        return menuActions.openCircuitCutterMenu();
    }
    @Override public Optional<ExInscriberMenuView> openExInscriberMenu() {
        return menuActions.openExInscriberMenu();
    }
    @Override public Optional<PatternProviderMenuView> openPatternProviderMenu() {
        return menuActions.openPatternProviderMenu();
    }
    @Override public Optional<CellTransferMenuView> openExCellTransferMenu() {
        return menuActions.openExCellTransferMenu();
    }
    @Override public Optional<CanerMenuView> openCanerMenu() { return menuActions.openCanerMenu(); }
    @Override public Optional<WirelessHubMenuView> openWirelessHubMenu() {
        return menuActions.openWirelessHubMenu();
    }
    @Override public boolean isAssemblerMatrixMenuOpen() { return menuActions.isAssemblerMatrixMenuOpen(); }

    private static MachineState.Shown toShown(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return new MachineState.Shown(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
    }

    private static boolean readPowered(BlockEntity entity, BlockState state) {
        if (entity instanceof IPowerChannelState channel) return channel.isPowered();
        if (entity instanceof DriveBlockEntity drive) return drive.isPowered();
        for (Property<?> prop : state.getProperties()) {
            if (prop instanceof BooleanProperty flag && "powered".equals(prop.getName())) {
                return state.getValue(flag);
            }
        }
        return true;
    }

    private static boolean readActive(BlockEntity entity, boolean fallback) {
        if (entity instanceof IPowerChannelState channel) return channel.isActive();
        return fallback;
    }
}
