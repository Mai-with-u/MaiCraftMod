// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * {@code use} 点开一台 ME 终端（线缆面板终端或随身无线终端）后的查看动作：
 * 等待网络连接状态与网络存货表同步完（空表或未连上多等一小会儿防止首包未到），
 * 将看到的网络状态与存货写入 {@link SeenNetworkStock}、整理成可直接展示的文字摘要，再经 {@link MenuSession} 关上界面。
 */
final class TerminalMenuLook implements NetworkStorage.TerminalLook {

    /** 点开后等网络连接状态与存货表同步过来的期限（刻），与取货打开核对一致。 */
    static final int STOCK_WAIT_TICKS = 40;
    /** 空表或未连上时再多等几刻（刻）：防着首包还没同步到就把网络误判成空的。 */
    static final int STOCK_SETTLE_TICKS = 10;
    /** 摘要末尾最多点名几种可下单合成样板；超出部分给总数。 */
    private static final int MAX_LISTED_CRAFTABLE = 10;

    /** 同步完成后把网络状态与存货写进记忆的回调。 */
    @FunctionalInterface
    interface RemembersStock {
        void record(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, boolean complete);
    }

    private enum Stage { WAIT_SYNC, CLOSE, DONE }

    private final MenuChannel channel;
    private final Supplier<AbstractContainerMenu> rawMenu;
    private final Ae2Compat compat;
    private final RemembersStock remember;
    private Stage stage = Stage.WAIT_SYNC;
    private int waited;
    private int settleWaited;
    private String summary;
    private MenuSession session;

    TerminalMenuLook(MenuChannel channel, Supplier<AbstractContainerMenu> rawMenu, Ae2Compat compat,
            RemembersStock remember) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.rawMenu = Objects.requireNonNull(rawMenu, "rawMenu");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.remember = Objects.requireNonNull(remember, "remember");
    }

    @Override public Optional<String> summary() {
        return Optional.ofNullable(summary);
    }

    @Override public ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case WAIT_SYNC -> waitSync();
            case CLOSE -> closeMenu(tick);
            case DONE -> ActionStatus.done();
        };
    }

    // 等连接状态与网络存货表同步完：有货立等即取；空表或未连上多等十刻确认不是首包未到，读到后写进存货记忆再去关界面。
    private ActionStatus waitSync() {
        if (!channel.stillOpen()) {
            stage = Stage.DONE;
            return ActionStatus.done();
        }
        AbstractContainerMenu raw = rawMenu.get();
        Optional<Boolean> linked = raw == null ? Optional.empty() : compat.networkLinked(raw);
        Optional<List<Ae2TerminalMenu.StockEntry>> stock = raw == null ? Optional.empty() : compat.networkStock(raw);
        if (linked.isEmpty() || stock.isEmpty()) {
            if (++waited > STOCK_WAIT_TICKS) {
                stage = Stage.CLOSE;
            }
            return ActionStatus.running();
        }
        boolean clearlyReady = linked.get() && !stock.get().isEmpty();
        if (!clearlyReady && ++settleWaited <= STOCK_SETTLE_TICKS) {
            return ActionStatus.running();
        }
        remember.record(linked.get(), stock.get(), true);
        String unlinkedWhy = linked.get() ? null : compat.unlinkedWhy(raw).orElse(null);
        summary = formatSummary(linked.get(), unlinkedWhy, stock.get());
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 经界面会话关上刚点开的终端界面；界面先一步被关掉也算完成。
    private ActionStatus closeMenu(TickContext tick) {
        if (session == null) {
            MenuSession.Claim claim = MenuSession.claim(channel);
            if (claim instanceof MenuSession.Claim.Refused) {
                if (channel.stillOpen()) channel.requestClose();
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            session = ((MenuSession.Claim.Owned) claim).session();
        }
        long clientTick = tick.player() == null ? tick.gameTick() : tick.player().clientTick();
        return switch (session.closeNow(channel, clientTick)) {
            case MenuSession.Closing.Closed closed -> {
                stage = Stage.DONE;
                yield ActionStatus.done();
            }
            case MenuSession.Closing.Failed failed -> ActionStatus.failed(failed.problem());
            default -> ActionStatus.running();
        };
    }

    // 任务中途被取消而终端界面还开着时，主动请求游戏关一次，不把终端界面留在屏幕上。
    @Override public void close() {
        if (stage != Stage.DONE && channel.stillOpen()) {
            channel.requestClose();
        }
    }

    @Override public String describe() {
        return stage == Stage.WAIT_SYNC ? "等 ME 终端的网络存货同步完" : "关上点开的 ME 终端界面";
    }

    /** 把终端界面的连接状态、现货与样板整理成 {@code use} 结果里可读的一段摘要文字。 */
    static String formatSummary(boolean linked, String unlinkedWhy, List<Ae2TerminalMenu.StockEntry> stock) {
        if (!linked) {
            String why = unlinkedWhy == null || unlinkedWhy.isBlank() ? "没电或没频道" : unlinkedWhy;
            return "没连上 ME 网络（" + why + "）";
        }
        Map<String, Long> storedByItem = new LinkedHashMap<>();
        Set<String> craftable = new TreeSet<>();
        for (Ae2TerminalMenu.StockEntry entry : stock) {
            String itemId = BuiltInRegistries.ITEM.getKey(entry.sample().getItem()).toString();
            if (entry.craftable()) craftable.add(itemId);
            if (entry.stored() > 0) storedByItem.merge(itemId, entry.stored(), Long::sum);
        }
        String craftSuffix = formatCraftableSuffix(craftable);
        if (storedByItem.isEmpty()) {
            return "空的（已连上 ME 网络，0 种物品" + craftSuffix + "）";
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(storedByItem.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        List<String> lines = new ArrayList<>(sorted.size());
        for (Map.Entry<String, Long> entry : sorted) {
            lines.add(entry.getKey() + " ×" + entry.getValue());
        }
        return String.join("、", lines) + "（共 " + storedByItem.size() + " 种物品" + craftSuffix + "）";
    }

    private static String formatCraftableSuffix(Set<String> craftable) {
        if (craftable.isEmpty()) return "";
        List<String> names = List.copyOf(craftable);
        if (names.size() <= MAX_LISTED_CRAFTABLE) {
            return "；可下单合成：" + String.join("、", names);
        }
        return "；可下单合成：" + String.join("、", names.subList(0, MAX_LISTED_CRAFTABLE))
                + " 等 " + names.size() + " 种";
    }
}
