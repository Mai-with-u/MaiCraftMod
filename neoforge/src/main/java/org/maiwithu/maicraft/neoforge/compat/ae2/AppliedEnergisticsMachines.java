// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import appeng.api.implementations.blockentities.ICrankable;
import appeng.api.implementations.items.IAEItemPowerStorage;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.storage.cells.CellState;
import appeng.api.util.AEColor;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.misc.ChargerBlock;
import appeng.block.misc.CrankBlock;
import appeng.block.misc.GrowthAcceleratorBlock;
import appeng.block.misc.InscriberBlock;
import appeng.blockentity.crafting.MolecularAssemblerBlockEntity;
import appeng.blockentity.misc.ChargerBlockEntity;
import appeng.blockentity.misc.ChargerRecipes;
import appeng.blockentity.misc.CrankBlockEntity;
import appeng.blockentity.misc.GrowthAcceleratorBlockEntity;
import appeng.blockentity.misc.VibrationChamberBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.blockentity.storage.SkyStoneTankBlockEntity;
import appeng.items.tools.powered.WirelessCraftingTerminalItem;
import appeng.parts.BusCollisionHelper;
import appeng.parts.reporting.CraftingTerminalPart;
import appeng.recipes.handlers.ChargerRecipe;
import appeng.util.InteractionUtil;
import appeng.util.Platform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.IFluidTank;

import org.maiwithu.maicraft.compat.ae2.machine.Ae2MachineReads;

/**
 * AE2 工作方块与自动合成闭环的 NeoForge 读写端：
 * 直接调用 AE2 公开接口与公开方块实体字段读取世界状态、部件包围盒、充能配方、带电物品电量与合成终端位置；
 * 菜单读数与专有网络发包委托给同包 {@link AppliedEnergisticsMachinesMenus}，全程零反射。
 */
public final class AppliedEnergisticsMachines implements Ae2MachineReads {

    private final AppliedEnergisticsMachinesMenus menus = new AppliedEnergisticsMachinesMenus();

    @Override public Optional<ChargerView> chargerAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof ChargerBlockEntity charger)) return Optional.empty();
        ItemStack slot = charger.getInternalInventory().getStackInSlot(0);
        if (slot.isEmpty()) {
            return Optional.of(new ChargerView(charger.getFront(), charger.isWorking(), "", 0, false, 0, 0));
        }
        String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem()).toString();
        boolean chargeable = Platform.isChargeable(slot) && slot.getItem() instanceof IAEItemPowerStorage;
        double energy = 0;
        double maxEnergy = 0;
        if (chargeable) {
            IAEItemPowerStorage storage = (IAEItemPowerStorage) slot.getItem();
            energy = storage.getAECurrentPower(slot);
            maxEnergy = storage.getAEMaxPower(slot);
        }
        return Optional.of(new ChargerView(charger.getFront(), charger.isWorking(), itemId, slot.getCount(),
                chargeable, energy, maxEnergy));
    }

    // 读取木质曲柄客户端同步视角：除 ICrankable.canTurn() 外，若附着充能器内工具已满电也立刻报告不可摇，防止空摇销毁。
    @Override public Optional<CrankView> crankAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof CrankBlock crankBlock)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof CrankBlockEntity crank)) return Optional.empty();
        Direction facing = crankBlock.getOrientationStrategy().getFacing(state);
        BlockPos attachedTo = at.relative(facing.getOpposite());
        ICrankable crankable = crankBlock.getCrankable(state, level, at);
        boolean canTurn = crankable != null && crankable.canTurn();
        if (canTurn && level.getBlockEntity(attachedTo) instanceof ChargerBlockEntity charger) {
            ItemStack inside = charger.getInternalInventory().getStackInSlot(0);
            if (!inside.isEmpty() && inside.getItem() instanceof IAEItemPowerStorage storage
                    && storage.getAECurrentPower(inside) + 0.01 >= storage.getAEMaxPower(inside)) {
                canTurn = false;
            }
        }
        return Optional.of(new CrankView(facing, attachedTo, crank.getVisibleRotation(), canTurn));
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

    @Override public Optional<VibrationView> vibrationAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof VibrationChamberBlockEntity vibration)) return Optional.empty();
        return Optional.of(new VibrationView(vibration.isOn));
    }

    @Override public Optional<MeChestView> meChestAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof MEChestBlockEntity chest)) return Optional.empty();
        Item cellItem = chest.getCellItem(0);
        String cellId = cellItem == null || cellItem == Items.AIR
                ? "" : BuiltInRegistries.ITEM.getKey(cellItem).toString();
        CellState status = chest.getCellStatus(0);
        String statusName = status == null ? "absent" : status.name().toLowerCase(Locale.ROOT);
        AEColor color = chest.getColor();
        String colorName = color == null ? "transparent" : color.name().toLowerCase(Locale.ROOT);
        return Optional.of(new MeChestView(chest.isPowered(), cellId, statusName, colorName, chest.getTop()));
    }

    @Override public Optional<TankView> skyStoneTankAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof SkyStoneTankBlockEntity tankEntity)) return Optional.empty();
        IFluidTank tank = tankEntity.getTank();
        FluidStack fluid = tank.getFluid();
        String fluidId = fluid.isEmpty() ? "" : BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString();
        return Optional.of(new TankView(fluidId, fluid.getAmount(), tank.getCapacity()));
    }

    @Override public Optional<GrowthView> growthAcceleratorAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof GrowthAcceleratorBlockEntity growth)) return Optional.empty();
        BlockOrientation orientation = growth.getOrientation();
        Direction front = orientation.getSide(RelativeSide.FRONT);
        Direction back = orientation.getSide(RelativeSide.BACK);
        return Optional.of(new GrowthView(growth.isPowered(), front, back));
    }

    @Override public Optional<AssemblerView> molecularAssemblerAt(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        if (!(level.getBlockEntity(at) instanceof MolecularAssemblerBlockEntity assembler)) return Optional.empty();
        return Optional.of(new AssemblerView(assembler.isPowered()));
    }

    @Override public Optional<String> patternProviderPushDirection(ClientLevel level, BlockPos at) {
        if (level == null || !level.isLoaded(at)) return Optional.empty();
        BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof PatternProviderBlock)) return Optional.empty();
        return Optional.of(state.getValue(PatternProviderBlock.PUSH_DIRECTION).getSerializedName());
    }

    // 算出线缆格某面部件在世界坐标中的精确包围盒，供打开部件界面时瞄准部件盒而不是线缆芯。
    @Override public Optional<AABB> partBox(ClientLevel level, BlockPos hostPos, Direction side) {
        if (level == null || hostPos == null || side == null || !level.isLoaded(hostPos)) return Optional.empty();
        if (!(level.getBlockEntity(hostPos) instanceof IPartHost host)) return Optional.empty();
        IPart part = host.getPart(side);
        if (part == null) return Optional.empty();
        List<AABB> boxes = new ArrayList<>();
        part.getBoxes(new BusCollisionHelper(boxes, side, true));
        if (boxes.isEmpty()) return Optional.empty();
        AABB merged = boxes.getFirst();
        for (AABB box : boxes) merged = merged.minmax(box);
        return Optional.of(merged.move(hostPos));
    }

    // 全仓支持 ICrankable 的三类主机：充能器非正面 5 面、压印器非正面 5 面、水晶催生器前后 2 面。
    @Override public Set<Direction> crankAttachableFaces(BlockState hostState, ClientLevel level, BlockPos hostPos) {
        if (hostState == null) return Set.of();
        Block block = hostState.getBlock();
        if (block instanceof ChargerBlock charger) {
            Direction front = charger.getOrientation(hostState).getSide(RelativeSide.FRONT);
            return EnumSet.complementOf(EnumSet.of(front));
        }
        if (block instanceof InscriberBlock inscriber) {
            Direction front = inscriber.getOrientation(hostState).getSide(RelativeSide.FRONT);
            return EnumSet.complementOf(EnumSet.of(front));
        }
        if (block instanceof GrowthAcceleratorBlock growth) {
            BlockOrientation orientation = growth.getOrientation(hostState);
            return EnumSet.of(orientation.getSide(RelativeSide.FRONT), orientation.getSide(RelativeSide.BACK));
        }
        return Set.of();
    }

    @Override public Optional<Direction> crankFacing(BlockState crankState) {
        if (crankState == null || !(crankState.getBlock() instanceof CrankBlock crank)) return Optional.empty();
        return Optional.of(crank.getOrientationStrategy().getFacing(crankState));
    }

    @Override public boolean isWrenchRotateTool(ItemStack stack) {
        return stack != null && !stack.isEmpty() && InteractionUtil.canWrenchRotate(stack);
    }

    @Override public List<CraftingTerminalSpot> craftingTerminalsNear(ClientLevel level, BlockPos center, int radius) {
        if (level == null || center == null || radius < 0) return List.of();
        List<CraftingTerminalSpot> found = new ArrayList<>();
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = center.getY() - radius;
        int maxY = center.getY() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                collectCraftingTerminals(chunk, minX, maxX, minY, maxY, minZ, maxZ, found);
            }
        }
        return List.copyOf(found);
    }

    private static void collectCraftingTerminals(LevelChunk chunk, int minX, int maxX, int minY, int maxY,
            int minZ, int maxZ, List<CraftingTerminalSpot> found) {
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos at = entry.getKey();
            if (at.getX() < minX || at.getX() > maxX || at.getY() < minY || at.getY() > maxY
                    || at.getZ() < minZ || at.getZ() > maxZ) continue;
            if (!(entry.getValue() instanceof IPartHost host)) continue;
            for (Direction side : Direction.values()) {
                IPart part = host.getPart(side);
                if (!(part instanceof CraftingTerminalPart terminal)) continue;
                List<AABB> boxes = new ArrayList<>();
                terminal.getBoxes(new BusCollisionHelper(boxes, side, true));
                if (boxes.isEmpty()) continue;
                AABB merged = boxes.getFirst();
                for (AABB box : boxes) merged = merged.minmax(box);
                String partId = BuiltInRegistries.ITEM.getKey(terminal.getPartItem().asItem()).toString();
                found.add(new CraftingTerminalSpot(at.immutable(), side, merged.move(at), partId));
            }
        }
    }

    @Override public Optional<String> carriedLinkedWirelessCraftingTerminal(Player player) {
        if (player == null) return Optional.empty();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof WirelessCraftingTerminalItem wireless
                    && wireless.getLinkedPosition(stack) != null) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return Optional.empty();
    }

    @Override public Optional<VibrationMenuView> openVibrationMenu() { return menus.openVibrationMenu(); }
    @Override public Optional<CondenserMenuView> openCondenserMenu() { return menus.openCondenserMenu(); }
    @Override public Optional<PatternProviderMenuView> openPatternProviderMenu() { return menus.openPatternProviderMenu(); }
    @Override public Optional<Integer> openPriorityMenu() { return menus.openPriorityMenu(); }
    @Override public boolean sendSetPriority(int priority) { return menus.sendSetPriority(priority); }
    @Override public Optional<AssemblerMenuView> openMolecularAssemblerMenu() { return menus.openMolecularAssemblerMenu(); }
    @Override public Optional<InscriberMenuView> openInscriberMenu() { return menus.openInscriberMenu(); }
    @Override public Optional<CraftingCpuMenuView> openCraftingCpuMenu() { return menus.openCraftingCpuMenu(); }
    @Override public boolean sendCpuCancel() { return menus.sendCpuCancel(); }
    @Override public boolean sendCpuTogglePause() { return menus.sendCpuTogglePause(); }
    @Override public boolean sendCpuSelect(int serial) { return menus.sendCpuSelect(serial); }
    @Override public Optional<PatternEncodingMenuView> openPatternEncodingMenu() { return menus.openPatternEncodingMenu(); }
    @Override public boolean sendEncodeMode(String mode) { return menus.sendEncodeMode(mode); }
    @Override public boolean sendEncodeSubstitute(boolean substitute) { return menus.sendEncodeSubstitute(substitute); }
    @Override public boolean sendEncodeSubstituteFluids(boolean substituteFluids) { return menus.sendEncodeSubstituteFluids(substituteFluids); }
    @Override public boolean sendEncodeStonecuttingRecipe(String recipeId) { return menus.sendEncodeStonecuttingRecipe(recipeId); }
    @Override public boolean sendEncodeClear() { return menus.sendEncodeClear(); }
    @Override public boolean sendEncodeCommit() { return menus.sendEncodeCommit(); }
    @Override public ItemStack wrapGenericStack(String kind, String id, long amount) { return menus.wrapGenericStack(kind, id, amount); }
    @Override public Optional<PatternAccessMenuView> openPatternAccessMenu() { return menus.openPatternAccessMenu(); }
    @Override public boolean sendQuickMovePattern(int containerId, int playerSlotIndex, List<Long> allowedServerIds) {
        return menus.sendQuickMovePattern(containerId, playerSlotIndex, allowedServerIds);
    }
    @Override public Optional<CraftingTermMenuView> openCraftingTermMenu() { return menus.openCraftingTermMenu(); }
    @Override public boolean sendFillCraftingGrid(String recipeId, List<ItemStack> templates9) {
        return menus.sendFillCraftingGrid(recipeId, templates9);
    }
    @Override public boolean sendCraftResult(int outputSlotIndex, boolean shift) {
        return menus.sendCraftResult(outputSlotIndex, shift);
    }
    @Override public boolean sendClearCraftingGrid(boolean toPlayerInventory) {
        return menus.sendClearCraftingGrid(toPlayerInventory);
    }
}
