// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 精妙背包的随身背包存放与观察实现：主背包要满时把杂物塞进身上背着或揣着的精妙背包，
 * 不往地上丢；自身观察也从这里读身上各栏位（含 Curios 饰品栏、胸甲、副手与主背包）的精妙背包及新鲜存货。
 *
 * <p>与取货来源共用同一份 {@link SeenBackpackStock}：还没打开过的随身背包按保底空槽数（27 格）参与腾格子排计划，
 * 避免因为返回 0 而永远不第一次开包；一旦开包看到真实主存储，立刻把看到的存货与真实通用空槽数写回账本
 * （真满了记 0，换下一只包或让腾格子改走附近箱子）。精妙背包物品本身绝不往包里塞，多只包时也绝不点起正开着的那只包。
 */
public final class SophisticatedCarriedBackpack implements CarriedBackpack {

    /** 本次进世界还没打开过的随身背包保底按基础皮革包 27 格估算，触发第一次开包后刷新为真实空槽数。 */
    public static final int UNSEEN_ASSUMED_FREE_SLOTS = 27;
    private static final long GIVE_UP_TICKS = 20L * 30;
    private static final int OPEN_WAIT_TICKS = 40;
    private static final int SETTLE_TICKS = 5;

    private final BackpackCompat compat;
    private final PlayerServices services;
    private final SeenBackpackStock seen;
    private final Supplier<Instant> clock;

    public SophisticatedCarriedBackpack(BackpackCompat compat, PlayerServices services, SeenBackpackStock seen,
            Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public int freeSlots() {
        List<BackpackSlot> packs = compat.carriedSlots(services.context(), services.backpack());
        if (packs.isEmpty()) {
            return 0;
        }
        Instant now = clock.get();
        int total = 0;
        for (BackpackSlot pack : packs) {
            Optional<SeenBackpackStock.Sighting> fresh = seen.freshOf(pack.location(), now);
            if (fresh.isEmpty()) {
                total += UNSEEN_ASSUMED_FREE_SLOTS;
            } else if (fresh.get().readable()) {
                total += fresh.get().freeSlots();
            }
        }
        return total;
    }

    @Override public List<CarriedPack> carried() {
        List<BackpackSlot> packs = compat.carriedSlots(services.context(), services.backpack());
        if (packs.isEmpty()) {
            return List.of();
        }
        Instant now = clock.get();
        List<CarriedPack> list = new ArrayList<>(packs.size());
        for (BackpackSlot pack : packs) {
            Optional<SeenBackpackStock.Sighting> fresh = seen.freshOf(pack.location(), now);
            if (fresh.isPresent() && fresh.get().readable()) {
                list.add(new CarriedPack(pack.itemId(), pack.location().whereCode(), true,
                        fresh.get().freeSlots(), fresh.get().stacksByItem()));
            } else {
                list.add(new CarriedPack(pack.itemId(), pack.location().whereCode(), false, 0, Map.of()));
            }
        }
        return List.copyOf(list);
    }

    @Override public Optional<Action> store(BackpackStack stack, TaskRecords records) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(records, "records");
        // 精妙背包本身绝不当杂物往随身背包里塞，避免套娃。
        if (compat.isBackpackItem(stack.itemId())) {
            return Optional.empty();
        }
        List<BackpackSlot> candidates = candidatePacksFor(stack.itemId());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new StoreAction(stack, candidates, records));
    }

    // 挑出值得打开尝试的随身背包：五分钟内确认过读不出、或既无空槽又无同种未满堆可并的跳过。
    private List<BackpackSlot> candidatePacksFor(String itemId) {
        List<BackpackSlot> packs = compat.carriedSlots(services.context(), services.backpack());
        if (packs.isEmpty()) return List.of();
        Instant now = clock.get();
        List<BackpackSlot> candidates = new ArrayList<>(packs.size());
        for (BackpackSlot pack : packs) {
            Optional<SeenBackpackStock.Sighting> fresh = seen.freshOf(pack.location(), now);
            if (fresh.isPresent()) {
                SeenBackpackStock.Sighting sighting = fresh.get();
                if (!sighting.readable()) continue;
                if (sighting.freeSlots() <= 0 && !sighting.stacksByItem().containsKey(itemId)) continue;
            }
            candidates.add(pack);
        }
        return candidates;
    }

    /**
     * 开包塞一堆东西的动作：精确打开候选包 → 在主存储槽里先并堆再放空槽 → 核对两侧差量记账 → 刷新存货与空槽并关包；
     * 这只包满了或过滤拒收就换身上另一只包接着试。
     */
    private final class StoreAction implements Action {

        private final BackpackStack target;
        private final Deque<BackpackSlot> toOpen;
        private final TaskRecords records;
        private BackpackSlot opening;
        private boolean openRequested;
        private ClaimedBackpackMenu menu;
        private TransferRun run;
        private Action closing;
        private boolean puttingBackCursor;
        private int sourceSlotId = -1;
        private int playerBefore;
        private int storageBefore;
        private int storedTotal;
        private boolean sourceEmptied;
        private int waited;
        private int quietTicks;
        private int totalTicks;
        private final List<String> deadEnds = new ArrayList<>();

        StoreAction(BackpackStack target, List<BackpackSlot> candidates, TaskRecords records) {
            this.target = target;
            this.toOpen = new ArrayDeque<>(candidates);
            this.records = records;
            this.opening = this.toOpen.poll();
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (++totalTicks > GIVE_UP_TICKS * (toOpen.size() + 1)) {
                if (run != null && !run.idle()) {
                    int clicks = run.abandon();
                    if (clicks > 0) {
                        records.unconfirmed(new Change(Change.Kind.OTHER, "随身背包", clicks,
                                "往随身背包放 " + target.itemId() + " 超时，有 " + clicks + " 下点击没等到确认"));
                    }
                }
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "往随身背包存东西超时", null));
            }
            if (closing != null) return tickClosing(tick);
            if (menu == null) return open();
            return tickOpened();
        }

        private ActionStatus open() {
            PlayerContext context = services.context().get();
            if (context == null || context.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，开不了随身背包", null));
            }
            if (!openRequested) {
                compat.openBackpack(opening.location());
                openRequested = true;
                waited = 0;
                return ActionStatus.progressed();
            }
            AbstractContainerMenu opened = context.localPlayer().containerMenu;
            if (opened != null && compat.isBackpackMenu(opened) && compat.isMenuSynchronized(opened)) {
                MenuChannel channel = ClientMenuChannel.claimCurrent(services.context());
                if (channel == null) {
                    return nextOrFail(Problem.Kind.TARGET_GONE, "随身背包界面点开了又马上没了");
                }
                Optional<List<Integer>> storageSlots = compat.mainStorageSlotIds(opened);
                if (storageSlots.isEmpty()) {
                    seen.markUnreadable(opening.location(), clock.get());
                    deadEnds.add(opening.location().describe() + "读数超常理（多半带着无限格升级）");
                    closing = new ClaimedBackpackMenu(compat, services.context(), channel, List.of()).closing();
                    return ActionStatus.progressed();
                }
                menu = new ClaimedBackpackMenu(compat, services.context(), channel, storageSlots.get());
                run = new TransferRun(menu);
                return ActionStatus.progressed();
            }
            if (++waited > OPEN_WAIT_TICKS) {
                return nextOrFail(Problem.Kind.STUCK, "打开随身背包的请求发出去了，界面一直没开");
            }
            return ActionStatus.running();
        }

        private ActionStatus tickOpened() {
            if (!run.idle()) {
                return run.tickAction(puttingBackCursor ? this::onCursorPutBack : this::onMoveSettled);
            }
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                if (!menu.stillOpen()) return finishPackAfterMenuGone();
                if (++quietTicks > SETTLE_TICKS * 4) {
                    beginClosing();
                    deadEnds.add(opening.location().describe() + "内容一直没同步完");
                    return ActionStatus.progressed();
                }
                return ActionStatus.running();
            }
            quietTicks = 0;
            return submitMove(reading.get());
        }

        private ActionStatus submitMove(MenuContent.Reading reading) {
            int remainingGoal = Math.max(1, target.count() - storedTotal);
            int sourceIndex = BackpackStorePlan.pickSourceIndex(reading, target.itemId(), remainingGoal,
                    opening.location(), itemId -> itemId.equals(target.itemId()), compat::isBackpackItem);
            if (sourceIndex < 0) {
                // 主背包里已经没有这一种东西可挪了：记下存货关包收工。
                sourceEmptied = true;
                beginClosing();
                return ActionStatus.progressed();
            }
            Optional<BackpackStorePlan.PlannedMove> planned = BackpackStorePlan.planClicks(
                    reading, sourceIndex, slotId -> menu.canStoreInSlot(slotId,
                            reading.playerSnapshots().get(sourceIndex).stack()));
            if (planned.isEmpty()) {
                // 这只包已满或过滤升级拒收这种物品：记下真实空槽数关包，换下一只或收场。
                deadEnds.add(opening.location().describe() + "装不下或过滤拒收 " + target.itemId());
                beginClosing();
                return ActionStatus.progressed();
            }
            sourceSlotId = planned.get().sourceSlotId();
            playerBefore = countItem(reading.playerSnapshots(), target.itemId());
            storageBefore = countItem(reading.containerSnapshots(), target.itemId());
            puttingBackCursor = false;
            run.begin(new TransferRun.Move(planned.get().clicks(),
                    new TransferRun.Check.IntoContainer(this::matchesTarget),
                    "往随身背包放 " + target.itemId()));
            return run.tickAction(this::onMoveSettled);
        }

        private ActionStatus onMoveSettled(TransferRun.MoveResult result) {
            if (result instanceof TransferRun.MoveResult.MenuGone(int clicks)) {
                if (clicks > 0) {
                    records.unconfirmed(new Change(Change.Kind.OTHER, "随身背包", clicks,
                            "往随身背包放 " + target.itemId() + " 时界面被关掉，有 " + clicks + " 下点击没能确认"));
                }
                return finishPackAfterMenuGone();
            }
            // 光标上还残留尾数（服务端过滤或堆上限比本地估算严）：先点回源槽放空光标再确认差量。
            if (!menu.cursorEmpty() && sourceSlotId >= 0) {
                puttingBackCursor = true;
                run.begin(new TransferRun.Move(List.of(new MenuClick(sourceSlotId, false, 0, false)),
                        new TransferRun.Check.PutDown(sourceSlotId),
                        "把放不进随身背包的尾数放回主背包"));
                return ActionStatus.progressed();
            }
            return accountAndClose();
        }

        private ActionStatus onCursorPutBack(TransferRun.MoveResult result) {
            puttingBackCursor = false;
            return accountAndClose();
        }

        private ActionStatus accountAndClose() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                records.unconfirmed(new Change(Change.Kind.OTHER, "随身背包", 1,
                        "往随身背包放 " + target.itemId() + " 后界面读数丢失，没能核对件数"));
                return finishPackAfterMenuGone();
            }
            int out = playerBefore - countItem(reading.get().playerSnapshots(), target.itemId());
            int in = countItem(reading.get().containerSnapshots(), target.itemId()) - storageBefore;
            if (out > 0 && out == in && menu.cursorEmpty()) {
                storedTotal += out;
                sourceEmptied = isSlotEmpty(reading.get(), sourceSlotId);
                records.change(new Change(Change.Kind.ITEM_STORED, target.itemId(), out,
                        "放进了" + opening.location().describe()));
            } else if (out == 0 && in == 0) {
                deadEnds.add(opening.location().describe() + "拒收或已满，没能放进 " + target.itemId());
            } else {
                if (in > 0 && out >= in && menu.cursorEmpty()) {
                    storedTotal += in;
                    records.change(new Change(Change.Kind.ITEM_STORED, target.itemId(), in,
                            "放进了" + opening.location().describe()));
                }
                records.unconfirmed(new Change(Change.Kind.OTHER, "随身背包", Math.max(1, Math.abs(out - in)),
                        "往" + opening.location().describe() + "放 " + target.itemId()
                                + "：主背包少了 " + out + " 件、包里多了 " + in + " 件，对不上账"));
            }
            beginClosing();
            return ActionStatus.progressed();
        }

        private void beginClosing() {
            if (menu != null) {
                menu.recordSighting(seen, opening.location(), clock.get());
                closing = menu.closing();
            }
        }

        private ActionStatus tickClosing(TickContext tick) {
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Failed) {
                records.unconfirmed(new Change(Change.Kind.OTHER, "随身背包", 1,
                        "关随身背包的界面没能确认，光标上的东西交给游戏的关闭流程还回"));
            }
            closing = null;
            menu = null;
            run = null;
            return afterPackClosed();
        }

        private ActionStatus finishPackAfterMenuGone() {
            seen.forget(opening.location());
            menu = null;
            run = null;
            closing = null;
            return afterPackClosed();
        }

        private ActionStatus afterPackClosed() {
            if (sourceEmptied || (storedTotal > 0 && toOpen.isEmpty())) {
                return ActionStatus.done();
            }
            if (!toOpen.isEmpty()) {
                startNextPack();
                return ActionStatus.progressed();
            }
            if (storedTotal > 0) {
                return ActionStatus.done();
            }
            String message = deadEnds.isEmpty()
                    ? "随身背包放不下 " + target.itemId()
                    : String.join("；", deadEnds);
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, message, null));
        }

        private ActionStatus nextOrFail(Problem.Kind kind, String reason) {
            deadEnds.add(opening.location().describe() + "：" + reason);
            if (!toOpen.isEmpty()) {
                startNextPack();
                return ActionStatus.progressed();
            }
            return ActionStatus.failed(Problem.of(kind, String.join("；", deadEnds), null));
        }

        private void startNextPack() {
            opening = toOpen.poll();
            openRequested = false;
            waited = 0;
            quietTicks = 0;
            sourceSlotId = -1;
            puttingBackCursor = false;
        }

        private boolean matchesTarget(SlotSnapshot snapshot) {
            return !snapshot.isEmpty()
                    && ClientBackpackView.snapshot(snapshot.stack()).itemId().equals(target.itemId());
        }

        private int countItem(List<SlotSnapshot> snapshots, String itemId) {
            int total = 0;
            for (SlotSnapshot snapshot : snapshots) {
                if (!snapshot.isEmpty()
                        && ClientBackpackView.snapshot(snapshot.stack()).itemId().equals(itemId)) {
                    total += snapshot.count();
                }
            }
            return total;
        }

        private boolean isSlotEmpty(MenuContent.Reading reading, int menuSlotId) {
            int index = reading.playerSlotIds().indexOf(menuSlotId);
            return index >= 0 && reading.playerSnapshots().get(index).isEmpty();
        }

        @Override public void pause() {
        }

        @Override public void close() {
            if (run != null && !run.idle()) run.abandon();
            if (menu != null && menu.stillOpen()) menu.abandon();
            menu = null;
            run = null;
            closing = null;
        }

        @Override public Interruptibility interruptibility() {
            return menu != null || closing != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            if (closing != null) return "关上随身背包";
            if (menu == null) return "打开" + opening.location().describe();
            return "把 " + target.itemId() + " 放进" + opening.location().describe();
        }
    }
}
