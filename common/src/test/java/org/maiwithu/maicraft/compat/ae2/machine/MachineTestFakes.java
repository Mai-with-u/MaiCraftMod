// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.compat.ae2.Ae2Inscribers;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2Patterns;
import org.maiwithu.maicraft.compat.ae2.Ae2TerminalMenu;
import org.maiwithu.maicraft.compat.ae2.Ae2Terminals;
import org.maiwithu.maicraft.compat.ae2.Ae2Transforms;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.game.world.BlockScanService;

/**
 * 离线单元测试共用的轻量化替身工厂：构造 Ae2Compat、PlayerServices 与可编程 OpenedMenu，
 * 不使用反射与 Unsafe。
 */
final class MachineTestFakes {

    private MachineTestFakes() {
    }

    static Ae2Compat compatWith(Ae2MenuSlots menuSlots, Ae2Patterns patterns, Ae2Grid grid) {
        Ae2Terminals noTerminals = new Ae2Terminals() {
            @Override public List<Terminal> near(ClientLevel level, BlockPos center, int radius) { return List.of(); }
            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) { return false; }
        };
        Ae2TerminalMenu noMenu = new Ae2TerminalMenu() {
            @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
                return false;
            }
            @Override public Optional<Boolean> linked(AbstractContainerMenu menu) { return Optional.empty(); }
            @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) { return Optional.empty(); }
            @Override public void takeStack(AbstractContainerMenu menu, long serial) {}
            @Override public void takeOne(AbstractContainerMenu menu, long serial) {}
            @Override public void putBack(AbstractContainerMenu menu) {}
        };
        return new Ae2Compat(noTerminals, noMenu, grid, patterns, Ae2Transforms.NONE, Ae2Inscribers.NONE, menuSlots);
    }

    static Ae2Grid emptyGrid() {
        return new Ae2Grid() {
            @Override public Optional<GridCell> cell(ClientLevel level, BlockPos at) { return Optional.empty(); }
            @Override public boolean partItem(String itemId) { return false; }
            @Override public boolean cableItem(String itemId) { return false; }
        };
    }

    static PlayerServices offlineServices() {
        Protection open = new Protection((dim, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        return new PlayerServices(() -> null, (target, permissions) -> doneAction("walk"), new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> doneAction("obtain"), open, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, new BackpackView() {
                    @Override public List<BackpackStack> stacks() { return List.of(); }
                    @Override public int usedSlots() { return 0; }
                    @Override public int totalSlots() { return 36; }
                }, DigToolRule.HELD);
    }

    static Action doneAction(String desc) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return desc; }
        };
    }

    static TickContext emptyTick() {
        return TickContext.of(1L, null);
    }

    /** 构造离线菜单读数快照（不依赖客户端菜单对象）。 */
    static MenuContent.Reading readingOf(List<Integer> playerSlotIds, List<SlotSnapshot> playerSnapshots,
            List<Integer> containerSlotIds, List<SlotSnapshot> containerSnapshots) {
        List<Integer> invIndices = new ArrayList<>(playerSlotIds.size());
        for (int i = 0; i < playerSlotIds.size(); i++) invIndices.add(i);
        MenuChannel channel = new MenuChannel() {
            @Override public boolean stillOpen() { return true; }
            @Override public boolean cursorCarrying() { return false; }
            @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
            @Override public boolean click(int slot, int button) { return true; }
            @Override public boolean quickMove(int slot) { return true; }
            @Override public boolean selectTradeOffer(int offerIndex) { return false; }
            @Override public void requestClose() {}
        };
        MenuSlots slots = new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:test"; }
            @Override public int slotCount() { return playerSlotIds.size() + containerSlotIds.size(); }
            @Override public boolean playerBacked(int slot) { return playerSlotIds.contains(slot); }
            @Override public int playerInventoryIndex(int slot) { return Math.max(0, playerSlotIds.indexOf(slot)); }
        };
        return new MenuContent.Reading(channel, slots, containerSlotIds, playerSlotIds, invIndices,
                containerSnapshots, playerSnapshots);
    }

    /** 可按步推进的菜单打开替身：一拍即完成并返回给定 OpenedMenu。 */
    static MenuOpening instantOpening(OpenedMenu menu) {
        return new MenuOpening() {
            @Override public Optional<OpenedMenu> opened() { return Optional.of(menu); }
            @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
            @Override public String describe() { return "打开测试菜单"; }
        };
    }

    /** 记录所有 quickMove / click / close 的可编程菜单替身。 */
    static final class ScriptedMenu implements OpenedMenu {
        private final Supplier<Optional<MenuContent.Reading>> readingSupplier;
        private final IntConsumer onQuickMove;
        private Supplier<SlotSnapshot> cursorSupplier = SlotSnapshot::empty;
        private IntConsumer onClick;
        final List<Integer> quickMoved = new ArrayList<>();
        final List<Integer> clicked = new ArrayList<>();
        boolean closed;

        ScriptedMenu(Supplier<Optional<MenuContent.Reading>> readingSupplier, IntConsumer onQuickMove) {
            this.readingSupplier = readingSupplier;
            this.onQuickMove = onQuickMove;
        }

        ScriptedMenu withCursor(Supplier<SlotSnapshot> cursorSupplier, IntConsumer onClick) {
            this.cursorSupplier = cursorSupplier;
            this.onClick = onClick;
            return this;
        }

        @Override public Optional<MenuContent.Reading> reading() { return readingSupplier.get(); }
        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursorSupplier.get().isEmpty(); }
        @Override public SlotSnapshot cursor() { return cursorSupplier.get(); }
        @Override public boolean quickMove(int slotId) {
            quickMoved.add(slotId);
            if (onQuickMove != null) onQuickMove.accept(slotId);
            return true;
        }
        @Override public boolean click(int slotId, int button) {
            clicked.add(slotId);
            if (onClick != null) onClick.accept(slotId);
            return true;
        }
        @Override public void noteCursorTakenFrom(int slotId) {}
        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }
                @Override public String describe() { return "关闭测试菜单"; }
            };
        }
        @Override public void abandon() { closed = true; }
    }
}
