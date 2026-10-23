// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 挑终端：记得有货的去最近那台；没有就先看没看过的，再看上次不行的；别人的不去。上次看到的网络存货五分钟内算数。 */
class TerminalChooserTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Predicate<String> TORCH = "minecraft:torch"::equals;

    private static Ae2Terminals.Terminal terminalAt(int x) {
        return new Ae2Terminals.Terminal(new BlockPos(x, 64, 0), Direction.NORTH, new AABB(x, 64, 0, x + 1, 65, 0.125),
                "ae2:terminal");
    }

    private static SeenNetworkStock.Sighting seen(boolean linked, Map<String, Long> stored) {
        return new SeenNetworkStock.Sighting(linked, stored, NOW);
    }

    private static TerminalChooser.Candidate candidate(int x, double distance, boolean usable,
            SeenNetworkStock.Sighting sighting) {
        return new TerminalChooser.Candidate(terminalAt(x), distance, usable, Optional.ofNullable(sighting));
    }

    @Test
    void 记得有货的去最近那台_不赌没看过的() {
        TerminalChooser.Choice choice = TerminalChooser.choose(List.of(
                candidate(1, 3, true, null),
                candidate(2, 20, true, seen(true, Map.of("minecraft:torch", 40L))),
                candidate(3, 9, true, seen(true, Map.of("minecraft:torch", 8L)))), TORCH, "minecraft:torch", 64);
        TerminalChooser.Known known = assertInstanceOf(TerminalChooser.Known.class, choice);
        assertEquals(terminalAt(3), known.candidate().terminal());
        assertEquals(8, known.count());
    }

    @Test
    void 记得网络里没现货但有样板_也算记得有_比没看过的先去() {
        TerminalChooser.Choice choice = TerminalChooser.choose(List.of(
                candidate(1, 3, true, null),
                candidate(2, 9, true, new SeenNetworkStock.Sighting(true, Map.of(), NOW, Set.of("minecraft:torch")))),
                TORCH, "minecraft:torch", 64);
        TerminalChooser.Known known = assertInstanceOf(TerminalChooser.Known.class, choice);
        assertEquals(terminalAt(2), known.candidate().terminal());
        assertEquals(0, known.count());
        assertTrue(known.craftable(), "到了在终端里下单补上");
    }

    @Test
    void 都没看过就去最近一台碰运气() {
        TerminalChooser.Choice choice = TerminalChooser.choose(List.of(
                candidate(1, 12, true, null), candidate(2, 5, true, null)), TORCH, "minecraft:torch", 64);
        TerminalChooser.Unknown unknown = assertInstanceOf(TerminalChooser.Unknown.class, choice);
        assertEquals(terminalAt(2), unknown.candidate().terminal());
    }

    @Test
    void 上次不行的照样去看_排在没看过的后面_别人的不去() {
        TerminalChooser.Choice choice = TerminalChooser.choose(List.of(
                candidate(1, 2, false, null),
                candidate(2, 4, true, seen(false, Map.of())),
                candidate(3, 6, true, seen(true, Map.of("minecraft:cobblestone", 900L))),
                candidate(4, 9, true, null)),
                TORCH, "minecraft:torch", 64);
        TerminalChooser.Unknown unknown = assertInstanceOf(TerminalChooser.Unknown.class, choice);
        assertEquals(terminalAt(4), unknown.candidate().terminal(), "没看过的比上次不行的先去");
        assertTrue(unknown.lastTime().isEmpty());
    }

    @Test
    void 只剩上次不行的_去最近那台看一眼_写明上次看到的情况() {
        TerminalChooser.Choice choice = TerminalChooser.choose(List.of(
                candidate(2, 4, true, seen(false, Map.of())),
                candidate(3, 6, true, seen(true, Map.of("minecraft:cobblestone", 900L)))),
                TORCH, "minecraft:torch", 64);
        TerminalChooser.Unknown unknown = assertInstanceOf(TerminalChooser.Unknown.class, choice);
        assertEquals(terminalAt(2), unknown.candidate().terminal());
        assertEquals(Optional.of("上次看没连上网络（没电或没频道）"), unknown.lastTime());

        TerminalChooser.Unknown empty = assertInstanceOf(TerminalChooser.Unknown.class, TerminalChooser.choose(
                List.of(candidate(3, 6, true, seen(true, Map.of("minecraft:cobblestone", 900L)))),
                TORCH, "minecraft:torch", 64));
        assertEquals(Optional.of("上次看网络里没有minecraft:torch"), empty.lastTime());
    }

    @Test
    void 附近的终端都是别人的_不去() {
        TerminalChooser.None none = assertInstanceOf(TerminalChooser.None.class, TerminalChooser.choose(
                List.of(candidate(1, 2, false, null), candidate(2, 5, false, null)), TORCH, "minecraft:torch", 64));
        assertTrue(none.reason().contains("都是别人的") && none.reason().contains("2 台"), none.reason());
    }

    @Test
    void 附近没有终端() {
        TerminalChooser.None none = assertInstanceOf(TerminalChooser.None.class,
                TerminalChooser.choose(List.of(), TORCH, "minecraft:torch", 32));
        assertEquals("附近 32 格内没有 ME 终端", none.reason());
    }

    @Test
    void 存东西去最近一台能用的_上次没连上的排后面_别人的不去() {
        // 存东西不看网络里有什么：别人的不用，上次看没连上的虽然近也排后面，其余最近的先。
        TerminalChooser.Choice choice = TerminalChooser.toStore(List.of(
                candidate(1, 2, false, null),
                candidate(2, 4, true, seen(false, Map.of())),
                candidate(3, 9, true, seen(true, Map.of())),
                candidate(4, 12, true, null)), 32);
        TerminalChooser.Unknown unknown = assertInstanceOf(TerminalChooser.Unknown.class, choice);
        assertEquals(terminalAt(3), unknown.candidate().terminal());
    }

    @Test
    void 存东西_能用的都上次没连上_照样去最近那台_写明上次的情况() {
        TerminalChooser.Choice choice = TerminalChooser.toStore(List.of(
                candidate(1, 8, true, seen(false, Map.of())),
                candidate(2, 3, true, seen(false, Map.of()))), 32);
        TerminalChooser.Unknown unknown = assertInstanceOf(TerminalChooser.Unknown.class, choice);
        assertEquals(terminalAt(2), unknown.candidate().terminal());
        assertTrue(unknown.lastTime().orElseThrow().contains("没连上"));
    }

    @Test
    void 存东西_附近没有终端或都是别人的_写明是哪种() {
        TerminalChooser.None none = assertInstanceOf(TerminalChooser.None.class, TerminalChooser.toStore(List.of(), 32));
        assertTrue(none.reason().contains("32 格内没有 ME 终端"), none.reason());
        TerminalChooser.None others = assertInstanceOf(TerminalChooser.None.class,
                TerminalChooser.toStore(List.of(candidate(1, 2, false, null), candidate(2, 5, false, null)), 32));
        assertTrue(others.reason().contains("都是别人的") && others.reason().contains("2 台"), others.reason());
    }

    @Test
    void 上次看到的网络存货按物品合计_五分钟后当没看过() {
        SeenNetworkStock stock = new SeenNetworkStock();
        Ae2Terminals.Terminal terminal = terminalAt(1);
        stock.saw("minecraft:overworld", terminal, true, List.of(
                new Ae2TerminalMenu.StockEntry(1, new ItemStack(Items.TORCH), 30, false),
                new Ae2TerminalMenu.StockEntry(2, new ItemStack(Items.TORCH), 10, false),
                new Ae2TerminalMenu.StockEntry(3, new ItemStack(Items.STICK), 0, true)), NOW);
        SeenNetworkStock.Sighting sighting = stock.lastSeen("minecraft:overworld", terminal,
                NOW.plus(4, ChronoUnit.MINUTES)).orElseThrow();
        assertEquals(40, sighting.count(TORCH));
        assertEquals(Map.of("minecraft:torch", 40L), sighting.storedByItem());
        assertTrue(stock.lastSeen("minecraft:overworld", terminal, NOW.plus(6, ChronoUnit.MINUTES)).isEmpty());
        assertTrue(stock.lastSeen("minecraft:the_nether", terminal, NOW).isEmpty());
        stock.forget("minecraft:overworld", terminal.block(), terminal.side());
        assertTrue(stock.lastSeen("minecraft:overworld", terminal, NOW).isEmpty());
    }
}
