// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuClose;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.MenuSessionSource;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 认领到的交易界面：界面由搭话的动作点开（不走打开容器的共用人马），这里把此刻开着的这份
 * 绑在对象与编号上，交给搬运执行逐刻点、按核对式出结论。
 *
 * <p>读数按交易界面自己的布局分侧：容器那一侧固定三格（0、1 付款格，2 结果格），其余是角色侧。
 * 点选报价不是点格子，仍从界面通道发；点格子、读光标、认来源格与关界面都按搬运执行的界面接缝来。
 *
 * <p>不认服务端换页：成交后服务端真换了新的一页，就按"界面中途关上了"照实交回——
 * 认领的那一页绑的是对象与编号，与迁移前通道的核对方式一字不差。
 */
final class ClaimedTradeMenu implements OpenedMenu {

    private final ClientMenuChannel channel;
    private final Supplier<PlayerContext> contexts;

    /** 认领此刻开着的交易界面；没有开着的容器界面时为 null。 */
    static ClaimedTradeMenu claimCurrent(Supplier<PlayerContext> contexts) {
        ClientMenuChannel channel = ClientMenuChannel.claimCurrent(contexts);
        return channel == null ? null : new ClaimedTradeMenu(channel, contexts);
    }

    private ClaimedTradeMenu(ClientMenuChannel channel, Supplier<PlayerContext> contexts) {
        this.channel = channel;
        this.contexts = contexts;
    }

    /** 这份界面此刻还开着的：读侧、点侧与收尾都按它回答。 */
    boolean stillOpen() {
        return channel.stillOpen();
    }

    /** 点选一条报价：不是点格子，走界面通道的选择报价包；本刻发不出去给假，下一刻再试。 */
    boolean selectTradeOffer(int offerIndex) {
        return channel.selectTradeOffer(offerIndex);
    }

    /** 认领的那份界面通道：关界面的会话按它核对。 */
    MenuChannel channel() {
        return channel;
    }

    // 分侧读数：容器侧固定 0、1、2 三格（付款两格加结果格），角色侧照背包格号排；
    // 布局判定只证明得了原版箱子一族，交易界面的三格是游戏事实，在这里写明。
    @Override public Optional<MenuContent.Reading> reading() {
        PlayerContext context = contexts.get();
        var menu = context == null || context.localPlayer() == null ? null : context.localPlayer().containerMenu;
        if (menu == null || menu.getType() != MenuType.MERCHANT || !stillOpen()) {
            return Optional.empty();
        }
        List<Integer> containerIds = new ArrayList<>();
        List<SlotSnapshot> containerSnapshots = new ArrayList<>();
        for (int slot = 0; slot < 3; slot++) {
            containerIds.add(slot);
            containerSnapshots.add(snapshot(menu, slot));
        }
        List<Integer> playerIds = new ArrayList<>();
        List<Integer> playerIndices = new ArrayList<>();
        List<SlotSnapshot> playerSnapshots = new ArrayList<>();
        for (int slot = 3; slot < menu.slots.size(); slot++) {
            if (!(menu.slots.get(slot).container instanceof Inventory)) {
                continue;
            }
            playerIds.add(slot);
            playerIndices.add(menu.getSlot(slot).getContainerSlot());
            playerSnapshots.add(snapshot(menu, slot));
        }
        MenuSlots slots = new MenuSlots() {
            @Override public String menuTypeId() { return "minecraft:merchant"; }
            @Override public int slotCount() { return menu.slots.size(); }
            @Override public boolean playerBacked(int slot) {
                return slot >= 3 && slot < menu.slots.size()
                        && menu.slots.get(slot).container instanceof Inventory;
            }
            @Override public int playerInventoryIndex(int slot) {
                return slot >= 0 && slot < menu.slots.size() ? menu.getSlot(slot).getContainerSlot() : -1;
            }
        };
        return Optional.of(new MenuContent.Reading(channel, slots, List.copyOf(containerIds),
                List.copyOf(playerIds), List.copyOf(playerIndices), List.copyOf(containerSnapshots),
                List.copyOf(playerSnapshots)));
    }

    @Override public boolean busy() {
        PlayerContext context = contexts.get();
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

    // 结果格的货放不回结果格（结果格收不下玩家放的东西），收尾一律按原版关闭流程
    // 先放进背包装得下的一格再关；这里不登记来源格，与迁移前一字不差。
    @Override public void noteCursorTakenFrom(int slotId) {
    }

    @Override public Action closing() {
        return new MenuClose(channel, unusedSession());
    }

    @Override public void abandon() {
        if (channel.stillOpen()) {
            channel.requestClose();
        }
    }

    // 这份视图自己不持有会话：关界面由交易动作按它认领的会话收，这里给不出。
    private static MenuSessionSource unusedSession() {
        return new MenuSessionSource() {
            @Override public Optional<MenuSession> current() {
                return Optional.empty();
            }

            @Override public void renew() {
            }
        };
    }

    private static SlotSnapshot snapshot(AbstractContainerMenu menu, int slot) {
        var stack = menu.getSlot(slot).getItem();
        return stack.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(stack);
    }
}
