// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import appeng.api.config.CondenserOutput;
import appeng.api.config.CpuSelectionMode;
import appeng.api.config.InscriberInputCapacity;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.ShowPatternProviders;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.patternaccess.PatternContainerRecord;
import appeng.client.gui.me.patternaccess.PatternSlot;
import appeng.core.network.serverbound.FillCraftingGridFromRecipePacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.core.network.serverbound.QuickMovePatternPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.CondenserMenu;
import appeng.menu.implementations.InscriberMenu;
import appeng.menu.implementations.MolecularAssemblerMenu;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.implementations.VibrationChamberMenu;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatusMenu;
import appeng.menu.me.items.CraftingTermMenu;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.EncodingMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.network.PacketDistributor;

import org.maiwithu.maicraft.compat.ae2.machine.Ae2MachineReads;

/**
 * AE2 工作方块与自动合成菜单的客户端读写端：
 * 全部通过 AE2 公开菜单 getter、@GuiSync 公开字段、公开 clientAction 方法与公开网络包交互，
 * 绝不使用反射访问任何 Screen 私有字段（包括 PatternAccessTermScreen 与 CraftingCPUScreen）。
 */
final class AppliedEnergisticsMachinesMenus {

    Optional<Ae2MachineReads.VibrationMenuView> openVibrationMenu() {
        if (!(openMenu() instanceof VibrationChamberMenu menu)) return Optional.empty();
        ItemStack fuel = firstSlotStack(menu, SlotSemantics.MACHINE_INPUT);
        String fuelId = fuel.isEmpty() ? "" : itemId(fuel);
        return Optional.of(new Ae2MachineReads.VibrationMenuView(
                menu.getRemainingBurnTime(), menu.getPowerPerTick(), menu.getFuelEfficiency(),
                fuelId, fuel.getCount(), upgradeIds(menu)));
    }

    Optional<Ae2MachineReads.CondenserMenuView> openCondenserMenu() {
        if (!(openMenu() instanceof CondenserMenu menu)) return Optional.empty();
        ItemStack comp = firstSlotStack(menu, SlotSemantics.STORAGE_CELL);
        ItemStack out = firstSlotStack(menu, SlotSemantics.MACHINE_OUTPUT);
        return Optional.of(new Ae2MachineReads.CondenserMenuView(
                condenserModeName(menu.getOutput()),
                menu.storedPower, menu.requiredEnergy,
                comp.isEmpty() ? "" : itemId(comp),
                out.isEmpty() ? "" : itemId(out),
                out.getCount()));
    }

    Optional<Ae2MachineReads.PatternProviderMenuView> openPatternProviderMenu() {
        if (!(openMenu() instanceof PatternProviderMenu menu)) return Optional.empty();
        GenericStack unlock = menu.getUnlockStack();
        String unlockText = unlock == null ? "" : unlock.what().getId() + " ×" + unlock.amount();
        return Optional.of(new Ae2MachineReads.PatternProviderMenuView(
                yesNoName(menu.getBlockingMode()),
                lockModeName(menu.getLockCraftingMode()),
                yesNoName(menu.getShowInAccessTerminal()),
                lockModeName(menu.getCraftingLockedReason()),
                unlockText));
    }

    Optional<Integer> openPriorityMenu() {
        if (!(openMenu() instanceof PriorityMenu menu)) return Optional.empty();
        return Optional.of(menu.getPriorityValue());
    }

    boolean sendSetPriority(int priority) {
        if (!(openMenu() instanceof PriorityMenu menu)) return false;
        menu.setPriority(priority);
        return true;
    }

    Optional<Ae2MachineReads.AssemblerMenuView> openMolecularAssemblerMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.containerMenu instanceof MolecularAssemblerMenu menu)) return Optional.empty();
        ItemStack pat = firstSlotStack(menu, SlotSemantics.ENCODED_PATTERN);
        String outId = "";
        if (!pat.isEmpty() && PatternDetailsHelper.isEncodedPattern(pat)) {
            IPatternDetails decoded = PatternDetailsHelper.decodePattern(pat, player.level());
            if (decoded != null && decoded.getPrimaryOutput() != null) {
                outId = decoded.getPrimaryOutput().what().getId().toString();
            }
        }
        return Optional.of(new Ae2MachineReads.AssemblerMenuView(
                menu.getCurrentProgress(), !pat.isEmpty(), outId, upgradeIds(menu)));
    }

    Optional<Ae2MachineReads.InscriberMenuView> openInscriberMenu() {
        if (!(openMenu() instanceof InscriberMenu menu)) return Optional.empty();
        InscriberInputCapacity cap = menu.getBufferSize();
        String capText = cap == null ? "64" : Integer.toString(cap.capacity);
        return Optional.of(new Ae2MachineReads.InscriberMenuView(
                yesNoName(menu.getSeparateSides()),
                yesNoName(menu.getAutoExport()),
                capText,
                menu.getCurrentProgress(), menu.getMaxProgress(),
                upgradeIds(menu)));
    }

    Optional<Ae2MachineReads.CraftingCpuMenuView> openCraftingCpuMenu() {
        AbstractContainerMenu raw = openMenu();
        if (!(raw instanceof CraftingCPUMenu cpuMenu)) return Optional.empty();
        boolean isStatus = raw instanceof CraftingStatusMenu;
        int selected = -1;
        List<Ae2MachineReads.CpuEntryView> list = List.of();
        if (raw instanceof CraftingStatusMenu statusMenu) {
            selected = statusMenu.getSelectedCpuSerial();
            if (statusMenu.cpuList != null && statusMenu.cpuList.cpus() != null) {
                List<Ae2MachineReads.CpuEntryView> entries = new ArrayList<>();
                for (CraftingStatusMenu.CraftingCpuListEntry e : statusMenu.cpuList.cpus()) {
                    String job = e.currentJob() == null ? ""
                            : e.currentJob().what().getId() + " ×" + e.currentJob().amount();
                    String name = e.name() == null ? "" : e.name().getString();
                    entries.add(new Ae2MachineReads.CpuEntryView(e.serial(), e.storage(), e.coProcessors(),
                            name, cpuModeName(e.mode()), job, e.progress(), e.elapsedTimeNanos()));
                }
                list = List.copyOf(entries);
            }
        }
        return Optional.of(new Ae2MachineReads.CraftingCpuMenuView(
                cpuModeName(cpuMenu.getSchedulingMode()),
                cpuMenu.isCantStoreItems(), isStatus, selected, list));
    }

    boolean sendCpuCancel() {
        if (!(openMenu() instanceof CraftingCPUMenu menu)) return false;
        menu.cancelCrafting();
        return true;
    }

    boolean sendCpuTogglePause() {
        if (!(openMenu() instanceof CraftingCPUMenu menu)) return false;
        menu.toggleScheduling();
        return true;
    }

    boolean sendCpuSelect(int serial) {
        if (!(openMenu() instanceof CraftingStatusMenu menu)) return false;
        menu.selectCpu(serial);
        return true;
    }

    Optional<Ae2MachineReads.PatternEncodingMenuView> openPatternEncodingMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.containerMenu instanceof PatternEncodingTermMenu menu)) return Optional.empty();
        ItemStack craftResult = firstSlotStack(menu, SlotSemantics.CRAFTING_RESULT);
        ItemStack blank = firstSlotStack(menu, SlotSemantics.BLANK_PATTERN);
        ItemStack encoded = firstSlotStack(menu, SlotSemantics.ENCODED_PATTERN);
        String encodedOut = "";
        if (!encoded.isEmpty() && PatternDetailsHelper.isEncodedPattern(encoded)) {
            IPatternDetails decoded = PatternDetailsHelper.decodePattern(encoded, player.level());
            if (decoded != null && decoded.getPrimaryOutput() != null) {
                encodedOut = decoded.getPrimaryOutput().what().getId().toString();
            }
        }
        List<String> stoneRecipes = new ArrayList<>();
        for (RecipeHolder<StonecutterRecipe> holder : menu.getStonecuttingRecipes()) {
            stoneRecipes.add(holder.id().toString());
        }
        boolean procIn = menu.getSlots(SlotSemantics.PROCESSING_INPUTS).stream().anyMatch(s -> !s.getItem().isEmpty());
        List<Slot> procOutSlots = menu.getSlots(SlotSemantics.PROCESSING_OUTPUTS);
        boolean procPrimaryOut = !procOutSlots.isEmpty() && !procOutSlots.getFirst().getItem().isEmpty();
        boolean smithingValid = isSmithingValid(menu, player.level());
        return Optional.of(new Ae2MachineReads.PatternEncodingMenuView(
                encodingModeName(menu.getMode()),
                menu.isSubstitute(), menu.isSubstituteFluids(),
                menu.getStonecuttingRecipeId() == null ? "" : menu.getStonecuttingRecipeId().toString(),
                List.copyOf(stoneRecipes),
                craftResult.isEmpty() ? "" : itemId(craftResult),
                blank.getCount(), encodedOut, !encoded.isEmpty(),
                procIn, procPrimaryOut, smithingValid));
    }

    // 核对锻造台三槽是否匹配真实锻造配方，防止无效配方调 encode() 洗掉旧样板。
    private static boolean isSmithingValid(PatternEncodingTermMenu menu, Level level) {
        ItemStack template = firstSlotStack(menu, SlotSemantics.SMITHING_TABLE_TEMPLATE);
        ItemStack base = firstSlotStack(menu, SlotSemantics.SMITHING_TABLE_BASE);
        ItemStack addition = firstSlotStack(menu, SlotSemantics.SMITHING_TABLE_ADDITION);
        if (template.isEmpty() || base.isEmpty() || addition.isEmpty() || level == null) return false;
        SmithingRecipeInput input = new SmithingRecipeInput(template, base, addition);
        return level.getRecipeManager().getRecipeFor(RecipeType.SMITHING, input, level).isPresent();
    }

    boolean sendEncodeMode(String mode) {
        if (!(openMenu() instanceof PatternEncodingTermMenu menu)) return false;
        EncodingMode parsed = parseEncodingMode(mode);
        if (parsed == null) return false;
        menu.setMode(parsed);
        return true;
    }

    boolean sendEncodeSubstitute(boolean substitute) {
        if (!(openMenu() instanceof PatternEncodingTermMenu menu)) return false;
        menu.setSubstitute(substitute);
        return true;
    }

    boolean sendEncodeSubstituteFluids(boolean substituteFluids) {
        if (!(openMenu() instanceof PatternEncodingTermMenu menu)) return false;
        menu.setSubstituteFluids(substituteFluids);
        return true;
    }

    // 经 AE2 公开 menu.setStonecuttingRecipeId(ResourceLocation) 发包，走 AE2 原生 Gson 对象序列化绝不发字符串。
    boolean sendEncodeStonecuttingRecipe(String recipeId) {
        if (!(openMenu() instanceof PatternEncodingTermMenu menu)) return false;
        ResourceLocation rl = ResourceLocation.tryParse(recipeId);
        if (rl == null) return false;
        menu.setStonecuttingRecipeId(rl);
        return true;
    }

    boolean sendEncodeClear() {
        if (!(openMenu() instanceof PatternEncodingTermMenu menu)) return false;
        menu.clear();
        return true;
    }

    boolean sendEncodeCommit() {
        if (!(openMenu() instanceof PatternEncodingTermMenu menu)) return false;
        menu.encode();
        return true;
    }

    // PROCESSING 模式支持流体与大于 64 的数量，使用 GenericStack.wrapInItemStack 包装后发 SET_FILTER。
    ItemStack wrapGenericStack(String kind, String id, long amount) {
        if (id == null || id.isBlank() || amount <= 0) return ItemStack.EMPTY;
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return ItemStack.EMPTY;
        if ("fluid".equalsIgnoreCase(kind)) {
            Fluid fluid = BuiltInRegistries.FLUID.getOptional(key).orElse(Fluids.EMPTY);
            if (fluid == Fluids.EMPTY) return ItemStack.EMPTY;
            return GenericStack.wrapInItemStack(new GenericStack(AEFluidKey.of(fluid), amount));
        }
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(Items.AIR);
        if (item == Items.AIR) return ItemStack.EMPTY;
        return GenericStack.wrapInItemStack(new GenericStack(AEItemKey.of(item), amount));
    }

    // 仅从菜单当前可见的公开 PatternSlot 读取 PatternContainerRecord，绝不反射 PatternAccessTermScreen。
    Optional<Ae2MachineReads.PatternAccessMenuView> openPatternAccessMenu() {
        if (!(openMenu() instanceof PatternAccessTermMenu menu)) return Optional.empty();
        Map<Long, Ae2MachineReads.VisiblePatternContainer> seen = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (!(slot instanceof PatternSlot patternSlot)) continue;
            PatternContainerRecord rec = patternSlot.getMachineInv();
            if (rec == null || seen.containsKey(rec.getServerId())) continue;
            int total = rec.getInventory().size();
            int occupied = 0;
            for (int i = 0; i < total; i++) {
                if (!rec.getInventory().getStackInSlot(i).isEmpty()) occupied++;
            }
            String groupName = rec.getGroup() == null || rec.getGroup().name() == null
                    ? "" : rec.getGroup().name().getString();
            seen.put(rec.getServerId(), new Ae2MachineReads.VisiblePatternContainer(
                    rec.getServerId(), groupName, total, occupied));
        }
        return Optional.of(new Ae2MachineReads.PatternAccessMenuView(
                menu.containerId, showProvidersName(menu.getShownProviders()),
                menu.getLinkStatus().connected(), List.copyOf(seen.values())));
    }

    boolean sendQuickMovePattern(int containerId, int playerSlotIndex, List<Long> allowedServerIds) {
        if (!(openMenu() instanceof PatternAccessTermMenu menu)) return false;
        if (menu.containerId != containerId || allowedServerIds == null || allowedServerIds.isEmpty()) return false;
        PacketDistributor.sendToServer(new QuickMovePatternPacket(containerId, playerSlotIndex, allowedServerIds));
        return true;
    }

    Optional<Ae2MachineReads.CraftingTermMenuView> openCraftingTermMenu() {
        if (!(openMenu() instanceof CraftingTermMenu menu)) return Optional.empty();
        ItemStack result = firstSlotStack(menu, SlotSemantics.CRAFTING_RESULT);
        boolean gridOccupied = menu.getSlots(SlotSemantics.CRAFTING_GRID).stream().anyMatch(s -> !s.getItem().isEmpty());
        return Optional.of(new Ae2MachineReads.CraftingTermMenuView(
                menu.getLinkStatus().connected(),
                result.isEmpty() ? "" : itemId(result),
                result.getCount(), gridOccupied));
    }

    // 严格传 craftMissing = false，防止缺料时服务端强行切页到 CraftConfirmMenu 触发意外自动合成下单。
    boolean sendFillCraftingGrid(String recipeId, List<ItemStack> templates9) {
        if (!(openMenu() instanceof CraftingTermMenu)) return false;
        NonNullList<ItemStack> list = NonNullList.withSize(9, ItemStack.EMPTY);
        if (templates9 != null) {
            for (int i = 0; i < Math.min(9, templates9.size()); i++) {
                ItemStack stack = templates9.get(i);
                list.set(i, stack == null ? ItemStack.EMPTY : stack.copy());
            }
        }
        ResourceLocation rl = recipeId == null || recipeId.isBlank() ? null : ResourceLocation.tryParse(recipeId);
        PacketDistributor.sendToServer(new FillCraftingGridFromRecipePacket(rl, list, false));
        return true;
    }

    // CraftingTermSlot.mayPickup 恒为 false，必须经 InventoryActionPacket(CRAFT_SHIFT/CRAFT_ITEM) 取产物。
    boolean sendCraftResult(int outputSlotIndex, boolean shift) {
        if (!(openMenu() instanceof CraftingTermMenu menu)) return false;
        if (outputSlotIndex < 0 || outputSlotIndex >= menu.slots.size()) return false;
        InventoryAction action = shift ? InventoryAction.CRAFT_SHIFT : InventoryAction.CRAFT_ITEM;
        PacketDistributor.sendToServer(new InventoryActionPacket(action, outputSlotIndex, 0));
        return true;
    }

    boolean sendClearCraftingGrid(boolean toPlayerInventory) {
        if (!(openMenu() instanceof CraftingTermMenu menu)) return false;
        if (toPlayerInventory) menu.clearToPlayerInventory();
        else menu.clearCraftingGrid();
        return true;
    }

    private static AbstractContainerMenu openMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : player.containerMenu;
    }

    private static ItemStack firstSlotStack(AEBaseMenu menu, SlotSemantic semantic) {
        List<Slot> slots = menu.getSlots(semantic);
        return slots.isEmpty() ? ItemStack.EMPTY : slots.getFirst().getItem();
    }

    private static List<String> upgradeIds(AEBaseMenu menu) {
        List<Slot> slots = menu.getSlots(SlotSemantics.UPGRADE);
        List<String> list = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            list.add(stack.isEmpty() ? "empty" : itemId(stack));
        }
        return List.copyOf(list);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String yesNoName(YesNo v) {
        return v == YesNo.YES ? "yes" : "no";
    }

    private static String condenserModeName(CondenserOutput output) {
        if (output == null) return "trash";
        return switch (output) {
            case TRASH -> "trash";
            case MATTER_BALLS -> "matter_balls";
            case SINGULARITY -> "singularity";
        };
    }

    private static String lockModeName(LockCraftingMode mode) {
        if (mode == null) return "none";
        return switch (mode) {
            case NONE -> "none";
            case LOCK_UNTIL_PULSE -> "until_pulse";
            case LOCK_WHILE_HIGH -> "while_high";
            case LOCK_WHILE_LOW -> "while_low";
            case LOCK_UNTIL_RESULT -> "until_result";
        };
    }

    private static String cpuModeName(CpuSelectionMode mode) {
        if (mode == null) return "any";
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static String showProvidersName(ShowPatternProviders shown) {
        if (shown == null) return "visible";
        return shown.name().toLowerCase(Locale.ROOT);
    }

    private static String encodingModeName(EncodingMode mode) {
        if (mode == null) return "crafting";
        return switch (mode) {
            case CRAFTING -> "crafting";
            case PROCESSING -> "processing";
            case SMITHING_TABLE -> "smithing";
            case STONECUTTING -> "stonecutting";
        };
    }

    private static EncodingMode parseEncodingMode(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "crafting" -> EncodingMode.CRAFTING;
            case "processing" -> EncodingMode.PROCESSING;
            case "smithing", "smithing_table" -> EncodingMode.SMITHING_TABLE;
            case "stonecutting" -> EncodingMode.STONECUTTING;
            default -> null;
        };
    }
}
