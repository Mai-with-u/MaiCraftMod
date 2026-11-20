// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import appeng.api.behaviors.ContainerItemStrategies;
import appeng.api.config.FuzzyMode;
import appeng.api.config.YesNo;
import appeng.api.features.P2PTunnelAttunement;
import appeng.api.ids.AEComponents;
import appeng.api.implementations.items.IMemoryCard;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.core.definitions.AEItems;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.implementations.CellWorkbenchMenu;
import appeng.menu.implementations.EnergyLevelEmitterMenu;
import appeng.menu.implementations.FormationPlaneMenu;
import appeng.menu.implementations.IOBusMenu;
import appeng.menu.implementations.IOPortMenu;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.implementations.SetStockAmountMenu;
import appeng.menu.implementations.StorageBusMenu;
import appeng.menu.implementations.StorageLevelEmitterMenu;
import appeng.menu.implementations.UpgradeableMenu;
import appeng.menu.implementations.WirelessAccessPointMenu;
import appeng.menu.interfaces.KeyTypeSelectionMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.parts.BusCollisionHelper;
import appeng.parts.p2p.P2PTunnelPart;
import appeng.parts.reporting.AbstractMonitorPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.compat.ae2.part.Ae2PartControl;

/**
 * AE2 线缆部件、元件工作台、ME IO 传输站与无线访问点的 NeoForge 客户端读写端。
 *
 * <p>全部直接调用 AE2 公开接口与公开数据包，不使用反射；只负责把 AE2 部件、菜单与物品状态
 * 翻译为纯 Minecraft / Java 记录交给通用层判断与驱动。
 */
public final class AppliedEnergisticsParts implements Ae2PartControl {

    // 按部件自身报告的碰撞形状拼出世界坐标下的交互包围盒，避免右键误点中心线缆或邻面部件。
    @Override public Optional<AABB> partBox(ClientLevel level, BlockPos host, Direction side) {
        if (level == null || host == null || side == null || !level.isLoaded(host)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(host);
        if (!(entity instanceof IPartHost partHost)) return Optional.empty();
        IPart part = partHost.getPart(side);
        if (part == null) return Optional.empty();
        List<AABB> boxes = new ArrayList<>();
        part.getBoxes(new BusCollisionHelper(boxes, side, true));
        if (boxes.isEmpty()) return Optional.empty();
        AABB union = boxes.getFirst();
        for (AABB box : boxes) {
            union = union.minmax(box);
        }
        return Optional.of(union.move(host));
    }

    // 读取客户端方块流已同步的部件信息：监视器（全状态）与 P2P 通道（仅频率）。
    @Override public Optional<ClientPartReading> readClientPart(ClientLevel level, BlockPos host, Direction side) {
        if (level == null || host == null || side == null || !level.isLoaded(host)) return Optional.empty();
        BlockEntity entity = level.getBlockEntity(host);
        if (!(entity instanceof IPartHost partHost)) return Optional.empty();
        IPart part = partHost.getPart(side);
        if (part instanceof AbstractMonitorPart monitor) {
            AEKey displayed = monitor.getDisplayed();
            String id = displayed == null ? null : displayed.getId().toString();
            return Optional.of(ClientPartReading.monitor(id, monitor.getAmount(), monitor.canCraft(),
                    monitor.isLocked()));
        }
        if (part instanceof P2PTunnelPart<?> tunnel) {
            return Optional.of(ClientPartReading.p2p(Short.toUnsignedInt(tunnel.getFrequency())));
        }
        return Optional.empty();
    }

    // 汇总当前打开的 AE2 菜单里全部同步设置、子菜单数值与各语义槽位，彻底避免硬编码槽位索引。
    @Override public Optional<OpenMenuSnapshot> readOpenMenu() {
        AEBaseMenu menu = openMenu();
        if (menu == null) return Optional.empty();
        String typeId = menuTypeIdOf(menu);
        Map<String, String> settings = new LinkedHashMap<>();
        Map<String, Boolean> keyTypes = new LinkedHashMap<>();
        String connectedTo = null;
        OptionalLong reporting = OptionalLong.empty();
        OptionalInt priority = OptionalInt.empty();
        OptionalInt stockInitial = OptionalInt.empty();
        OptionalInt stockMax = OptionalInt.empty();
        boolean hasRedstoneCard = false;
        OptionalInt wirelessBoosters = OptionalInt.empty();

        if (menu instanceof UpgradeableMenu<?> upgradeable) {
            readUpgradeableSettings(upgradeable, settings);
            hasRedstoneCard = upgradeable.hasUpgrade(AEItems.REDSTONE_CARD);
        }
        if (menu instanceof KeyTypeSelectionMenu keyMenu) {
            readKeyTypes(keyMenu, keyTypes);
        }
        if (menu instanceof StorageBusMenu storageBus) {
            putEnum(settings, "access", storageBus.getReadWriteMode());
            putEnum(settings, "storage_filter", storageBus.getStorageFilter());
            putEnum(settings, "filter_on_extract", storageBus.getFilterOnExtract());
            Component desc = storageBus.getConnectedTo();
            connectedTo = desc == null ? null : desc.getString();
        }
        if (menu instanceof FormationPlaneMenu plane) {
            YesNo place = plane.getPlaceMode();
            if (place != null) settings.put("place_mode", place == YesNo.YES ? "block" : "drop");
        }
        if (menu instanceof StorageLevelEmitterMenu emitter) {
            reporting = OptionalLong.of(emitter.getCurrentValue());
            putEnum(settings, "craft_via_redstone", emitter.getCraftingMode());
        }
        if (menu instanceof EnergyLevelEmitterMenu energyEmitter) {
            reporting = OptionalLong.of(energyEmitter.getReportingValue());
        }
        if (menu instanceof PriorityMenu priorityMenu) {
            priority = OptionalInt.of(priorityMenu.getPriorityValue());
        }
        if (menu instanceof SetStockAmountMenu stockMenu) {
            stockInitial = OptionalInt.of(stockMenu.getInitialAmount());
            stockMax = OptionalInt.of(stockMenu.getMaxAmount());
        }
        if (menu instanceof CellWorkbenchMenu workbench) {
            putEnum(settings, "copy_mode", workbench.getCopyMode());
            putEnum(settings, "fuzzy_mode", workbench.getFuzzyMode());
        }
        if (menu instanceof IOPortMenu transfer) {
            putEnum(settings, "operation_mode", transfer.getOperationMode());
            putEnum(settings, "fullness_mode", transfer.getFullMode());
        }
        List<MenuSlotEntry> storageSlots = readSemanticSlots(menu, SlotSemantics.STORAGE, false);
        if (menu instanceof WirelessAccessPointMenu) {
            int boosters = storageSlots.isEmpty() || storageSlots.getFirst().isEmpty()
                    ? 0 : (int) storageSlots.getFirst().count();
            wirelessBoosters = OptionalInt.of(boosters);
        }
        return Optional.of(new OpenMenuSnapshot(
                typeId, settings, keyTypes, connectedTo, reporting, priority, stockInitial, stockMax,
                hasRedstoneCard, wirelessBoosters,
                readSemanticSlots(menu, SlotSemantics.CONFIG, true),
                readSemanticSlots(menu, SlotSemantics.UPGRADE, false),
                storageSlots,
                readSemanticSlots(menu, SlotSemantics.STORAGE_CELL, false),
                readSemanticSlots(menu, SlotSemantics.MACHINE_INPUT, false),
                readSemanticSlots(menu, SlotSemantics.MACHINE_OUTPUT, false)));
    }

    // 输入总线切换导入物品/流体类型：经 AE2 公开接口发 SelectKeyTypePacket 并更新客户端选中态。
    @Override public boolean selectKeyType(String keyType, boolean enabled) {
        if (!(openMenu() instanceof IOBusMenu menu)) return false;
        AEKeyType resolved = switch (keyType == null ? "" : keyType.toLowerCase(Locale.ROOT)) {
            case "item" -> AEKeyType.items();
            case "fluid" -> AEKeyType.fluids();
            default -> null;
        };
        if (resolved == null) return false;
        menu.selectKeyType(resolved, enabled);
        return true;
    }

    // 元件工作台模糊模式存在元件组件上，必须走 CellWorkbenchMenu.setCellFuzzyMode（GuiActionPacket）。
    @Override public boolean setCellWorkbenchFuzzy(String fuzzyMode) {
        if (!(openMenu() instanceof CellWorkbenchMenu menu) || fuzzyMode == null) return false;
        FuzzyMode parsed;
        try {
            parsed = FuzzyMode.valueOf(fuzzyMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        menu.setCellFuzzyMode(parsed);
        return true;
    }

    // 过滤目标同时支持物品 ID、流体 ID（经 GenericStack.wrapInItemStack 包装）与 empty。
    @Override public Optional<ItemStack> filterStackOf(String targetId) {
        if (targetId == null || targetId.isBlank()) return Optional.empty();
        if (targetId.equalsIgnoreCase("empty")) return Optional.of(ItemStack.EMPTY);
        ResourceLocation key = ResourceLocation.tryParse(targetId);
        if (key == null) return Optional.empty();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(key);
        if (item.isPresent() && item.get() != Items.AIR) {
            return Optional.of(new ItemStack(item.get()));
        }
        Optional<Fluid> fluid = BuiltInRegistries.FLUID.getOptional(key);
        if (fluid.isPresent() && fluid.get() != Fluids.EMPTY) {
            return Optional.of(GenericStack.wrapInItemStack(AEFluidKey.of(fluid.get()), 1));
        }
        return Optional.empty();
    }

    // 调用 AE2 公开的 P2PTunnelAttunement 查这件物品能调谐出哪种 P2P 通道。
    @Override public Optional<String> p2pAttunementOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        ItemStack tunnel = P2PTunnelAttunement.getTunnelPartByTriggerItem(stack);
        if (tunnel.isEmpty()) return Optional.empty();
        String id = BuiltInRegistries.ITEM.getKey(tunnel.getItem()).toString();
        return switch (id) {
            case "ae2:me_p2p_tunnel" -> Optional.of("me");
            case "ae2:redstone_p2p_tunnel" -> Optional.of("redstone");
            case "ae2:item_p2p_tunnel" -> Optional.of("item");
            case "ae2:fluid_p2p_tunnel" -> Optional.of("fluid");
            case "ae2:fe_p2p_tunnel" -> Optional.of("fe");
            case "ae2:light_p2p_tunnel" -> Optional.of("light");
            default -> Optional.empty();
        };
    }

    // ME IO 传输站可接受任意存储元件与实现 IBasicCellItem 的工具（如物质炮、上色器）。
    @Override public boolean isTransferableCell(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof IBasicCellItem || StorageCells.isCellHandled(stack);
    }

    @Override public boolean isMemoryCard(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof IMemoryCard;
    }

    @Override public Optional<String> memoryCardP2pType(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        Item item = stack.get(AEComponents.EXPORTED_P2P_TYPE);
        if (item == null) return Optional.empty();
        return Optional.of(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    @Override public OptionalInt memoryCardP2pFrequency(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OptionalInt.empty();
        Short freq = stack.get(AEComponents.EXPORTED_P2P_FREQUENCY);
        return freq == null ? OptionalInt.empty() : OptionalInt.of(Short.toUnsignedInt(freq));
    }

    @Override public boolean isMenuType(AbstractContainerMenu menu, String menuTypeId) {
        if (menu == null || menuTypeId == null) return false;
        return menuTypeIdOf(menu).equals(menuTypeId.toLowerCase(Locale.ROOT));
    }

    // 监视器二次右键流体容器物品时切到内含流体：按 AE2 原生 ContainerItemStrategies 核对容器内流体。
    @Override public boolean isFluidContainerFor(ItemStack stack, String fluidId) {
        if (stack == null || stack.isEmpty() || fluidId == null) return false;
        GenericStack contained = ContainerItemStrategies.getContainedStack(stack);
        return contained != null && contained.what().getId().toString().equals(fluidId);
    }

    private static void readUpgradeableSettings(UpgradeableMenu<?> menu, Map<String, String> settings) {
        putEnum(settings, "redstone_mode", menu.getRedStoneMode());
        putEnum(settings, "fuzzy_mode", menu.getFuzzyMode());
        putEnum(settings, "craft_only", menu.getCraftingMode());
        putEnum(settings, "scheduling_mode", menu.getSchedulingMode());
    }

    private static void readKeyTypes(KeyTypeSelectionMenu menu, Map<String, Boolean> keyTypes) {
        Map<AEKeyType, Boolean> synced = menu.getClientKeyTypeSelection().keyTypes();
        Boolean items = synced.get(AEKeyType.items());
        if (items != null) keyTypes.put("item", items);
        Boolean fluids = synced.get(AEKeyType.fluids());
        if (fluids != null) keyTypes.put("fluid", fluids);
    }

    private static List<MenuSlotEntry> readSemanticSlots(AEBaseMenu menu, SlotSemantic semantic, boolean unwrapGeneric) {
        List<Slot> slots = menu.getSlots(semantic);
        if (slots.isEmpty()) return List.of();
        List<MenuSlotEntry> list = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            boolean enabled = !(slot instanceof AppEngSlot aeSlot) || aeSlot.isSlotEnabled();
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                list.add(new MenuSlotEntry(slot.index, i, enabled, null, 0L));
                continue;
            }
            if (unwrapGeneric) {
                GenericStack generic = GenericStack.fromItemStack(stack);
                if (generic != null) {
                    long count = generic.amount() <= 0 ? 1L : generic.amount();
                    list.add(new MenuSlotEntry(slot.index, i, enabled, generic.what().getId().toString(), count));
                    continue;
                }
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            list.add(new MenuSlotEntry(slot.index, i, enabled, id, stack.getCount()));
        }
        return List.copyOf(list);
    }

    private static void putEnum(Map<String, String> settings, String key, Enum<?> value) {
        if (value != null) {
            settings.put(key, value.name().toLowerCase(Locale.ROOT));
        }
    }

    private static String menuTypeIdOf(AbstractContainerMenu menu) {
        try {
            MenuType<?> type = menu.getType();
            ResourceLocation key = BuiltInRegistries.MENU.getKey(type);
            return key == null ? "" : key.toString().toLowerCase(Locale.ROOT);
        } catch (UnsupportedOperationException ignored) {
            return "";
        }
    }

    private static AEBaseMenu openMenu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof AEBaseMenu menu ? menu : null;
    }
}
