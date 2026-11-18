// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AE2 网络存货记忆快照、存入关闭前刷新记忆、拆掉已加载终端淘汰、线缆面板挑选与终端查看器测试。
 */
class Ae2NetworkStockTest {

    private static final Instant NOW = Instant.parse("2026-11-18T05:00:00Z");
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static final Ae2Terminals.Terminal NORTH_TERMINAL = new Ae2Terminals.Terminal(
            new BlockPos(3, 64, 0), Direction.NORTH,
            new AABB(3.125, 64.125, 0.0, 3.875, 64.875, 0.125), "ae2:crafting_terminal");
    private static final Ae2Terminals.Terminal SOUTH_TERMINAL = new Ae2Terminals.Terminal(
            new BlockPos(3, 64, 0), Direction.SOUTH,
            new AABB(3.125, 64.125, 0.875, 3.875, 64.875, 1.0), "ae2:terminal");

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void seenNetworkStockExportsUnexpiredSightingsAndRemembersWirelessTerminalItem() {
        SeenNetworkStock seen = new SeenNetworkStock();
        seen.saw(OVERWORLD, NORTH_TERMINAL, true, List.of(
                new Ae2TerminalMenu.StockEntry(1L, new ItemStack(Items.COBBLESTONE), 2048L, false),
                new Ae2TerminalMenu.StockEntry(2L, new ItemStack(Items.PISTON), 0L, true)),
                NOW.minusSeconds(120), true);
        seen.saw(OVERWORLD, SOUTH_TERMINAL, true, List.of(
                new Ae2TerminalMenu.StockEntry(3L, new ItemStack(Items.COAL), 64L, false)),
                NOW.minusSeconds(301), true);
        seen.sawWireless("ae2:wireless_crafting_terminal", true, List.of(
                new Ae2TerminalMenu.StockEntry(4L, new ItemStack(Items.IRON_INGOT), 512L, false)),
                NOW.minusSeconds(30), false);

        List<SeenNetworkStock.PlacedSighting> active = seen.activeSightings(NOW);
        assertEquals(1, active.size(), "超过五分钟的旧记录自动过滤");
        assertEquals("ae2:crafting_terminal", active.getFirst().terminalId());
        assertEquals(2048L, active.getFirst().sighting().storedByItem().get("minecraft:cobblestone"));
        assertTrue(active.getFirst().sighting().craftableItems().contains("minecraft:piston"));

        SeenNetworkStock.WirelessSighting wireless = seen.activeWireless(NOW).orElseThrow();
        assertEquals("ae2:wireless_crafting_terminal", wireless.terminalId());
        assertFalse(wireless.sighting().complete());
        assertEquals(512L, wireless.sighting().storedByItem().get("minecraft:iron_ingot"));
        assertTrue(seen.activeWireless(NOW.plusSeconds(400)).isEmpty(), "无线终端超过五分钟同样过期");
    }

    @Test
    void storingUpdatesRememberedStockBeforeClosingTerminalMenu() {
        SeenNetworkStock seen = new SeenNetworkStock();
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        menu.player.set(0, new ItemStack(Items.IRON_INGOT, 32));

        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.sharePage(menu.raw);
        reads.linked = Optional.of(true);
        reads.stock = List.of();

        Ae2Compat compat = new Ae2Compat(emptyTerminals(), reads, new NoGrid());
        TerminalFlowFakes.StubPlayer player = new TerminalFlowFakes.StubPlayer();
        MenuNetworkStores stores = new MenuNetworkStores(compat, TerminalFlowFakes.services(() -> player),
                seen, () -> NOW);

        TerminalAccess access = new TerminalAccess() {
            @Override public MenuOpening opening() {
                return new MenuOpening() {
                    @Override public Optional<OpenedMenu> opened() {
                        return Optional.of(menu);
                    }
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.done();
                    }
                    @Override public String describe() { return "打开终端"; }
                };
            }

            @Override public boolean belongs(AbstractContainerMenu raw, ClientLevel level) {
                return raw == menu.raw;
            }

            @Override public String describe() {
                return NORTH_TERMINAL.describe();
            }

            @Override public void remember(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, boolean complete) {
                seen.saw(OVERWORLD, NORTH_TERMINAL, linked, stock, NOW, complete);
            }
        };

        TerminalSession session = new TerminalSession(access, compat) {
            @Override AbstractContainerMenu rawPage(PlayerContext ignored) {
                return menu.raw;
            }
        };

        List<BackpackStack> stacks = List.of(new BackpackStack("minecraft:iron_ingot", 32, 64, false, false, false, false));
        Action storing = stores.storingWithSession(session, stacks, "存铁锭", new NoopRecords());
        TerminalFlowFakes.Ticks ticks = new TerminalFlowFakes.Ticks(player);

        // 推进到存入动作把背包里的铁锭快速移动进网络后，让终端界面读数反映存完后的 32 个铁锭，再完成关界面。
        ActionStatus status = ActionStatus.running();
        for (int step = 0; step < 30 && status instanceof ActionStatus.Running; step++) {
            if (menu.player.get(0).isEmpty()) {
                reads.stock = List.of(new Ae2TerminalMenu.StockEntry(1L, new ItemStack(Items.IRON_INGOT), 32L, false));
            }
            status = storing.tick(ticks);
            ticks.advance();
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertTrue(menu.closed);

        SeenNetworkStock.Sighting remembered = seen.lastSeen(OVERWORLD, NORTH_TERMINAL, NOW).orElseThrow();
        assertTrue(remembered.linked());
        assertFalse(remembered.complete(), "存入关界面前刷新的存货记为未看全，避免漏算还没同步完的大网络分包");
        assertEquals(32L, remembered.storedByItem().get("minecraft:iron_ingot"));
    }

    @Test
    void rememberedNetworksPrunesRemovedTerminalInLoadedChunkWhileKeepingUnloadedAndOtherDimension() {
        SeenNetworkStock seen = new SeenNetworkStock();
        Ae2Terminals.Terminal removedLoaded = NORTH_TERMINAL;
        Ae2Terminals.Terminal keptUnloaded = new Ae2Terminals.Terminal(
                new BlockPos(200, 64, 0), Direction.EAST,
                new AABB(200.875, 64.125, 0.125, 201.0, 64.875, 0.875), "ae2:terminal");
        Ae2Terminals.Terminal keptNether = new Ae2Terminals.Terminal(
                new BlockPos(3, 64, 0), Direction.WEST,
                new AABB(3.0, 64.125, 0.125, 3.125, 64.875, 0.875), "ae2:terminal");

        List<Ae2TerminalMenu.StockEntry> sample = List.of(
                new Ae2TerminalMenu.StockEntry(1L, new ItemStack(Items.COBBLESTONE), 100L, false));
        seen.saw(OVERWORLD, removedLoaded, true, sample, NOW);
        seen.saw(OVERWORLD, keptUnloaded, true, sample, NOW);
        seen.saw(NETHER, keptNether, true, sample, NOW);

        Ae2Compat compat = new Ae2Compat(emptyTerminals(), new TerminalFlowFakes.Reads(), new NoGrid());
        MenuNetworkStores stores = new MenuNetworkStores(compat, TerminalFlowFakes.services(() -> null),
                seen, () -> NOW, (dimension, block, side) -> {
                    if (dimension.equals(OVERWORLD) && block.equals(removedLoaded.block())) {
                        return MenuNetworkStores.TerminalPresence.GONE_IN_LOADED_CHUNK;
                    }
                    return MenuNetworkStores.TerminalPresence.UNCHECKED;
                });

        List<NetworkStorage.RememberedNetwork> remembered = stores.rememberedNetworks();
        assertEquals(2, remembered.size(), "已加载区块被拆掉的终端当场淘汰，未加载与异维度的保留");
        assertTrue(seen.lastSeen(OVERWORLD, removedLoaded, NOW).isEmpty(), "被淘汰的终端已从 SeenNetworkStock 删除");
        assertTrue(seen.lastSeen(OVERWORLD, keptUnloaded, NOW).isPresent());
        assertTrue(seen.lastSeen(NETHER, keptNether, NOW).isPresent());
    }

    @Test
    void choosePanelTerminalMatchesPreferredSideOrNearestPanelToEyes() {
        BlockPos cell = new BlockPos(3, 64, 0);
        List<Ae2Terminals.Terminal> both = List.of(NORTH_TERMINAL, SOUTH_TERMINAL);

        assertEquals(NORTH_TERMINAL, MenuNetworkStores.choosePanelTerminal(
                both, cell, Direction.NORTH, new Vec3(3.5, 65.6, 2.0)).orElseThrow());
        assertTrue(MenuNetworkStores.choosePanelTerminal(
                both, cell, Direction.EAST, new Vec3(3.5, 65.6, 2.0)).isEmpty(),
                "指定的那一面没有装终端时不强扭去点别的面");

        // 未指定面时，角色站在南侧（z=2.0）挑离眼睛最近的南面面板；站在北侧（z=-1.0）挑北面面板。
        assertEquals(SOUTH_TERMINAL, MenuNetworkStores.choosePanelTerminal(
                both, cell, null, new Vec3(3.5, 65.6, 2.0)).orElseThrow());
        assertEquals(NORTH_TERMINAL, MenuNetworkStores.choosePanelTerminal(
                both, cell, null, new Vec3(3.5, 65.6, -1.0)).orElseThrow());
    }

    @Test
    void terminalMenuLookWaitsForStockSettlesEmptyAndUnlinkedWritesMemoryAndClosesMenu() {
        SeenNetworkStock seen = new SeenNetworkStock();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of(
                new Ae2TerminalMenu.StockEntry(1L, new ItemStack(Items.COBBLESTONE), 2048L, false),
                new Ae2TerminalMenu.StockEntry(2L, new ItemStack(Items.IRON_INGOT), 64L, false),
                new Ae2TerminalMenu.StockEntry(3L, new ItemStack(Items.PISTON), 0L, true));
        Ae2Compat compat = new Ae2Compat(emptyTerminals(), reads, new NoGrid());

        ClosableChannel channel = new ClosableChannel();
        TerminalMenuLook stockedLook = new TerminalMenuLook(channel, reads::page, compat,
                (linked, stock, complete) -> seen.saw(OVERWORLD, NORTH_TERMINAL, linked, stock, NOW, complete));
        runLookUntilDone(stockedLook);

        assertTrue(channel.closed);
        assertEquals("minecraft:cobblestone ×2048、minecraft:iron_ingot ×64（共 2 种物品；可下单合成：minecraft:piston）",
                stockedLook.summary().orElseThrow());
        assertEquals(2048L, seen.lastSeen(OVERWORLD, NORTH_TERMINAL, NOW).orElseThrow()
                .storedByItem().get("minecraft:cobblestone"));

        // 空网络：多等 10 刻防首包未到，确认仍为空后如实写明"空的（已连上 ME 网络，0 种物品）"。
        reads.stock = List.of();
        ClosableChannel emptyChannel = new ClosableChannel();
        TerminalMenuLook emptyLook = new TerminalMenuLook(emptyChannel, reads::page, compat,
                (linked, stock, complete) -> seen.sawWireless("ae2:wireless_terminal", linked, stock, NOW, complete));
        int ticksForEmpty = runLookUntilDone(emptyLook);
        assertTrue(ticksForEmpty > TerminalMenuLook.STOCK_SETTLE_TICKS);
        assertEquals("空的（已连上 ME 网络，0 种物品）", emptyLook.summary().orElseThrow());

        // 未连网：写明界面给出的未连网原因。
        reads.linked = Optional.of(false);
        reads.why = "超出无线范围";
        ClosableChannel unlinkedChannel = new ClosableChannel();
        TerminalMenuLook unlinkedLook = new TerminalMenuLook(unlinkedChannel, reads::page, compat,
                (linked, stock, complete) -> seen.sawWireless("ae2:wireless_terminal", linked, stock, NOW, complete));
        runLookUntilDone(unlinkedLook);
        assertEquals("没连上 ME 网络（超出无线范围）", unlinkedLook.summary().orElseThrow());
        assertFalse(seen.lastSeenWireless(NOW).orElseThrow().linked());
    }

    private static int runLookUntilDone(TerminalMenuLook look) {
        TerminalFlowFakes.StubPlayer player = new TerminalFlowFakes.StubPlayer();
        TerminalFlowFakes.Ticks ticks = new TerminalFlowFakes.Ticks(player);
        int steps = 0;
        for (; steps < 60; steps++) {
            ActionStatus status = look.tick(ticks);
            ticks.advance();
            if (status instanceof ActionStatus.Done) break;
        }
        return steps;
    }

    private static Ae2Terminals emptyTerminals() {
        return new Ae2Terminals() {
            @Override public List<Terminal> near(ClientLevel level, BlockPos center, int radius) {
                return List.of();
            }

            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
                return false;
            }
        };
    }

    private static final class ClosableChannel implements MenuChannel {
        boolean closed;
        final List<String> log = new ArrayList<>();

        @Override public boolean stillOpen() { return !closed; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean click(int slot, int button) { return false; }
        @Override public boolean quickMove(int slot) { return false; }
        @Override public boolean selectTradeOffer(int offerIndex) { return false; }
        @Override public void requestClose() {
            log.add("close");
            closed = true;
        }
    }

    private static final class NoopRecords implements TaskRecords {
        @Override public void change(Change change) {}
        @Override public void unconfirmed(Change change) {}
        @Override public void attempt(String tried, String whatHappened) {}
    }
}
