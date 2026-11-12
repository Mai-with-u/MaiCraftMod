// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 随身背包的来源：包是身上的仓库，不管背在 Curios 饰品栏、穿在胸甲格、攥在副手还是揣在主背包里，
 * 要的东西包里可能就有——开包取出来算拿到，并入"身上"这条途径。
 *
 * <p>问价按上次开包读到的存货（本次进世界内，五分钟内算数）报明确数量；没读过报"包里可能有，
 * 不知道有多少"，排在数量明确的来源后面。执行按每只包的结构化栏位定位（栏位名、分组名与从 0 起算的槽号）
 * 逐只精确发打开请求，只看主存储（升级槽与记忆筛选图标不算货），按要的量搬进主背包，关箱前把这次看到的主存储存货与空槽数
 * 一并记进 {@link SeenBackpackStock}。带无限格升级的背包读数超常理，按未证实跳过。
 */
public final class SophisticatedBackpackSource implements ItemSource {

    /** 开包、等同步、搬完、关上，大约几个动作：就在身上，不用走路。 */
    private static final int ACTIONS_PER_VISIT = 4;
    /** 一次开包取货的总期限（刻）；到点如实收场。 */
    private static final long GIVE_UP_TICKS = 20L * 30;
    /** 一笔搬运结清后，等两侧内容同步过来的宽限（刻）。 */
    private static final int SETTLE_TICKS = 5;
    /** 等背包界面打开并同步列布局的期限（刻）。 */
    private static final int OPEN_WAIT_TICKS = 40;

    private final BackpackCompat compat;
    private final PlayerServices services;
    private final SeenBackpackStock seen;
    private final Supplier<Instant> clock;

    public SophisticatedBackpackSource(BackpackCompat compat, PlayerServices services, SeenBackpackStock seen,
            Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 保留旧参数表的构造：忽略多传的 menus，统一走 compat 的安全包裹调用。 */
    public SophisticatedBackpackSource(BackpackCompat compat, PlayerServices services, BackpackMenus menus,
            SeenBackpackStock seen, Supplier<Instant> clock) {
        this(compat, services, seen, clock);
    }

    @Override public String describe() {
        return "随身背包";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CARRIED;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        boolean exactLocations = compat.hasExactSlotLocations(services.context());
        List<BackpackSlot> carriedPacks = compat.carriedSlots(services.context(), services.backpack());
        int carried = carriedPacks.size();
        if (carried == 0) {
            return new SourceQuote.Unavailable(describe(), "身上没有随身背包");
        }
        Instant now = clock.get();
        Set<BackpackSlot.Location> present = exactLocations ? locationsOf(carriedPacks) : null;
        Optional<SeenBackpackStock.Sighting> stock = present == null ? seen.fresh(now) : seen.fresh(present, now);
        int seenCount = present == null ? seen.freshCount(now) : seen.freshCount(present, now);
        if (stock.isEmpty()) {
            if (seenCount >= carried) {
                return new SourceQuote.Unavailable(describe(), "身上的随身背包带着无限格升级，读不出常规存货");
            }
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(0, ACTIONS_PER_VISIT * carried),
                    "包里可能有，还没开过或读数过时，不知道有多少", "");
        }
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId,
                services.itemTags().tagsOf(itemId));
        int count = stock.get().count(wanted);
        // 身上不止一只包、有的还没看过：看过的不够，没看过的那几只里可能还有。
        int unseen = Math.max(0, carried - seenCount);
        if (count <= 0 && unseen == 0) {
            return new SourceQuote.Unavailable(describe(), "上次开包（" + ago(stock.get().seenAt(), now)
                    + "）时里面没有" + request.wanted().describe());
        }
        if (count < request.count() && unseen > 0) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(0, ACTIONS_PER_VISIT * carried),
                    "看过的包里有 " + count + " 件，另有 " + unseen + " 只包还没开过，不知道有多少", "");
        }
        return new SourceQuote.Offer(describe(), Math.min(count, request.count()),
                new AcquisitionCost(0, ACTIONS_PER_VISIT),
                "记的是" + ago(stock.get().seenAt(), now) + "开包看到的数，开包取货时以现场为准", "");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        // 到动手时包可能已经不在身上（刚腾地方给了别人）：连饰品栏、胸甲、副手与主背包一起重认一遍。
        if (compat.carriedSlots(services.context(), services.backpack()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TakeAction(request));
    }

    private static Set<BackpackSlot.Location> locationsOf(List<BackpackSlot> packs) {
        Set<BackpackSlot.Location> set = new LinkedHashSet<>();
        for (BackpackSlot pack : packs) set.add(pack.location());
        return set;
    }

    /**
     * 开包取货的动作：按结构化定位打开 → 按量搬 → 记下存货与空槽 → 关上；身上不止一只包时一只一只精确打开，
     * 这只没有或不够就关上开下一只，打不开也换下一只，不卡在一只包上。
     */
    private final class TakeAction implements Action, ReportsUnconfirmed {

        private final ItemRequest request;
        private ClaimedBackpackMenu menu;
        private MenuTakeRound round;
        private Action closingUnread;
        private int waited;
        private int totalTicks;
        private int quietTicks;
        /** 还没开的几只随身背包定位。 */
        private final Deque<BackpackSlot.Location> toOpen = new ArrayDeque<>();
        private boolean slotsKnown;
        private BackpackSlot.Location opening = BackpackSlot.Location.FIRST_CARRIED;
        private boolean openRequested;
        private final List<String> deadEnds = new ArrayList<>();
        private int carriedAtStart = -1;

        TakeAction(ItemRequest request) {
            this.request = request;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (carriedAtStart < 0) carriedAtStart = carriedWanted();
            if (!slotsKnown) {
                slotsKnown = true;
                for (BackpackSlot pack : compat.carriedSlots(services.context(), services.backpack())) {
                    toOpen.add(pack.location());
                }
                if (toOpen.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "身上已经没有随身背包了", null));
                }
                opening = toOpen.poll();
            }
            if (closingUnread != null) {
                ActionStatus closeStatus = closingUnread.tick(tick);
                if (closeStatus instanceof ActionStatus.Running) return closeStatus;
                closingUnread = null;
                return afterClosed();
            }
            // 手上的笔先跑完：够没够等它结清再说。
            if (round != null && round.movePending()) return round.tick(tick, Optional.empty());
            // 已经够了：界面还开着就记一笔存货、关上；还没开包就直接完成。
            if (enough()) {
                if (menu == null) return ActionStatus.done();
                return round.tick(tick, menu.reading());
            }
            if (++totalTicks > GIVE_UP_TICKS * (toOpen.size() + 1)) {
                if (round != null) round.abandonAndNote("总期限到了");
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "开包取货超时", null));
            }
            if (menu == null) return open();
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                if (!menu.stillOpen()) return round.tick(tick, Optional.empty());
                if (++quietTicks > SETTLE_TICKS * 4) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "背包界面的内容一直没同步完", null));
                }
                return ActionStatus.running();
            }
            quietTicks = 0;
            return round.tick(tick, reading);
        }

        // 按结构化栏位定位发打开请求，等界面打开且升级占列同步完再认领，避免列同步重排槽位。
        private ActionStatus open() {
            PlayerContext context = services.context().get();
            if (context == null || context.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，开不了包", null));
            }
            if (!openRequested) {
                compat.openBackpack(opening);
                openRequested = true;
                waited = 0;
                return ActionStatus.progressed();
            }
            AbstractContainerMenu opened = context.localPlayer().containerMenu;
            if (opened != null && compat.isBackpackMenu(opened) && compat.isMenuSynchronized(opened)) {
                MenuChannel channel = ClientMenuChannel.claimCurrent(services.context());
                if (channel == null) {
                    return nextBackpackOr(Problem.of(Problem.Kind.TARGET_GONE, "背包界面点开了又马上没了", null));
                }
                Optional<List<Integer>> slots = compat.mainStorageSlotIds(opened);
                if (slots.isEmpty()) {
                    // 带无限格升级的背包读数超常理：记为不可读并先把已打开的界面关干净，再换下一只。
                    seen.markUnreadable(opening, clock.get());
                    deadEnds.add(opening.describe() + "读数对不上（多半带着无限格升级），取不了货");
                    closingUnread = new ClaimedBackpackMenu(compat, services.context(), channel, List.of()).closing();
                    return ActionStatus.progressed();
                }
                menu = new ClaimedBackpackMenu(compat, services.context(), channel, slots.get());
                round = new MenuTakeRound(menu, List.of(new BackpackWant()), new BackpackEndings());
                return ActionStatus.progressed();
            }
            if (++waited > OPEN_WAIT_TICKS) {
                return nextBackpackOr(Problem.of(Problem.Kind.STUCK,
                        "背包的打开请求发出去了，界面一直没开", null));
            }
            return ActionStatus.running();
        }

        private ActionStatus nextBackpackOr(Problem problem) {
            deadEnds.add(opening.describe() + "：" + problem.message());
            if (toOpen.isEmpty()) {
                return ActionStatus.failed(Problem.of(problem.kind(), String.join("；", deadEnds), null));
            }
            startNextBackpack();
            return ActionStatus.progressed();
        }

        private void startNextBackpack() {
            menu = null;
            round = null;
            quietTicks = 0;
            waited = 0;
            openRequested = false;
            opening = toOpen.poll();
        }

        private ActionStatus afterClosed() {
            if (enough() || toOpen.isEmpty()) {
                return ActionStatus.done();
            }
            startNextBackpack();
            return ActionStatus.progressed();
        }

        private boolean enough() {
            return carriedWanted() - carriedAtStart >= request.count();
        }

        private void recordStock() {
            if (menu == null) {
                seen.forget(opening);
                return;
            }
            menu.recordSighting(seen, opening, clock.get());
        }

        private boolean matches(SlotSnapshot snapshot) {
            if (snapshot.isEmpty()) return false;
            BackpackStack stack = ClientBackpackView.snapshot(snapshot.stack());
            return request.wanted().matches(stack.itemId(), services.itemTags().tagsOf(stack.itemId()));
        }

        private int carriedWanted() {
            int total = 0;
            for (BackpackStack stack : services.backpack().stacks()) {
                if (request.wanted().matches(stack.itemId(), services.itemTags().tagsOf(stack.itemId()))) {
                    total += stack.count();
                }
            }
            return total;
        }

        @Override public void pause() {
        }

        @Override public void close() {
            if (round != null) round.close(false);
            if (menu != null && menu.stillOpen()) menu.abandon();
            menu = null;
            closingUnread = null;
        }

        @Override public Interruptibility interruptibility() {
            return menu != null || closingUnread != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public List<String> unconfirmedFacts() {
            return round == null ? List.of() : round.unconfirmedFacts();
        }

        @Override public String describe() {
            if (closingUnread != null) return "关上随身背包";
            if (menu == null) return "打开" + opening.describe();
            return round.atClosing() ? "关上随身背包"
                    : "从随身背包搬" + request.wanted().describe() + "进主背包";
        }

        private final class BackpackEndings implements MenuTakeRound.Endings {

            @Override
            public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
                recordStock();
            }

            @Override
            public ActionStatus finishAfterClosed(boolean tookAnything) {
                return afterClosed();
            }

            @Override
            public ActionStatus finishAfterMenuGone(boolean tookAnything) {
                return afterClosed();
            }

            @Override
            public Optional<String> closeUnconfirmedNote() {
                return Optional.of("关随身背包的界面没能确认，光标上的东西交给游戏的关闭流程还回");
            }
        }

        private final class BackpackWant implements MenuTakeRound.Want {

            @Override
            public String label() {
                return "从随身背包拿" + request.wanted().describe();
            }

            @Override
            public Predicate<SlotSnapshot> matcher() {
                return TakeAction.this::matches;
            }

            @Override
            public int stillNeeded(MenuContent.Reading reading) {
                return request.count() - (carriedWanted() - carriedAtStart);
            }

            @Override
            public boolean settleAfterDiverge() {
                return true;
            }
        }
    }

    /** 多久以前：给 LLM 看的粗略说法。 */
    private static String ago(Instant seenAt, Instant now) {
        long seconds = Math.max(0, Duration.between(seenAt, now).getSeconds());
        return seconds < 60 ? " " + seconds + " 秒前" : " " + seconds / 60 + " 分钟前";
    }
}
