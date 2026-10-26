// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 终端界面会话各段离线测试共用的替身：一份能像原版那样点格子与快速移动的界面、
 * 一份可配置网络读数的 ME 终端读写端、到跟前的办法与每刻的角色。
 */
final class TerminalFlowFakes {

    private TerminalFlowFakes() {}

    /** 界面里这些槽位号的角色侧归属：0–26 是网络那一侧，27–62 是角色背包（主背包在前、快捷栏在后）。 */
    private static final MenuSlots SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "ae2:terminal"; }
        @Override public int slotCount() { return 63; }
        @Override public boolean playerBacked(int slot) { return slot >= 27; }
        @Override public int playerInventoryIndex(int slot) { return slot >= 36 ? slot - 27 : slot - 18; }
    };

    /**
     * 能动手的替身界面：网络那一侧 27 格、角色背包 36 格，普通点击与快速移动都按原版规矩结算。
     * 模组协议的操作一直发不出去（返回空），要演"发出去"的场景换成可编程的那一头。
     */
    static final class Menu implements OpenedMenu {

        final List<ItemStack> network = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        /** 光标上拿着的东西；与原界面 carried 由 carry 同步。 */
        private ItemStack cursor = ItemStack.EMPTY;
        /** 原界面：取货与存东西各段读光标、读存货都对着的这一份。 */
        final AbstractContainerMenu raw = rawMenu();
        /** 前几下快速移动"本刻发不出去"。 */
        int notReadyMoves;
        /** 点得出去但东西原地不动（网络放不下、服务端收下点击却没搬）。 */
        int jamMoves;
        /** 发过的模组协议操作次数。 */
        int modActions;
        boolean closed;

        Menu() {
            for (int i = 0; i < 27; i++) network.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        private static AbstractContainerMenu rawMenu() {
            return new AbstractContainerMenu(null, 0) {
                @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
                @Override public boolean stillValid(Player player) { return true; }
            };
        }

        // 光标上拿起东西：替身字段与原界面的 carried 一起放，读光标的两条路看到同一份。
        void carry(ItemStack stack) {
            cursor = stack;
            raw.setCarried(stack);
        }

        // 网络把光标上的东西收下了（存东西的整份交回、取货的放回网络都走这一下）。
        void networkTakesCursor() {
            carry(ItemStack.EMPTY);
        }

        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> networkIds = new ArrayList<>();
            List<SlotSnapshot> networkSnapshots = new ArrayList<>();
            for (int i = 0; i < network.size(); i++) {
                networkIds.add(i);
                networkSnapshots.add(snapshot(network.get(i)));
            }
            List<Integer> playerIds = new ArrayList<>();
            List<Integer> playerIndices = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                playerIds.add(27 + i);
                playerIndices.add(i < 27 ? 9 + i : i - 27);
                playerSnapshots.add(snapshot(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(channel(), SLOTS, networkIds, playerIds, playerIndices,
                    networkSnapshots, playerSnapshots));
        }

        private MenuChannel channel() {
            return new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return !cursor.isEmpty(); }
                @Override public SlotSnapshot cursor() { return snapshot(cursor); }
                @Override public boolean click(int slot, int button) { return true; }
                @Override public boolean quickMove(int slot) { return true; }
                @Override public boolean selectTradeOffer(int offerIndex) { return true; }
                @Override public void requestClose() {}
            };
        }

        private static SlotSnapshot snapshot(ItemStack item) {
            return item.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(item);
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }
        @Override public SlotSnapshot cursor() { return snapshot(cursor); }

        // 快速移动：网络那一侧的整堆搬进背包第一个空格，背包那一侧的整堆交给网络；发不出去、搬不动的先挡下。
        @Override public boolean quickMove(int slotId) {
            if (notReadyMoves > 0) {
                notReadyMoves--;
                return false;
            }
            if (jamMoves > 0) {
                jamMoves--;
                return true;
            }
            if (slotId < 27) {
                ItemStack moving = network.get(slotId);
                for (int i = 0; i < player.size(); i++) {
                    if (player.get(i).isEmpty()) {
                        player.set(i, moving.copy());
                        network.set(slotId, ItemStack.EMPTY);
                        break;
                    }
                }
            } else {
                player.set(slotId - 27, ItemStack.EMPTY);
            }
            return true;
        }

        // 普通点击按原版规矩结算：空光标左键整份拿起、右键拿半堆；拿着东西左键整份放进（同种并堆，
        // 别的交换）、右键放一个，放不进就不动。
        @Override public boolean click(int slotId, int button) {
            List<ItemStack> side = slotId < 27 ? network : player;
            int index = slotId < 27 ? slotId : slotId - 27;
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
                    side.set(index, cursor);
                    cursor = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(slot, cursor)) {
                    int fitting = Math.min(cursor.getCount(), slot.getMaxStackSize() - slot.getCount());
                    slot.grow(fitting);
                    cursor.shrink(fitting);
                } else {
                    side.set(index, cursor);
                    cursor = slot;
                }
            } else if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, cursor)
                    && slot.getCount() < slot.getMaxStackSize()) {
                if (slot.isEmpty()) {
                    side.set(index, cursor.copyWithCount(1));
                } else {
                    slot.grow(1);
                }
                cursor.shrink(1);
            }
            if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
            raw.setCarried(cursor);
            return true;
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        // 模组协议的操作一直"本刻发不出去"：只计数，要演发出去的场景由测试另接替身。
        @Override public Optional<PendingMenuAction> submitModAction(String what, Runnable send,
                MenuConfirmation confirmation, int timeoutTicks) {
            modActions++;
            return Optional.empty();
        }

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "关上替身终端"; }
            };
        }

        @Override public void abandon() {
            closed = true;
        }

        int carried(ItemStack kind) {
            return player.stream().filter(item -> ItemStack.isSameItem(item, kind))
                    .mapToInt(ItemStack::getCount).sum();
        }
    }

    /** 可配置网络读数的 ME 终端读写端：连没连上、存货、没连上的原因、自己下的作业都在测试手里。 */
    static final class Reads implements Ae2TerminalMenu {

        /** 网络读数：null 演还没同步过来。 */
        Optional<Boolean> linked = Optional.empty();
        List<Ae2TerminalMenu.StockEntry> stock = null;
        String why;
        boolean pending;
        boolean recognized = true;
        /** 交回网络那一下（存东西凑尾数后整份交给网络）要做的；默认什么也不做，演服务端没动静。 */
        Runnable onPutBack = () -> {};

        private final AbstractContainerMenu terminal = rawTerminal();

        private static AbstractContainerMenu rawTerminal() {
            return new AbstractContainerMenu(null, 0) {
                @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
                @Override public boolean stillValid(Player player) { return true; }
            };
        }

        /** 终端那一页：各段对着它读存货、发操作；测试把它换成替身界面共用的一份。 */
        AbstractContainerMenu page() {
            return pageMenu != null ? pageMenu : terminal;
        }

        /** 让读写端认替身界面的那一份原界面：界面会话与读数对着同一个对象。 */
        void sharePage(AbstractContainerMenu raw) {
            this.pageMenu = raw;
        }

        private AbstractContainerMenu pageMenu;

        @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block,
                Direction side) {
            return menu == page() && recognized;
        }

        @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
            return menu == page() ? linked : Optional.empty();
        }

        @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
            return menu == page() ? Optional.ofNullable(stock) : Optional.empty();
        }

        @Override public void takeStack(AbstractContainerMenu menu, long serial) {}
        @Override public void takeOne(AbstractContainerMenu menu, long serial) {}
        @Override public void putBack(AbstractContainerMenu menu) {
            onPutBack.run();
        }
        @Override public void requestCraft(AbstractContainerMenu menu, long serial) {}
        @Override public void confirmCraft(AbstractContainerMenu menu, int amount) {}
        @Override public boolean craftAmountMenu(AbstractContainerMenu menu) { return false; }
        @Override public Optional<CraftPlanPage> craftPlanPage(AbstractContainerMenu menu) { return Optional.empty(); }
        @Override public boolean craftPending(ItemStack sample) { return pending; }
        @Override public Optional<String> unlinkedWhy(AbstractContainerMenu menu) {
            return menu == page() && why != null ? Optional.of(why) : Optional.empty();
        }
    }

    /** 到跟前的办法：点开按剧本走，认不认这台、记没记网络读数都在测试手里。 */
    static final class Access implements TerminalAccess {

        /** 打开动作每刻返回的状态；返回 Done 时交出 menu。 */
        ActionStatus openingStatus = ActionStatus.running();
        OpenedMenu opened;
        boolean recognized = true;
        /** 记下的网络读数：连没连上、几条存货、算不算看全。 */
        final List<String> remembered = new ArrayList<>();

        @Override public MenuOpening opening() {
            return new MenuOpening() {
                @Override public Optional<OpenedMenu> opened() { return Optional.ofNullable(opened); }
                @Override public ActionStatus tick(TickContext context) { return openingStatus; }
                @Override public String describe() { return "走到终端跟前"; }
            };
        }

        @Override public boolean belongs(AbstractContainerMenu raw, ClientLevel level) {
            return recognized;
        }

        @Override public String describe() {
            return "ae2:terminal（测试台）";
        }

        @Override public void remember(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, boolean complete) {
            remembered.add(linked + "x" + stock.size() + (complete ? "看全了" : "没看全"));
        }
    }

    /** 每刻的角色替身：各段读原界面都经会话的 rawPage 换成替身，碰不到真实角色。 */
    static final class StubPlayer implements PlayerContext {
        long tick = 1000;

        void advance() {
            tick++;
        }

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() { return null; }
        @Override public InteractionSender interactionSender() { return null; }
        @Override public MenuActions menuActions() { return null; }
        @Override public long clientTick() { return tick; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return true; }
        @Override public boolean tryClaimInteraction() { return true; }
    }

    /** 只有刻号与角色替身的推进现场。 */
    static final class Ticks implements TickContext {
        private final StubPlayer player;

        Ticks(StubPlayer player) {
            this.player = player;
        }

        void advance() {
            player.tick++;
        }

        @Override public long gameTick() { return player.tick; }

        @Override public PlayerContext player() { return player; }
    }

    /** 不在世界里的玩家行为：要什么按物品 ID 精确对上（标签一概不算），其余的入口一个都用不上。 */
    static PlayerServices services(Supplier<PlayerContext> contexts) {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        return new PlayerServices(contexts, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new NoGear(), inner -> inner, (request, permissions) -> null,
                nobodyOwns, itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                });
    }

    /** 一件装备都不穿、不取。 */
    private static final class NoGear implements GearChanges {
        @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
            return Optional.empty();
        }

        @Override public Optional<Action> takeOff(GearSlotName slot) {
            return Optional.empty();
        }
    }
}
