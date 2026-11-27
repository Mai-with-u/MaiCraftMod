// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.extendedae;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import appeng.api.config.FullnessMode;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.OperationMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.core.network.serverbound.SwitchGuisPacket;
import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.slot.AppEngSlot;
import com.glodblock.github.extendedae.api.CanerMode;
import com.glodblock.github.extendedae.common.me.wireless.WirelessStatus;
import com.glodblock.github.extendedae.container.ContainerAssemblerMatrix;
import com.glodblock.github.extendedae.container.ContainerCaner;
import com.glodblock.github.extendedae.container.ContainerCircuitCutter;
import com.glodblock.github.extendedae.container.ContainerCrystalAssembler;
import com.glodblock.github.extendedae.container.ContainerExIOPort;
import com.glodblock.github.extendedae.container.ContainerExInscriber;
import com.glodblock.github.extendedae.container.ContainerWirelessHub;
import com.glodblock.github.extendedae.network.EAENetworkHandler;
import com.glodblock.github.extendedae.network.packet.CEAEGenericPacket;
import com.glodblock.github.extendedae.network.packet.CUpdatePage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import org.maiwithu.maicraft.compat.extendedae.ExtendedAeReads;

/**
 * ExtendedAE 菜单槽位语义查询、界面同步字段读取与原生网络包发送的 NeoForge 实现：
 * 全部通过公开方法、公开同步字段与公开网络包交互，绝不使用反射。
 */
public final class ExtendedAeMenuActions {

    private static final long CRYSTAL_ASSEMBLER_TANK_CAP = 16000L;

    public boolean isMenuOpen() {
        return openAeMenu() != null;
    }

    // 仅返回 AppEngSlot.isSlotEnabled() 为真的槽位编号，使分页界面只暴露当前启用页的槽位。
    public List<Integer> enabledSlotsOf(String semanticId) {
        return slotsOf(semanticId, true);
    }

    public List<Integer> allSlotsOf(String semanticId) {
        return slotsOf(semanticId, false);
    }

    private List<Integer> slotsOf(String semanticId, boolean enabledOnly) {
        if (semanticId == null || semanticId.isEmpty()) return List.of();
        AEBaseMenu menu = openAeMenu();
        if (menu == null) return List.of();
        SlotSemantic semantic = SlotSemantics.get(semanticId);
        if (semantic == null) return List.of();
        List<Slot> slots = menu.getSlots(semantic);
        if (slots.isEmpty()) return List.of();
        List<Integer> indices = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (enabledOnly && slot instanceof AppEngSlot aeSlot && !aeSlot.isSlotEnabled()) continue;
            indices.add(slot.index);
        }
        return List.copyOf(indices);
    }

    public boolean sendConfigButton(String settingName, boolean backwards) {
        if (settingName == null || settingName.isEmpty() || openAeMenu() == null) return false;
        Setting<?> setting;
        try {
            setting = Settings.getOrThrow(settingName);
        } catch (IllegalArgumentException unknown) {
            return false;
        }
        PacketDistributor.sendToServer(new ConfigButtonPacket(setting, backwards));
        return true;
    }

    public boolean sendGenericAction(String action, Object... args) {
        if (action == null || action.isEmpty() || openAeMenu() == null) return false;
        Object[] safeArgs = args == null ? new Object[0] : args;
        EAENetworkHandler.INSTANCE.sendToServer(new CEAEGenericPacket(action, safeArgs));
        return true;
    }

    public boolean sendUpdatePage(int page) {
        if (openAeMenu() == null) return false;
        EAENetworkHandler.INSTANCE.sendToServer(new CUpdatePage(page));
        return true;
    }

    // 水晶组装器储罐倒桶走 AE2 原生 InventoryActionPacket(EMPTY_ITEM, tankSlotIndex, 0)。
    public boolean sendEmptyItemToTank(int tankSlotIndex) {
        AEBaseMenu menu = openAeMenu();
        if (menu == null || tankSlotIndex < 0 || tankSlotIndex >= menu.slots.size()) return false;
        PacketDistributor.sendToServer(new InventoryActionPacket(InventoryAction.EMPTY_ITEM, tankSlotIndex, 0));
        return true;
    }

    public boolean openSubMenu(String menuTypeId) {
        if (menuTypeId == null || menuTypeId.isEmpty() || openAeMenu() == null) return false;
        ResourceLocation key = ResourceLocation.tryParse(menuTypeId);
        if (key == null) return false;
        Optional<MenuType<?>> found = BuiltInRegistries.MENU.getOptional(key);
        if (found.isEmpty()) return false;
        @SuppressWarnings("unchecked")
        MenuType<? extends ISubMenu> sub = (MenuType<? extends ISubMenu>) found.get();
        PacketDistributor.sendToServer(SwitchGuisPacket.openSubMenu(sub));
        return true;
    }

    public boolean returnToParentMenu() {
        AEBaseMenu menu = openAeMenu();
        if (!(menu instanceof ISubMenu)) return false;
        PacketDistributor.sendToServer(SwitchGuisPacket.returnToParentMenu());
        return true;
    }

    public Optional<Integer> openPriorityMenu() {
        if (!(openRawMenu() instanceof PriorityMenu menu)) return Optional.empty();
        return Optional.of(menu.getPriorityValue());
    }

    public boolean sendSetPriority(int priority) {
        if (!(openRawMenu() instanceof PriorityMenu menu)) return false;
        menu.setPriority(priority);
        return true;
    }

    public Optional<ExtendedAeReads.CrystalAssemblerMenuView> openCrystalAssemblerMenu() {
        if (!(openRawMenu() instanceof ContainerCrystalAssembler menu)) return Optional.empty();
        // processingTime 构造初始为 -1，服务端首个同步包到达后才会变为非负；未同步前先返回空继续等待。
        if (menu.getCurrentProgress() < 0) return Optional.empty();
        int tankSlotIndex = -1;
        String tankFluidId = "";
        long tankAmount = 0L;
        for (Slot slot : menu.slots) {
            if (menu.isTank(slot)) {
                tankSlotIndex = slot.index;
                GenericStack unwrapped = GenericStack.unwrapItemStack(slot.getItem());
                if (unwrapped != null && unwrapped.what() instanceof AEFluidKey fluidKey) {
                    tankFluidId = fluidKey.getId().toString();
                    tankAmount = unwrapped.amount();
                }
                break;
            }
        }
        List<String> sides = directionNames(menu.getOutputSides());
        return Optional.of(new ExtendedAeReads.CrystalAssemblerMenuView(
                menu.getCurrentProgress(), menu.getMaxProgress(), yesNoName(menu.getAutoExport()),
                sides, tankSlotIndex, tankFluidId, tankAmount, CRYSTAL_ASSEMBLER_TANK_CAP, upgradeIds(menu)));
    }

    public Optional<ExtendedAeReads.CircuitCutterMenuView> openCircuitCutterMenu() {
        if (!(openRawMenu() instanceof ContainerCircuitCutter menu)) return Optional.empty();
        // processingTime 构造初始为 -1，等待首个服务端同步包到达后再读配置与输出面。
        if (menu.getCurrentProgress() < 0) return Optional.empty();
        return Optional.of(new ExtendedAeReads.CircuitCutterMenuView(
                menu.getCurrentProgress(), menu.getMaxProgress(), yesNoName(menu.getAutoExport()),
                directionNames(menu.getOutputSides()), upgradeIds(menu)));
    }

    public Optional<ExtendedAeReads.ExInscriberMenuView> openExInscriberMenu() {
        if (!(openRawMenu() instanceof ContainerExInscriber menu)) return Optional.empty();
        // maxProcessingTime 构造初始为 -1，未收到首个服务端同步包前先等待，避免默认 NO/0 误判。
        if (menu.getMaxProgress() < 0) return Optional.empty();
        int page = menu.getPage();
        boolean enabled = areInscriberPageSlotsEnabled(menu, page);
        return Optional.of(new ExtendedAeReads.ExInscriberMenuView(
                page, yesNoName(menu.getSeparateSides()), yesNoName(menu.getAutoExport()),
                menu.getStackMode(), menu.getCurrentProgress(), menu.getMaxProgress(),
                upgradeIds(menu), enabled));
    }

    public Optional<ExtendedAeReads.PatternProviderMenuView> openPatternProviderMenu() {
        if (!(openRawMenu() instanceof PatternProviderMenu menu)) return Optional.empty();
        return Optional.of(new ExtendedAeReads.PatternProviderMenuView(
                yesNoName(menu.getBlockingMode()),
                lockModeName(menu.getLockCraftingMode()),
                yesNoName(menu.getShowInAccessTerminal())));
    }

    public Optional<ExtendedAeReads.CellTransferMenuView> openExCellTransferMenu() {
        if (!(openRawMenu() instanceof ContainerExIOPort menu)) return Optional.empty();
        return Optional.of(new ExtendedAeReads.CellTransferMenuView(
                fullnessName(menu.getFullMode()), operationName(menu.getOperationMode()), upgradeIds(menu)));
    }

    public Optional<ExtendedAeReads.CanerMenuView> openCanerMenu() {
        if (!(openRawMenu() instanceof ContainerCaner menu)) return Optional.empty();
        CanerMode mode = menu.getMode();
        String name = mode == null ? "fill" : mode.name().toLowerCase(Locale.ROOT);
        return Optional.of(new ExtendedAeReads.CanerMenuView(name));
    }

    public Optional<ExtendedAeReads.WirelessHubMenuView> openWirelessHubMenu() {
        if (!(openRawMenu() instanceof ContainerWirelessHub menu)) return Optional.empty();
        List<ExtendedAeReads.HubLinkView> links = new ArrayList<>(ExtendedAeReads.WIRELESS_HUB_SLOTS);
        for (int i = 0; i < ExtendedAeReads.WIRELESS_HUB_SLOTS; i++) {
            WirelessStatus status = menu.getStatus(i);
            String statusName = status == null ? "unconnected" : status.name().toLowerCase(Locale.ROOT);
            BlockPos remote = menu.getRemotePosition(i);
            String posText = remote == null || status == WirelessStatus.UNCONNECTED ? "" : remote.toShortString();
            links.add(new ExtendedAeReads.HubLinkView(i, statusName, posText, menu.getRemoteChannel(i)));
        }
        return Optional.of(new ExtendedAeReads.WirelessHubMenuView(
                menu.powerUse, menu.usedChannel, menu.maxChannel, links));
    }

    public boolean isAssemblerMatrixMenuOpen() {
        return openRawMenu() instanceof ContainerAssemblerMatrix;
    }

    private static boolean areInscriberPageSlotsEnabled(ContainerExInscriber menu, int page) {
        String[] semantics = switch (Math.max(0, Math.min(3, page))) {
            case 1 -> new String[]{"EX_1", "EX_4", "EX_7", "EX_10"};
            case 2 -> new String[]{"EX_2", "EX_5", "EX_8", "EX_11"};
            case 3 -> new String[]{"EX_3", "EX_6", "EX_9", "EX_12"};
            default -> new String[]{"INSCRIBER_PLATE_TOP", "INSCRIBER_PLATE_BOTTOM",
                    "MACHINE_INPUT", "MACHINE_OUTPUT"};
        };
        for (String id : semantics) {
            SlotSemantic sem = SlotSemantics.get(id);
            if (sem == null) return false;
            List<Slot> slots = menu.getSlots(sem);
            if (slots.isEmpty()) return false;
            if (slots.getFirst() instanceof AppEngSlot ae && !ae.isSlotEnabled()) return false;
        }
        return true;
    }

    private static AEBaseMenu openAeMenu() {
        AbstractContainerMenu raw = openRawMenu();
        return raw instanceof AEBaseMenu menu ? menu : null;
    }

    private static AbstractContainerMenu openRawMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : player.containerMenu;
    }

    private static List<String> upgradeIds(AEBaseMenu menu) {
        List<Slot> slots = menu.getSlots(SlotSemantics.UPGRADE);
        List<String> list = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            list.add(stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return List.copyOf(list);
    }

    private static List<String> directionNames(List<Direction> dirs) {
        if (dirs == null || dirs.isEmpty()) return List.of();
        List<String> names = new ArrayList<>(dirs.size());
        for (Direction dir : dirs) {
            if (dir != null) names.add(dir.getSerializedName());
        }
        return List.copyOf(names);
    }

    private static String yesNoName(YesNo v) {
        return v == YesNo.YES ? "yes" : "no";
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

    private static String fullnessName(FullnessMode mode) {
        if (mode == null) return "empty";
        return switch (mode) {
            case EMPTY -> "empty";
            case HALF -> "half";
            case FULL -> "full";
        };
    }

    private static String operationName(OperationMode mode) {
        if (mode == null) return "empty";
        return mode == OperationMode.FILL ? "fill" : "empty";
    }
}
