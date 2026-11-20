// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往 ME 网络里存东西与查看终端存货的生产实现：挑一台终端（在身边时身上的无线终端优先，没带或点了地方就找装着的）→
 * 拿出无线终端右键打开，或走过去点在装着的终端面板上 → 核对是这台的界面、等连接状态同步过来
 * → 把要存的几样东西一样样存进网络 → 存完刷新网络存货记忆再关上，不每样开关一次。
 *
 * <p>同时把最近五分钟内点开终端看到的网络存货快照公开给世界记忆观察，并为 {@code use} 提供线缆格终端面板对准与
 * 终端界面网络存货查看器，认终端、开终端和取货共用同一份（{@link NearbyTerminals}）。
 */
public final class MenuNetworkStores implements NetworkStorage {

    /** 核对一台记在记忆里的固定终端此刻在世界里还在不在：只在同维度且所在区块已加载时才能断定已被拆掉。 */
    enum TerminalPresence { PRESENT, GONE_IN_LOADED_CHUNK, UNCHECKED }

    @FunctionalInterface
    interface LoadedTerminalCheck {
        TerminalPresence check(String dimension, BlockPos block, Direction side);
    }

    private final Ae2Compat compat;
    private final PlayerServices services;
    private final SeenNetworkStock seen;
    private final Supplier<Instant> clock;
    /** 认终端、到终端跟前：和取货共用一套认法。 */
    private final NearbyTerminals terminals;
    private final LoadedTerminalCheck terminalCheck;

    public MenuNetworkStores(Ae2Compat compat, PlayerServices services, SeenNetworkStock seen,
            Supplier<Instant> clock) {
        this(compat, services, seen, clock, null);
    }

    MenuNetworkStores(Ae2Compat compat, PlayerServices services, SeenNetworkStock seen,
            Supplier<Instant> clock, LoadedTerminalCheck customCheck) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.terminals = new NearbyTerminals(compat, services, seen, clock);
        this.terminalCheck = customCheck != null ? customCheck : this::checkInLoadedLevel;
    }

    private TerminalPresence checkInLoadedLevel(String dimension, BlockPos block, Direction side) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) return TerminalPresence.UNCHECKED;
        ClientLevel level = player.level();
        if (!level.dimension().location().toString().equals(dimension) || !level.isLoaded(block)) {
            return TerminalPresence.UNCHECKED;
        }
        return compat.terminalStillThere(level, block, side)
                ? TerminalPresence.PRESENT : TerminalPresence.GONE_IN_LOADED_CHUNK;
    }

    @Override public boolean reachable(TerminalSearch search) {
        return pick(search).access() != null;
    }

    @Override public TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search,
            TaskRecords records) {
        if (stacks.isEmpty()) return new TerminalPick.NotFound("没有要存的东西");
        Picked picked = pick(search);
        return picked.access() == null ? new TerminalPick.NotFound(picked.reason())
                : new TerminalPick.Chosen(new Storing(new TerminalSession(picked.access(), compat), stacks,
                        purpose, records));
    }

    // 供离线测试直接喂入带替身界面的 TerminalSession 验证完整存入与关界面前刷新记忆。
    Action storingWithSession(TerminalSession session, List<BackpackStack> stacks, String purpose,
            TaskRecords records) {
        return new Storing(session, stacks, purpose, records);
    }

    @Override public List<RememberedNetwork> rememberedNetworks() {
        Instant now = clock.get();
        List<RememberedNetwork> result = new ArrayList<>();
        seen.activeWireless(now).ifPresent(wireless -> {
            SeenNetworkStock.Sighting sighting = wireless.sighting();
            result.add(RememberedNetwork.wireless(wireless.terminalId(), sighting.linked(), sighting.complete(),
                    sighting.storedByItem(), sighting.craftableItems(), sighting.seenAt()));
        });
        for (SeenNetworkStock.PlacedSighting placed : seen.activeSightings(now)) {
            if (terminalCheck.check(placed.dimension(), placed.block(), placed.side())
                    == TerminalPresence.GONE_IN_LOADED_CHUNK) {
                // 所在区块已加载且这格这面的终端已被拆掉：当场从记忆中淘汰，未加载区块与跨维度保留不动。
                seen.forget(placed.dimension(), placed.block(), placed.side());
                continue;
            }
            SeenNetworkStock.Sighting sighting = placed.sighting();
            BlockPos block = placed.block();
            result.add(RememberedNetwork.placed(placed.terminalId(),
                    new WorldPosition(block.getX(), block.getY(), block.getZ(), placed.dimension()),
                    placed.side(), sighting.linked(), sighting.complete(), sighting.storedByItem(),
                    sighting.craftableItems(), sighting.seenAt()));
        }
        return List.copyOf(result);
    }

    @Override public Optional<AABB> terminalPanelAt(BlockPos cell, Direction preferredSide) {
        if (cell == null) return Optional.empty();
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null || !player.level().isLoaded(cell)) {
            return Optional.empty();
        }
        Vec3 eyePos = player.localPlayer() == null ? null : player.localPlayer().getEyePosition();
        return choosePanelTerminal(compat.terminalsNear(player.level(), cell, 0), cell, preferredSide, eyePos)
                .map(Ae2Terminals.Terminal::panel);
    }

    /**
     * 从一格线缆上的终端列表里挑出要对准的那块面板：指定了 preferredSide 时匹配装在那一面的终端；
     * 未指定时若同格装了多台终端，挑面板中心离角色眼睛最近的那一台。
     */
    static Optional<Ae2Terminals.Terminal> choosePanelTerminal(List<Ae2Terminals.Terminal> candidates,
            BlockPos cell, Direction preferredSide, Vec3 eyePos) {
        List<Ae2Terminals.Terminal> onCell = candidates.stream()
                .filter(terminal -> terminal.block().equals(cell))
                .toList();
        if (onCell.isEmpty()) return Optional.empty();
        if (preferredSide != null) {
            return onCell.stream().filter(terminal -> terminal.side() == preferredSide).findFirst();
        }
        if (onCell.size() == 1 || eyePos == null) {
            return Optional.of(onCell.getFirst());
        }
        return onCell.stream()
                .min(Comparator.comparingDouble(terminal -> terminal.panel().getCenter().distanceToSqr(eyePos)));
    }

    @Override public Optional<TerminalLook> openTerminalLook(BlockPos clickedCell, Direction clickedFace) {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return Optional.empty();
        }
        LocalPlayer player = current.localPlayer();
        AbstractContainerMenu opened = player.containerMenu;
        if (opened == null || opened == player.inventoryMenu) {
            return Optional.empty();
        }
        if (compat.wirelessMenu(opened)) {
            String wirelessId = resolveWirelessItemId(player);
            ClientMenuChannel channel = ClientMenuChannel.claimCurrent(services.context());
            return channel == null ? Optional.empty() : Optional.of(new TerminalMenuLook(
                    channel, () -> currentRawMenu(), compat,
                    (linked, stock, complete) -> seen.sawWireless(wirelessId, linked, stock, clock.get(), complete)));
        }
        ClientLevel level = current.level();
        if (clickedCell == null || !level.isLoaded(clickedCell)) {
            return Optional.empty();
        }
        Optional<Ae2Terminals.Terminal> matched = matchingPlacedTerminal(level, opened, clickedCell, clickedFace);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        String dimension = level.dimension().location().toString();
        Ae2Terminals.Terminal terminal = matched.get();
        ClientMenuChannel channel = ClientMenuChannel.claimCurrent(services.context());
        return channel == null ? Optional.empty() : Optional.of(new TerminalMenuLook(
                channel, () -> currentRawMenu(), compat,
                (linked, stock, complete) -> seen.saw(dimension, terminal, linked, stock, clock.get(), complete)));
    }

    private Optional<Ae2Terminals.Terminal> matchingPlacedTerminal(ClientLevel level, AbstractContainerMenu raw,
            BlockPos clickedCell, Direction clickedFace) {
        List<Ae2Terminals.Terminal> belonging = compat.terminalsNear(level, clickedCell, 0).stream()
                .filter(terminal -> terminal.block().equals(clickedCell))
                .filter(terminal -> compat.menuBelongsTo(raw, level, terminal.block(), terminal.side()))
                .toList();
        if (belonging.isEmpty()) return Optional.empty();
        if (clickedFace != null) {
            Optional<Ae2Terminals.Terminal> onFace = belonging.stream()
                    .filter(terminal -> terminal.side() == clickedFace)
                    .findFirst();
            if (onFace.isPresent()) return onFace;
        }
        return Optional.of(belonging.getFirst());
    }

    private String resolveWirelessItemId(LocalPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty() && compat.wirelessTerminal(mainHand)) {
            return BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString();
        }
        return compat.carriedWirelessTerminal(player).orElse(SeenNetworkStock.DEFAULT_WIRELESS_TERMINAL);
    }

    private AbstractContainerMenu currentRawMenu() {
        PlayerContext current = services.context().get();
        return current == null || current.localPlayer() == null ? null : current.localPlayer().containerMenu;
    }

    /** 挑终端的结果：挑到了 access 不为空；挑不到 reason 写明身上和附近各是什么情况。 */
    private record Picked(TerminalAccess access, String reason) {
    }

    // 在身边找：身上绑好的无线终端拿出来就能开，比走去哪台都近，先用它；没带再找脚边的。
    // 点了地方：只找那里装着的终端，走过去点开。装着的按存东西的挑法挑（别人的不用，上次没连上的排后面）。
    private Picked pick(TerminalSearch search) {
        if (search.nearMeFirst()) {
            Optional<String> wireless = terminals.carriedWireless();
            if (wireless.isPresent()) return new Picked(terminals.wireless(wireless.get()), null);
        }
        Optional<BlockPos> center = search.nearMeFirst() ? terminals.feet() : Optional.of(search.near());
        Optional<String> dimension = terminals.dimension();
        if (center.isEmpty() || dimension.isEmpty()) return new Picked(null, "角色不在世界里，找不了终端");
        TerminalChooser.Choice choice = TerminalChooser.toStore(
                terminals.around(center.get(), search.radius(), search.permissions()), search.radius());
        String noWireless = search.nearMeFirst() ? "身上没有绑了访问点的无线终端，" : "";
        return switch (choice) {
            case TerminalChooser.None none -> new Picked(null, noWireless + none.reason());
            case TerminalChooser.Unknown unknown -> placed(unknown.candidate(), dimension.get(), search);
            case TerminalChooser.Known known -> placed(known.candidate(), dimension.get(), search);
        };
    }

    private Picked placed(TerminalChooser.Candidate candidate, String dimension, TerminalSearch search) {
        return new Picked(terminals.placed(candidate.terminal(), dimension, search.permissions()), null);
    }

    /** 存几堆进网络的会话：打开 → 等连上 → 一样样一格格快速移动 → 刷新网络存货记忆再关上，跨刻推进。 */
    private final class Storing implements Action, ReportsStoreProblem {

        /** 存东西会话的先后顺序。 */
        private enum Stage { OPEN, SYNC, STORE, CLOSE, DONE }

        /** 打开以后存东西的期限（刻）：存几格、关上，半分钟足够；到点如实收场。走过去的路由靠近自己管。 */
        private static final long GIVE_UP_TICKS = 20L * 30;

        private final TerminalSession session;
        private final TerminalSync sync;
        private StoreMoves moves;
        private final List<BackpackStack> stacks;
        private final String purpose;
        private final TaskRecords records;
        private Stage stage = Stage.OPEN;
        private Action closing;
        private long waited;
        /** 收场的原因：一件都没存进时按它失败；存进了就算做完。 */
        private Problem stopReason;

        Storing(TerminalSession session, List<BackpackStack> stacks, String purpose, TaskRecords records) {
            this.session = Objects.requireNonNull(session, "session");
            this.sync = TerminalSync.waitingForLink(session);
            this.stacks = List.copyOf(stacks);
            this.purpose = purpose;
            this.records = records;
        }

        @Override public ActionStatus tick(TickContext tick) {
            // 走过去、点开由打开动作自己管（走不到它会如实失败）；点开以后才开始算存东西的期限。
            if (stage != Stage.OPEN && ++waited > GIVE_UP_TICKS && stage.ordinal() < Stage.CLOSE.ordinal()) {
                stop(Problem.of(Problem.Kind.STUCK, "往 " + session.access().describe() + " 连着的网络存东西超时",
                        null));
            }
            return switch (stage) {
                case OPEN -> open(tick);
                case SYNC -> onTurn(sync.proceed(tick), Stage.STORE);
                case STORE -> onTurn(moves.proceed(tick), null);
                case CLOSE -> closeMenu(tick);
                case DONE -> result();
            };
        }

        // 拿出终端右键打开：打不开把原因原样交回，腾地方改走别的路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = session.opening().tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            session.bindMenu();
            moves = new StoreMoves(session, records, session.access().describe(), stacks, purpose);
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        // 一段交回的走向换成会话的推进：进下一段、收场，或把动作状态原样交回。afterSync 是连上后去的段。
        private ActionStatus onTurn(TerminalTurn turn, Stage afterSync) {
            if (turn instanceof TerminalTurn.Stay(ActionStatus status)) return status;
            if (turn instanceof TerminalTurn.Synced) {
                stage = afterSync;
                return ActionStatus.progressed();
            }
            if (turn instanceof TerminalTurn.FinishNow(Problem reason)) {
                // 界面已经不在了：不用关，直接收场。
                if (stopReason == null) stopReason = reason;
                stage = Stage.DONE;
                return result();
            }
            return stopNow(((TerminalTurn.WrapUp) turn).reason());
        }

        // 关界面：发起关闭请求前趁原始菜单还开着，把刚存完后的网络存货记进记忆；关不上照常收场。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) {
                rememberStockBeforeClose(tick.player());
                closing = session.menu().closing();
            }
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return result();
        }

        private void rememberStockBeforeClose(PlayerContext player) {
            if (session.menu() == null || session.reading().isEmpty()) return;
            AbstractContainerMenu raw = session.rawPage(player);
            if (raw == null) return;
            Optional<Boolean> linked = compat.networkLinked(raw);
            Optional<List<Ae2TerminalMenu.StockEntry>> stock = compat.networkStock(raw);
            if (linked.isPresent() && stock.isPresent()) {
                session.access().remember(linked.get(), stock.get(), false);
            }
        }

        // 收场：存进了就算做完；一件都没存进就按收场原因失败，腾地方改走别的路。
        // 部分存入后仍把余下物品的拒收原因保留在 storeProblem() 中，供上层如实向玩家与模型透出。
        private ActionStatus result() {
            if (stopReason == null || (moves != null && moves.stored() > 0)) return ActionStatus.done();
            return ActionStatus.failed(stopReason);
        }

        @Override public Optional<Problem> storeProblem() {
            if (stopReason != null) return Optional.of(stopReason);
            return moves == null ? Optional.empty() : moves.rejectionProblem();
        }

        // 收场：还没打开界面就没有要关的，直接结束；开着就去关。
        private void stop(Problem reason) {
            if (stopReason == null) stopReason = reason;
            stage = session.menu() == null ? Stage.DONE : Stage.CLOSE;
        }

        private ActionStatus stopNow(Problem reason) {
            stop(reason);
            return ActionStatus.progressed();
        }

        // 凑光标上那一小份、没收下的部分还没点回背包时停不得：东西在光标上，撒手会落进流程外。
        @Override public Interruptibility interruptibility() {
            return moves != null && moves.unsafeToStop()
                    ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public void pause() {
            session.opening().pause();
            session.abandonMenu();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，光标上的东西由原版的关闭流程还回背包。
        @Override public void close() {
            session.opening().close();
            if (closing != null) closing.close();
            if (session.menu() != null && stage != Stage.DONE) session.abandonMenu();
        }

        @Override public String describe() {
            BackpackStack current = moves == null ? stacks.getFirst() : moves.current();
            return stacks.size() == 1
                    ? "把 " + current.count() + " 个 " + current.itemId() + " 存进 "
                            + session.access().describe() + " 连着的网络"
                    : "把 " + stacks.size() + " 样东西存进 " + session.access().describe()
                            + " 连着的网络（正在存 " + current.itemId() + "）";
        }
    }
}
