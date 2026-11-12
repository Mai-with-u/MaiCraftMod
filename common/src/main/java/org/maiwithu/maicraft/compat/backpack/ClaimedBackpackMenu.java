// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuClose;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.MenuSessionSource;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 认领到的一份随身背包界面：取货与腾地方往包里存共用它读两侧槽位、点击搬运、记下存货并关上界面。
 *
 * <p>读数只看模组主存储里的可用槽位（升级槽、被升级占掉变灰的槽位和记忆筛选幽灵图标都不算存货）；
 * 关包前顺手把主存储里现有的物品计数与未锁定的通用空槽数写进 {@link SeenBackpackStock}，
 * 让后续拿东西问价、腾格子算空间和自身观察都拿到刚看过的真实读数。
 */
final class ClaimedBackpackMenu implements OpenedMenu {

    private final BackpackCompat compat;
    private final Supplier<PlayerContext> playerContext;
    private final MenuChannel channel;
    /** 主存储的可用槽位号：升级槽与灰掉的槽位不在里面。 */
    private final List<Integer> storage;
    private MenuSession session;

    ClaimedBackpackMenu(BackpackCompat compat, Supplier<PlayerContext> playerContext, MenuChannel channel,
            List<Integer> storage) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.playerContext = Objects.requireNonNull(playerContext, "playerContext");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.storage = List.copyOf(storage);
    }

    boolean stillOpen() {
        return channel.stillOpen();
    }

    List<Integer> storageSlotIds() {
        return storage;
    }

    /** 主存储这一格能不能接纳这堆物品（同时排除记忆筛选锁给别的物品的槽位与过滤升级拒收的物品）。 */
    boolean canStoreInSlot(int slotId, ItemStack stack) {
        AbstractContainerMenu opened = currentMenu();
        return opened != null && compat.canStoreInSlot(opened, slotId, stack);
    }

    /** 关包前记下这次看到的主存储物品计数与通用空槽数；界面已经丢了就把旧记录清掉。 */
    void recordSighting(SeenBackpackStock seen, BackpackSlot.Location location, Instant now) {
        AbstractContainerMenu opened = currentMenu();
        Optional<MenuContent.Reading> current = reading();
        if (opened == null || current.isEmpty()) {
            seen.forget(location);
            return;
        }
        Map<String, Integer> stock = new LinkedHashMap<>();
        int freeSlots = 0;
        List<SlotSnapshot> snapshots = current.get().containerSnapshots();
        for (int i = 0; i < snapshots.size(); i++) {
            SlotSnapshot snapshot = snapshots.get(i);
            if (snapshot.isEmpty()) {
                // 设了记忆筛选图标的空槽只收特定那一种物品，不计入通用剩余空槽。
                if (compat.isUnfilteredEmptySlot(opened, storage.get(i))) {
                    freeSlots++;
                }
                continue;
            }
            BackpackStack stack = ClientBackpackView.snapshot(snapshot.stack());
            stock.merge(stack.itemId(), snapshot.count(), Integer::sum);
        }
        seen.saw(location, stock, freeSlots, now);
    }

    @Override public Optional<MenuContent.Reading> reading() {
        AbstractContainerMenu opened = currentMenu();
        if (opened == null || !compat.isMenuSynchronized(opened)) {
            return Optional.empty();
        }
        List<Integer> playerSlots = compat.playerSlotIds(opened);
        List<SlotSnapshot> storageSnapshots = new ArrayList<>();
        List<SlotSnapshot> playerSnapshots = new ArrayList<>();
        for (int slot : storage) storageSnapshots.add(snapshot(opened, slot));
        for (int slot : playerSlots) playerSnapshots.add(snapshot(opened, slot));
        // 背包格号取槽位背后物品格自己的格号：快捷栏 0-8、主背包 9-35，不依赖界面把哪些槽排在前。
        List<Integer> playerIndices = new ArrayList<>();
        for (int slot : playerSlots) playerIndices.add(opened.getSlot(slot).getContainerSlot());
        MenuSlots slots = new MenuSlots() {
            @Override public String menuTypeId() { return "sophisticatedbackpacks:backpack"; }
            @Override public int slotCount() { return opened.slots.size(); }
            @Override public boolean playerBacked(int slot) {
                return slot >= 0 && slot < opened.slots.size()
                        && opened.slots.get(slot).container instanceof Inventory;
            }
            @Override public int playerInventoryIndex(int slot) {
                return slot >= 0 && slot < opened.slots.size()
                        ? opened.getSlot(slot).getContainerSlot() : -1;
            }
        };
        return Optional.of(new MenuContent.Reading(channel, slots, storage, playerSlots,
                List.copyOf(playerIndices), List.copyOf(storageSnapshots), List.copyOf(playerSnapshots)));
    }

    @Override public boolean busy() {
        PlayerContext context = playerContext.get();
        return context == null || context.menuActions().hasPendingTransaction();
    }

    @Override public boolean cursorEmpty() {
        return !channel.cursorCarrying();
    }

    @Override public SlotSnapshot cursor() {
        return channel.cursor();
    }

    @Override public boolean quickMove(int slotId) {
        return channel.quickMove(slotId);
    }

    @Override public boolean click(int slotId, int button) {
        return channel.click(slotId, button);
    }

    @Override public void noteCursorTakenFrom(int slotId) {
        // 光标上的东西从这一格拿起：会话记下，关界面前放回这里。会话要求光标为空才认领，
        // 第一次登记都发生在拿起之前；认领不上就交由原版的关闭流程还。
        if (session == null && MenuSession.claim(channel) instanceof MenuSession.Claim.Owned owned) {
            session = owned.session();
        }
        if (session != null) session.noteCursorTakenFrom(slotId);
    }

    @Override public Action closing() {
        return new MenuClose(channel, new MenuSessionSource() {
            @Override public Optional<MenuSession> current() {
                return Optional.ofNullable(session);
            }

            @Override public void renew() {
                session = null;
            }
        });
    }

    @Override public void abandon() {
        if (channel.stillOpen()) channel.requestClose();
    }

    private AbstractContainerMenu currentMenu() {
        PlayerContext context = playerContext.get();
        if (context == null || context.localPlayer() == null || !channel.stillOpen()) {
            return null;
        }
        AbstractContainerMenu opened = context.localPlayer().containerMenu;
        return opened != null && compat.isBackpackMenu(opened) ? opened : null;
    }

    private static SlotSnapshot snapshot(AbstractContainerMenu opened, int slot) {
        if (slot < 0 || slot >= opened.slots.size()) return SlotSnapshot.empty();
        ItemStack stack = opened.slots.get(slot).getItem();
        return stack.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(stack);
    }
}
