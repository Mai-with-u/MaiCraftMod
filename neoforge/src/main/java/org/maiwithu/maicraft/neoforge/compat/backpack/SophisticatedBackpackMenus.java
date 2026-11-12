// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.backpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedbackpacks.compat.CompatModIds;
import net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackOpenPayload;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;

import org.maiwithu.maicraft.compat.backpack.BackpackMenus;
import org.maiwithu.maicraft.compat.backpack.BackpackSlot;

/**
 * 精妙背包的界面与栏位读写端：枚举各随身栏位里的背包、认顶层随身包菜单、读主存储槽位、发精确打开请求。
 * 直接引用模组的类，只翻译不判断，所以只在联动清单确认装了、版本在范围内之后才会被加载。
 */
public final class SophisticatedBackpackMenus implements BackpackMenus {

    /** 主存储槽位数的常理上限：带无限格升级的背包读出来的数对不上常理，按读不出处理。 */
    private static final int SANE_STORAGE_SLOTS = 54 * 12;

    /**
     * 模组登记过随身背包栏位的名字，按模组自己找包时的先后顺序排：
     * 饰品栏（Curios、Accessories）在前，接着是胸甲、副手，最后是主背包 36 格。
     * 没装对应饰品模组时栏位表里查不到那一项，直接跳过，不会触发饰品模组的类加载。
     */
    private static final List<String> INVENTORY_SECTIONS = List.of(
            CompatModIds.CURIOS,
            CompatModIds.ACCESSORIES,
            PlayerInventoryProvider.ARMOR_INVENTORY,
            PlayerInventoryProvider.OFFHAND_INVENTORY,
            PlayerInventoryProvider.MAIN_INVENTORY);

    @Override public boolean isBackpackMenu(AbstractContainerMenu menu) {
        // 只认角色身上顶层随身背包打开的界面；摆在地上的方块背包与嵌套子包不算随身顶层包。
        return menu instanceof BackpackContainer backpack
                && backpack.getBackpackContext().getType() == BackpackContext.ContextType.ITEM_BACKPACK;
    }

    @Override public boolean isSynchronized(AbstractContainerMenu menu) {
        return menu instanceof BackpackContainer backpack && backpack.isUpgradeColumnCountSynced();
    }

    @Override public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) {
        if (!(menu instanceof BackpackContainer backpack)) {
            return Optional.empty();
        }
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (!backpack.isStorageInventorySlot(slot)) continue;
            // 带无限格升级时单格容量超出常规堆叠模型：整只包按读不出处理，不冒充普通格存取。
            if (backpack.isInfiniteSlot(slot)) {
                return Optional.empty();
            }
            // 高级合成、熔炉等升级占掉列数后变灰的槽位不能存取物品，排除在可用主存储外。
            if (!backpack.isInaccessibleSlot(slot)) {
                slots.add(slot);
            }
        }
        if (slots.size() > SANE_STORAGE_SLOTS) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(slots));
    }

    @Override public List<Integer> playerSlotIds(AbstractContainerMenu menu) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (menu.slots.get(slot).container instanceof Inventory) {
                slots.add(slot);
            }
        }
        return List.copyOf(slots);
    }

    @Override public boolean canStoreInSlot(AbstractContainerMenu menu, int slotId, ItemStack stack) {
        if (!(menu instanceof BackpackContainer backpack) || slotId < 0 || slotId >= menu.slots.size()) {
            return false;
        }
        if (!backpack.isStorageInventorySlot(slotId)
                || backpack.isInaccessibleSlot(slotId)
                || backpack.isInfiniteSlot(slotId)) {
            return false;
        }
        // 设了记忆筛选的槽位带一个幽灵过滤图标，只允许放同一种物品，不拿别的杂物去撞。
        ItemStack filter = backpack.getSlotFilterItem(slotId);
        if (!filter.isEmpty() && !ItemStack.isSameItem(filter, stack)) {
            return false;
        }
        return menu.getSlot(slotId).mayPlace(stack);
    }

    @Override public boolean isUnfilteredEmptySlot(AbstractContainerMenu menu, int slotId) {
        if (!(menu instanceof BackpackContainer backpack) || slotId < 0 || slotId >= menu.slots.size()) {
            return false;
        }
        return menu.getSlot(slotId).getItem().isEmpty() && backpack.getSlotFilterItem(slotId).isEmpty();
    }

    @Override public List<BackpackSlot> carriedBackpacks(Player player) {
        if (player == null) {
            return List.of();
        }
        // 按栏位名逐个取栏位读取器枚举：runOnBackpacks 在模组不同次版本间改过返回类型描述符，
        // 直接经 getPlayerInventoryHandler 遍历在两个次版本上字节码描述符一致，且胸甲、副手槽号天然从 0 起算。
        List<BackpackSlot> found = new ArrayList<>();
        PlayerInventoryProvider provider = PlayerInventoryProvider.get();
        for (String section : INVENTORY_SECTIONS) {
            Optional<PlayerInventoryHandler> registered = provider.getPlayerInventoryHandler(section);
            if (registered.isEmpty()) continue;
            PlayerInventoryHandler reader = registered.get();
            for (String group : reader.getIdentifiers(player)) {
                int slotCount = reader.getSlotCount(player, group);
                for (int slot = 0; slot < slotCount; slot++) {
                    ItemStack stack = reader.getStackInSlot(player, group, slot);
                    if (stack.getItem() instanceof BackpackItem) {
                        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        found.add(new BackpackSlot(section, group, slot, itemId));
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    @Override public void open(BackpackSlot.Location location) {
        // 没指定栏位时发空参请求，让服务端按默认顺序开第一只；
        // 指定了栏位时带上该栏位内的原生槽号（胸甲与副手是 0，饰品栏是对应分组内下标）精确打开那一只。
        if (location.inventoryName().isEmpty() || location.slotIndex() < 0) {
            PacketDistributor.sendToServer(new BackpackOpenPayload());
            return;
        }
        PacketDistributor.sendToServer(
                new BackpackOpenPayload(location.slotIndex(), location.slotGroup(), location.inventoryName()));
    }
}
