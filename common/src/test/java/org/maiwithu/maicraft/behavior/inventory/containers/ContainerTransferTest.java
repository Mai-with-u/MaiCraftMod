// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往容器搬运动作测试：按配方 #标签 点名搬运时能命中具体物品格、支持整堆快速移动与部分拆堆，
 * 两侧配对总数按标签核对并按原规格记账；按具体物品 ID 搬运的行为保持原样。
 */
class ContainerTransferTest {

    private static final TagKey<Item> GOLD_INGOTS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("c", "ingots/gold"));
    private static final TagKey<Item> BOATS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("minecraft", "boats"));

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void 绑定测试标签() {
        BuiltInRegistries.ITEM.bindTags(Map.of(
                GOLD_INGOTS, List.of(holder(Items.GOLD_INGOT)),
                BOATS, List.of(holder(Items.OAK_BOAT), holder(Items.SPRUCE_BOAT))));
    }

    private static Holder<Item> holder(Item item) {
        return BuiltInRegistries.ITEM.wrapAsHolder(item);
    }

    @Test
    void 按物品标签搬运整堆与拆堆都能挑中具体物品并按标签记账() {
        // 背包里有 10 块金锭，机器按 #c:ingots/gold 要投 4 件：拆出 4 件放进容器格，账本按 #c:ingots/gold 记 4 件。
        FakeTransferMenu menu = new FakeTransferMenu(false);
        menu.player.set(0, new ItemStack(Items.GOLD_INGOT, 10));
        CountingLedger ledger = new CountingLedger(Map.of("#c:ingots/gold", 4));
        ContainerTransfer transfer = new ContainerTransfer(menu, List.of("#c:ingots/gold"), ledger, null);

        runToDone(transfer);

        assertEquals(4, ledger.stored.getOrDefault("#c:ingots/gold", 0));
        assertEquals(6, menu.player.get(0).getCount());
        assertEquals(4, menu.container.get(0).getCount());
        assertTrue(ledger.unconfirmed.isEmpty(), ledger.unconfirmed.toString());
    }

    @Test
    void 按具体物品ID搬运保持原有行为不变() {
        FakeTransferMenu menu = new FakeTransferMenu(false);
        menu.player.set(2, new ItemStack(Items.COBBLESTONE, 16));
        CountingLedger ledger = new CountingLedger(Map.of("minecraft:cobblestone", 16));
        ContainerTransfer transfer = new ContainerTransfer(menu, List.of("minecraft:cobblestone"), ledger, null);

        runToDone(transfer);

        assertEquals(16, ledger.stored.getOrDefault("minecraft:cobblestone", 0));
        assertTrue(menu.player.get(2).isEmpty());
        assertEquals(16, menu.container.get(0).getCount());
    }

    private static void runToDone(Action action) {
        for (int i = 0; i < 40; i++) {
            ActionStatus status = action.tick(TickContext.of(i, null));
            if (status instanceof ActionStatus.Done) return;
        }
        assertInstanceOf(ActionStatus.Done.class, action.tick(TickContext.of(40, null)));
    }

    private static final class CountingLedger implements StoresInContainer.Ledger {
        private final Map<String, Integer> wanted;
        final Map<String, Integer> stored = new LinkedHashMap<>();
        final List<String> unconfirmed = new ArrayList<>();

        CountingLedger(Map<String, Integer> wanted) {
            this.wanted = wanted;
        }

        @Override public int left(String itemId) {
            return wanted.getOrDefault(itemId, 0) - stored.getOrDefault(itemId, 0);
        }

        @Override public void stored(String itemId, int amount) {
            stored.merge(itemId, amount, Integer::sum);
        }

        @Override public void unconfirmed(String fact) {
            unconfirmed.add(fact);
        }

        @Override public void attempt(String tried, String whatHappened) {}
    }

    /** 容器侧 4 格、角色背包侧 36 格的替身界面；instantConsume 为真时模拟快速机器把刚放进的物品立刻吃掉。 */
    static final class FakeTransferMenu implements OpenedMenu {
        private static final int CONTAINER_SIZE = 4;
        final List<ItemStack> container = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        private ItemStack cursor = ItemStack.EMPTY;
        private final boolean instantConsume;
        boolean closed;

        FakeTransferMenu(boolean instantConsume) {
            this.instantConsume = instantConsume;
            for (int i = 0; i < CONTAINER_SIZE; i++) container.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        private final MenuChannel channel = new MenuChannel() {
            @Override public boolean stillOpen() { return !closed; }
            @Override public boolean cursorCarrying() { return !cursor.isEmpty(); }
            @Override public SlotSnapshot cursor() {
                return cursor.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(cursor);
            }
            @Override public boolean click(int slot, int button) { return FakeTransferMenu.this.click(slot, button); }
            @Override public boolean quickMove(int slot) { return FakeTransferMenu.this.quickMove(slot); }
            @Override public boolean selectTradeOffer(int offerIndex) { return false; }
            @Override public void requestClose() { closed = true; }
        };

        private static final MenuSlots SLOTS = new MenuSlots() {
            @Override public String menuTypeId() { return "test:machine"; }
            @Override public int slotCount() { return CONTAINER_SIZE + 36; }
            @Override public boolean playerBacked(int slot) { return slot >= CONTAINER_SIZE; }
            @Override public int playerInventoryIndex(int slot) { return slot - CONTAINER_SIZE; }
        };

        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> cIds = new ArrayList<>();
            List<SlotSnapshot> cSnaps = new ArrayList<>();
            for (int i = 0; i < container.size(); i++) {
                cIds.add(i);
                cSnaps.add(container.get(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(container.get(i)));
            }
            List<Integer> pIds = new ArrayList<>();
            List<Integer> pIdx = new ArrayList<>();
            List<SlotSnapshot> pSnaps = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                pIds.add(CONTAINER_SIZE + i);
                pIdx.add(i);
                pSnaps.add(player.get(i).isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(channel, SLOTS, cIds, pIds, pIdx, cSnaps, pSnaps));
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }
        @Override public SlotSnapshot cursor() {
            return cursor.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(cursor);
        }

        @Override public boolean quickMove(int slotId) {
            if (slotId < CONTAINER_SIZE) return true;
            int pIndex = slotId - CONTAINER_SIZE;
            ItemStack moving = player.get(pIndex);
            if (moving.isEmpty()) return true;
            for (int i = 0; i < container.size(); i++) {
                if (container.get(i).isEmpty()) {
                    container.set(i, instantConsume ? ItemStack.EMPTY : moving.copy());
                    player.set(pIndex, ItemStack.EMPTY);
                    return true;
                }
            }
            return true;
        }

        @Override public boolean click(int slotId, int button) {
            List<ItemStack> side = slotId < CONTAINER_SIZE ? container : player;
            int index = slotId < CONTAINER_SIZE ? slotId : slotId - CONTAINER_SIZE;
            ItemStack slot = side.get(index);
            if (cursor.isEmpty()) {
                if (slot.isEmpty()) return true;
                if (button == 0) {
                    cursor = slot;
                    side.set(index, ItemStack.EMPTY);
                } else {
                    int taken = (slot.getCount() + 1) / 2;
                    cursor = slot.copyWithCount(taken);
                    side.set(index, slot.copyWithCount(slot.getCount() - taken));
                }
            } else if (button == 0) {
                if (slot.isEmpty()) {
                    side.set(index, slotId < CONTAINER_SIZE && instantConsume ? ItemStack.EMPTY : cursor);
                    cursor = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(slot, cursor)) {
                    int fit = Math.min(cursor.getCount(), slot.getMaxStackSize() - slot.getCount());
                    if (!(slotId < CONTAINER_SIZE && instantConsume)) slot.grow(fit);
                    cursor.shrink(fit);
                }
            } else if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, cursor)
                    && slot.getCount() < slot.getMaxStackSize()) {
                if (!(slotId < CONTAINER_SIZE && instantConsume)) {
                    if (slot.isEmpty()) side.set(index, cursor.copyWithCount(1));
                    else slot.grow(1);
                }
                cursor.shrink(1);
            }
            if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
            return true;
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }
                @Override public String describe() { return "关上测试界面"; }
            };
        }

        @Override public void abandon() { closed = true; }
    }
}
