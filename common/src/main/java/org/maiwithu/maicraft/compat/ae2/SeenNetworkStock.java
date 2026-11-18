// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.worldmemory.SeenStock;

/**
 * 上次看到的网络存货：角色每次点开一台 ME 终端，就记下那一刻网络连没连上、里面各有多少东西。
 * 下次有人要东西，不用先走过去开一遍才知道有没有；时效与按终端记账用行为层的 {@link SeenStock}，
 * 这里只把终端界面读到的存货表喂进去，网络连接状态、样板能做的清单这些网络特有的读法留在联动这一侧。
 *
 * <p>网络里的东西一直在被机器和别人搬，记下的数只当线索，到了终端跟前以现场为准。
 * 只在这次启动内有效，不存盘：重开游戏后第一次要东西时，终端按"没看过、不知道有多少"报。
 * 网络之间看不出谁连着谁，所以按终端分开记，一台终端看到的不算到别的终端头上。
 */
public final class SeenNetworkStock {

    /** 没单独指明是哪件无线终端物品时的默认物品注册 ID。 */
    public static final String DEFAULT_WIRELESS_TERMINAL = "ae2:wireless_terminal";

    private final SeenStock<TerminalAt> placed = new SeenStock<>();
    private final SeenStock<String> wireless = new SeenStock<>();
    /** 各台终端最近一次的网络特有读法：连没连上、哪些有样板、存货表看全了没有；终端本身在哪台是记账的键。 */
    private final Map<TerminalAt, NetworkRead> placedReads = new LinkedHashMap<>();
    /** 身上各件无线终端最近一次的网络特有读法，按终端物品 ID 分记。 */
    private final Map<String, NetworkRead> wirelessReads = new LinkedHashMap<>();
    /** 身上最近一次打开的是哪件无线终端：无线终端连的就是绑定的那张网，不分在哪儿打开。 */
    private String lastWireless;

    /** 一台终端最近一次看到的网络特有读法。 */
    private record NetworkRead(boolean linked, Set<String> craftableItems, boolean complete, String terminalId) {
        NetworkRead {
            craftableItems = Set.copyOf(craftableItems);
        }
    }

    /**
     * 点开一台终端、网络存货同步过来之后记一笔，盖掉这台终端以前的记录。
     *
     * @param dimension 终端所在的维度
     * @param terminal  哪一台终端
     * @param linked    界面显示网络连没连上；没连上时 stock 一般是空的
     * @param stock     界面里的网络存货
     * @param when      看到的时刻
     */
    public void saw(String dimension, Ae2Terminals.Terminal terminal, boolean linked,
            List<Ae2TerminalMenu.StockEntry> stock, Instant when) {
        saw(dimension, terminal, linked, stock, when, true);
    }

    /** @param complete 存货表已经同步齐（连着几刻不再变）；只看到第一包时为 false */
    public void saw(String dimension, Ae2Terminals.Terminal terminal, boolean linked,
            List<Ae2TerminalMenu.StockEntry> stock, Instant when, boolean complete) {
        TerminalAt key = TerminalAt.of(dimension, terminal);
        placed.saw(key, countsOf(stock), when);
        placedReads.put(key, new NetworkRead(linked, craftableOf(stock), complete, terminal.partId()));
    }

    /** 打开身上的无线终端、存货同步过来之后记一笔，盖掉以前的记录。 */
    public void sawWireless(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, Instant when) {
        sawWireless(DEFAULT_WIRELESS_TERMINAL, linked, stock, when, true);
    }

    /** @param complete 存货表已经同步齐；只看到第一包时为 false */
    public void sawWireless(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, Instant when, boolean complete) {
        sawWireless(DEFAULT_WIRELESS_TERMINAL, linked, stock, when, complete);
    }

    /** 记录指定物品 ID 的随身无线终端打开后看到的连接状态与网络存货。 */
    public void sawWireless(String terminalId, boolean linked, List<Ae2TerminalMenu.StockEntry> stock, Instant when,
            boolean complete) {
        String resolvedId = terminalId == null || terminalId.isBlank() ? DEFAULT_WIRELESS_TERMINAL : terminalId;
        lastWireless = resolvedId;
        wireless.saw(resolvedId, countsOf(stock), when);
        wirelessReads.put(resolvedId, new NetworkRead(linked, craftableOf(stock), complete, resolvedId));
    }

    /** 身上的无线终端上次打开时的样子；没开过或过了时效都给空。 */
    public Optional<Sighting> lastSeenWireless(Instant now) {
        return activeWireless(now).map(WirelessSighting::sighting);
    }

    /** 身上的无线终端未过期的存货快照（含无线终端物品 ID）；没开过或过了时效给空。 */
    public Optional<WirelessSighting> activeWireless(Instant now) {
        if (lastWireless == null) return Optional.empty();
        return wireless.fresh(lastWireless, now)
                .map(seen -> sightingOf(wirelessReads.get(lastWireless), seen))
                .map(sighting -> new WirelessSighting(lastWireless, sighting));
    }

    /** 全部未过期的固定终端存货快照；过了时效的旧记录不返回。 */
    public List<PlacedSighting> activeSightings(Instant now) {
        List<PlacedSighting> active = new ArrayList<>();
        for (Map.Entry<TerminalAt, SeenStock.Sighting> entry : placed.freshAll(now).entrySet()) {
            active.add(new PlacedSighting(entry.getKey().dimension(), entry.getKey().block(), entry.getKey().side(),
                    placedReads.get(entry.getKey()).terminalId(), sightingOf(placedReads.get(entry.getKey()),
                    entry.getValue())));
        }
        return List.copyOf(active);
    }

    /** 这台终端上次看到的样子；没看过或已经过了时效都给空。 */
    public Optional<Sighting> lastSeen(String dimension, Ae2Terminals.Terminal terminal, Instant now) {
        TerminalAt key = TerminalAt.of(dimension, terminal);
        return placed.fresh(key, now).map(seen -> sightingOf(placedReads.get(key), seen));
    }

    /** 那一格那一面的终端不在了（被拆、换了别的部件）：忘掉它的记录，免得照着旧数去找。 */
    public void forget(String dimension, BlockPos block, Direction side) {
        TerminalAt key = new TerminalAt(dimension, block.immutable(), side);
        placed.forget(key);
        placedReads.remove(key);
    }

    // 界面存货表 → 行为层记账的件数：同一种物品的几个变体（耐久、附魔不同）合在一起记，
    // 要东西时按物品 ID 或标签认，不细分变体。
    private static Map<String, Long> countsOf(List<Ae2TerminalMenu.StockEntry> stock) {
        Map<String, Long> storedByItem = new LinkedHashMap<>();
        for (Ae2TerminalMenu.StockEntry entry : stock) {
            if (entry.stored() <= 0) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(entry.sample().getItem()).toString();
            storedByItem.merge(itemId, entry.stored(), Long::sum);
        }
        return storedByItem;
    }

    // 有样板能做的也记下：现货是零的东西，下次报价时知道能在网络里下单。
    private static Set<String> craftableOf(List<Ae2TerminalMenu.StockEntry> stock) {
        Set<String> craftable = new LinkedHashSet<>();
        for (Ae2TerminalMenu.StockEntry entry : stock) {
            if (entry.craftable()) craftable.add(BuiltInRegistries.ITEM.getKey(entry.sample().getItem()).toString());
        }
        return craftable;
    }

    // 记账的存货加上这台终端的网络特有读法，拼成调用方要看的快照。
    private static Sighting sightingOf(NetworkRead read, SeenStock.Sighting seen) {
        return new Sighting(read.linked(), seen.stacksByItem(), seen.seenAt(), read.craftableItems(), read.complete());
    }

    /** 这台终端上次看到的样子。 */
    public record Sighting(boolean linked, Map<String, Long> storedByItem, Instant seenAt, Set<String> craftableItems,
            boolean complete) {
        public Sighting {
            Objects.requireNonNull(storedByItem, "storedByItem");
            Objects.requireNonNull(seenAt, "seenAt");
            storedByItem = Map.copyOf(storedByItem);
            craftableItems = craftableItems == null ? Set.of() : Set.copyOf(craftableItems);
        }

        /** 只记现货、没记样板的写法（测试与旧调用用），按同步齐了算。 */
        public Sighting(boolean linked, Map<String, Long> storedByItem, Instant seenAt) {
            this(linked, storedByItem, seenAt, Set.of(), true);
        }

        /** 按同步齐了算。 */
        public Sighting(boolean linked, Map<String, Long> storedByItem, Instant seenAt, Set<String> craftableItems) {
            this(linked, storedByItem, seenAt, craftableItems, true);
        }

        /** 想要的东西网络里有没有样板能做。 */
        public boolean craftable(Predicate<String> wanted) {
            for (String itemId : craftableItems) {
                if (wanted.test(itemId)) return true;
            }
            return false;
        }

        /** 想要的东西网络里有几件：wanted 按物品 ID 回答算不算想要的（标签由调用方查好）。 */
        public long count(Predicate<String> wanted) {
            return SeenStock.count(storedByItem, wanted);
        }
    }

    /** 装在线缆格某一面上的固定终端及其最近一次网络存货快照。 */
    public record PlacedSighting(String dimension, BlockPos block, Direction side, String terminalId,
            Sighting sighting) {
        public PlacedSighting {
            Objects.requireNonNull(dimension, "dimension");
            block = Objects.requireNonNull(block, "block").immutable();
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(sighting, "sighting");
        }
    }

    /** 随身无线终端物品 ID 与其最近一次打开看到的网络存货快照。 */
    public record WirelessSighting(String terminalId, Sighting sighting) {
        public WirelessSighting {
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(sighting, "sighting");
        }
    }

    /** 一台终端的身份：维度、格子与哪一面。 */
    private record TerminalAt(String dimension, BlockPos block, Direction side) {
        static TerminalAt of(String dimension, Ae2Terminals.Terminal terminal) {
            return new TerminalAt(dimension, terminal.block(), terminal.side());
        }
    }
}
