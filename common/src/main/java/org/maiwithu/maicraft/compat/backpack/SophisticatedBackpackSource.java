// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.menu.ClientMenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuClose;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.MenuSessionSource;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 随身背包的来源：包是身上的仓库，要的东西包里可能就有——开包取出来算拿到，并入"身上"这条途径。
 *
 * <p>问价按上次开包读到的存货（本次启动内，五分钟内算数）报明确数量；没读过报"包里可能有，
 * 不知道有多少"，排在数量明确的来源后面；读不到（联动停用、版本对不上）由登记表那一层如实回答。
 * 执行用模组自己的打开请求按格开包（对应玩家在背包里对着那只包按 B 键），身上几只包一只一只开，
 * 只看主存储（升级槽与幽灵槽不算货），按要的量搬进主背包，每笔以身上真的多了为准；
 * 开出界面之后的一轮取货走共用的取货一轮，这里只给差异点：打开是模组自己的请求（对着包按 B 键），
 * 关箱前把这次看到的主存储存货记下来给问价用。带无限格升级的背包读数对不上，按"未证实"处理。
 * 主背包放不下就停，已取的照算；往包里塞是腾地方的事，不在这里。
 */
public final class SophisticatedBackpackSource implements ItemSource {

    /** 开包、等同步、搬完、关上，大约几个动作：就在身上，不用走路。 */
    private static final int ACTIONS_PER_VISIT = 4;
    /** 一次开包取货的总期限（刻）；到点如实收场。 */
    private static final long GIVE_UP_TICKS = 20L * 30;
    /** 一笔搬运结清后，等两侧内容同步过来的宽限（刻）。 */
    private static final int SETTLE_TICKS = 5;
    /** 等背包界面打开的期限（刻）。 */
    private static final int OPEN_WAIT_TICKS = 40;

    private final BackpackCompat compat;
    private final PlayerServices services;
    private final BackpackMenus menus;
    private final SeenBackpackStock seen;
    private final Supplier<Instant> clock;

    public SophisticatedBackpackSource(BackpackCompat compat, PlayerServices services, BackpackMenus menus,
            SeenBackpackStock seen, Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String describe() {
        return "随身背包";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CARRIED;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        int carried = compat.carriedBackpacks(services.backpack()).size();
        if (carried == 0) {
            return new SourceQuote.Unavailable(describe(), "身上没有随身背包");
        }
        Instant now = clock.get();
        Optional<SeenBackpackStock.Sighting> stock = seen.fresh(now);
        if (stock.isEmpty()) {
            return new SourceQuote.Offer(describe(), SourceQuote.Offer.UNKNOWN_COUNT,
                    new AcquisitionCost(0, ACTIONS_PER_VISIT * carried),
                    "包里可能有，还没开过或读数过时，不知道有多少", "");
        }
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId,
                services.itemTags().tagsOf(itemId));
        int count = stock.get().count(wanted);
        // 身上不止一只包、有的还没看过：看过的不够，没看过的那几只里可能还有。
        int unseen = Math.max(0, carried - seen.freshCount(now));
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
        // 到动手时包可能已经不在身上（刚腾地方给了别人）：重新认一遍，没有就交回空让引擎换路。
        if (compat.carriedBackpacks(services.backpack()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TakeAction(request, context.permissions()));
    }

    /**
     * 开包取货的动作：打开 → 按量搬 → 记下存货 → 关上；身上不止一只包时一只一只按格开，
     * 这只没有或不够就关上开下一只，开错了、打不开也换下一只，不卡在一只包上。点出去没能确认的一句一条交回。
     */
    private final class TakeAction implements Action, ReportsUnconfirmed {

        private final ItemRequest request;
        private final Permissions permissions;
        /** 认领到的那只包的界面：读数、点击与关闭都经它。 */
        private ClaimedMenu menu;
        /** 界面认下来之后的一轮取货：逐样盘、记账、关箱都走它。 */
        private MenuTakeRound round;
        private int waited;
        private int totalTicks;
        private int quietTicks;
        /** 还没开的几只包（原版背包格号）；按不了格（饰品栏里的）记 -1，由模组自己找第一只。 */
        private final Deque<Integer> toOpen = new ArrayDeque<>();
        private boolean slotsKnown;
        /** 正在开的那一只。 */
        private int openingSlot = -1;
        /** 这一只的打开请求发过没有：一只只发一次，等界面开，不每刻重发。 */
        private boolean openRequested;
        /** 每只包卡住的原因：都试完还不够时一起交代。 */
        private final List<String> deadEnds = new ArrayList<>();
        private int carriedAtStart = -1;

        TakeAction(ItemRequest request, Permissions permissions) {
            this.request = request;
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (carriedAtStart < 0) carriedAtStart = carriedWanted();
            if (!slotsKnown) {
                slotsKnown = true;
                PlayerContext context = services.context().get();
                if (context != null && context.localPlayer() != null) {
                    toOpen.addAll(compat.carriedSlots(context.localPlayer().getInventory()));
                }
                // 背包里一只都认不出（多半背在饰品栏里）：退回模组自己找第一只。
                if (toOpen.isEmpty()) toOpen.add(-1);
                openingSlot = toOpen.poll();
            }
            // 手上的笔先跑完：够没够等它结清再说。
            if (round != null && round.movePending()) return round.tick(tick, Optional.empty());
            // 已经够了：界面还开着就记一笔存货、关上；还没开包就直接完成。
            if (enough()) {
                if (menu == null) return ActionStatus.done();
                return round.tick(tick, menu.reading());
            }
            // 总期限按还要开几只包放宽：一只包一份期限。
            if (++totalTicks > GIVE_UP_TICKS * (toOpen.size() + 1)) {
                if (round != null) round.abandonAndNote("总期限到了");
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "开包取货超时", null));
            }
            if (menu == null) return open();
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                // 界面被合上了（打断、模组自己关的）：照界面没了收场，搬走多少由引擎清点；
                // 还开着只是没同步完的，等宽限。
                if (!menu.stillOpen()) return round.tick(tick, Optional.empty());
                if (++quietTicks > SETTLE_TICKS * 4) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "背包界面的内容一直没同步完", null));
                }
                return ActionStatus.running();
            }
            quietTicks = 0;
            return round.tick(tick, reading);
        }

        // 按格开这一只（对应玩家在背包里对着它按 B 键），请求只发一次，等背包界面真的打开并认领它。
        private ActionStatus open() {
            PlayerContext context = services.context().get();
            if (context == null || context.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻掌握不到角色，开不了包", null));
            }
            if (!openRequested) {
                if (openingSlot >= 0) {
                    menus.open(openingSlot);
                } else {
                    menus.openCarried();
                }
                openRequested = true;
                waited = 0;
                return ActionStatus.progressed();
            }
            AbstractContainerMenu opened = context.localPlayer().containerMenu;
            if (opened != null && menus.isBackpackMenu(opened)) {
                MenuChannel channel = ClientMenuChannel.claimCurrent(services.context());
                if (channel == null) {
                    return nextBackpackOr(Problem.of(Problem.Kind.TARGET_GONE, "背包界面点开了又马上没了", null));
                }
                Optional<List<Integer>> slots = menus.mainStorageSlotIds(opened);
                if (slots.isEmpty()) {
                    // 带无限格升级的背包读数对不上：这只取不了货，按未证实处理，收场换下一只。
                    deadEnds.add(whichBackpack() + "读数对不上（多半带着无限格升级），取不了货");
                    return afterClosed();
                }
                menu = new ClaimedMenu(channel, slots.get());
                round = new MenuTakeRound(menu, List.of(new BackpackWant()), new BackpackEndings());
                return ActionStatus.progressed();
            }
            if (++waited > OPEN_WAIT_TICKS) {
                // 这只一直打不开：不卡在它身上，换下一只。
                return nextBackpackOr(Problem.of(Problem.Kind.STUCK,
                        "背包的打开请求发出去了，界面一直没开", null));
            }
            return ActionStatus.running();
        }

        /** 这只包没开成：还有没开的就换下一只，都开过了按这个问题收场。 */
        private ActionStatus nextBackpackOr(Problem problem) {
            deadEnds.add(whichBackpack() + "：" + problem.message());
            if (toOpen.isEmpty()) {
                return ActionStatus.failed(Problem.of(problem.kind(), String.join("；", deadEnds), null));
            }
            startNextBackpack();
            return ActionStatus.progressed();
        }

        /** 换下一只包：界面与这一只的进度清掉，从发打开请求开始。 */
        private void startNextBackpack() {
            menu = null;
            round = null;
            quietTicks = 0;
            waited = 0;
            openRequested = false;
            openingSlot = toOpen.poll();
        }

        /** 关上一只之后：够了收工；不够还有没开的就开下一只；都开过了交给引擎清点。 */
        private ActionStatus afterClosed() {
            if (enough() || toOpen.isEmpty()) {
                return ActionStatus.done();
            }
            startNextBackpack();
            return ActionStatus.progressed();
        }

        private String whichBackpack() {
            return openingSlot >= 0 ? "第 " + openingSlot + " 格的背包" : "身上的背包";
        }

        private boolean enough() {
            return carriedWanted() - carriedAtStart >= request.count();
        }

        /** 关上之前记下这次开包看到的主存储存货：下次问价不用再开一遍；读数没了就把这只的旧存货忘掉。 */
        private void recordStock() {
            Optional<MenuContent.Reading> reading = menu == null ? Optional.empty() : menu.reading();
            if (reading.isEmpty()) {
                seen.forget(openingSlot);
                return;
            }
            Map<String, Integer> stock = new HashMap<>();
            for (SlotSnapshot snapshot : reading.get().containerSnapshots()) {
                if (snapshot.isEmpty()) continue;
                BackpackStack stack = ClientBackpackView.snapshot(snapshot.stack());
                stock.merge(stack.itemId(), snapshot.count(), Integer::sum);
            }
            seen.saw(openingSlot, stock, clock.get());
        }

        // 这一格是不是想要的东西：按物品 ID 与它挂着的标签匹配。
        private boolean matches(SlotSnapshot snapshot) {
            if (snapshot.isEmpty()) return false;
            BackpackStack stack = ClientBackpackView.snapshot(snapshot.stack());
            return request.wanted().matches(stack.itemId(), services.itemTags().tagsOf(stack.itemId()));
        }

        /** 身上（这层只看主背包）有多少件想要的：取没取到以它的增长为准。 */
        private int carriedWanted() {
            int total = 0;
            for (BackpackStack stack : services.backpack().stacks()) {
                if (request.wanted().matches(stack.itemId(), services.itemTags().tagsOf(stack.itemId()))) {
                    total += stack.count();
                }
            }
            return total;
        }

        // 生存需求打断时没有界面操作要收：界面被请关后恢复经 begin 重新走。
        @Override public void pause() {
        }

        @Override public void close() {
            if (round != null) round.close(false);
            if (menu != null && menu.stillOpen()) menu.abandon();
            menu = null;
        }

        @Override public Interruptibility interruptibility() {
            return menu != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public List<String> unconfirmedFacts() {
            return round == null ? List.of() : round.unconfirmedFacts();
        }

        @Override public String describe() {
            if (menu == null) return "打开随身背包";
            return round.atClosing() ? "关上随身背包"
                    : "从随身背包搬" + request.wanted().describe() + "进主背包";
        }

        /** 一轮取货的收场：关箱前记下这次看到的主存储存货；关上后够了或没有下一只就收工。 */
        private final class BackpackEndings implements MenuTakeRound.Endings {

            @Override
            public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
                recordStock();
            }

            @Override
            public ActionStatus finishAfterClosed(boolean tookAnything) {
                return afterClosed();
            }

            // 界面中途没了：搬走多少由引擎清点，这只照常算完，不够再开下一只。
            @Override
            public ActionStatus finishAfterMenuGone(boolean tookAnything) {
                return afterClosed();
            }

            // 关不上不冒充关上了：光标上的东西交给游戏的关闭流程还回。
            @Override
            public Optional<String> closeUnconfirmedNote() {
                return Optional.of("关随身背包的界面没能确认，光标上的东西交给游戏的关闭流程还回");
            }
        }

        /** 要货单里的一样（随身背包一次只要一样）：按包里的东西认，缺几件按身上真多了多少算。 */
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

            // 对不上账的一笔说不清搬走多少：这一只收场，已取的照算，交给引擎清点后再说。
            @Override
            public boolean settleAfterDiverge() {
                return true;
            }
        }
    }

    /**
     * 认领到的一份背包界面：读数按模组的主存储分侧（升级槽与幽灵槽不算货），点击与关闭走通道与会话。
     * 打开请求是模组自己的（对着包按 B 键），认领发生在界面真开之后；关闭与会话纪律走共用的那一份。
     */
    private final class ClaimedMenu implements OpenedMenu {

        private final MenuChannel channel;
        /** 主存储的槽位号：升级槽与幽灵槽不在里面，天然不算货。 */
        private final List<Integer> storage;
        private MenuSession session;
        private boolean closeAsked;

        ClaimedMenu(MenuChannel channel, List<Integer> storage) {
            this.channel = channel;
            this.storage = List.copyOf(storage);
        }

        boolean stillOpen() {
            return channel.stillOpen();
        }

        @Override public Optional<MenuContent.Reading> reading() {
            PlayerContext context = services.context().get();
            if (context == null || context.localPlayer() == null || !channel.stillOpen()) {
                return Optional.empty();
            }
            AbstractContainerMenu opened = context.localPlayer().containerMenu;
            if (opened == null || !menus.isBackpackMenu(opened)) return Optional.empty();
            List<Integer> playerSlots = menus.playerSlotIds(opened);
            List<SlotSnapshot> storageSnapshots = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int slot : storage) storageSnapshots.add(snapshot(opened, slot));
            for (int slot : playerSlots) playerSnapshots.add(snapshot(opened, slot));
            // 背包格号取槽位背后物品格自己的格号：快捷栏 0-8、主背包 9-35，不依赖界面把哪些槽排在前。
            List<Integer> playerIndices = new ArrayList<>();
            for (int slot : playerSlots) playerIndices.add(opened.getSlot(slot).getContainerSlot());
            MenuSlots slots = new MenuSlots() {
                @Override public String menuTypeId() { return "sophisticatedbackpacks:backpack"; }
                @Override public int slotCount() { return opened.slots.size(); }
                @Override public boolean playerBacked(int slot) {
                    return slot >= 0 && slot < opened.slots.size()
                            && opened.slots.get(slot).container instanceof Inventory;
                }
                @Override public int playerInventoryIndex(int slot) {
                    return slot >= 0 && slot < opened.slots.size()
                            ? opened.getSlot(slot).getContainerSlot() : -1;
                }
            };
            return Optional.of(new MenuContent.Reading(channel, slots, List.copyOf(storage), playerSlots,
                    List.copyOf(playerIndices), List.copyOf(storageSnapshots), List.copyOf(playerSnapshots)));
        }

        @Override public boolean busy() {
            PlayerContext context = services.context().get();
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

        @Override public void noteCursorTakenFrom(int slotId) {
            // 光标上的东西从这一格拿起：会话记下，关界面前放回这里。会话要求光标为空才认领，
            // 第一次登记都发生在拿起之前；认领不上就交由原版的关闭流程还。
            if (session == null && MenuSession.claim(channel) instanceof MenuSession.Claim.Owned owned) {
                session = owned.session();
            }
            if (session != null) session.noteCursorTakenFrom(slotId);
        }

        @Override public Action closing() {
            return new MenuClose(channel, new MenuSessionSource() {
                @Override public Optional<MenuSession> current() {
                    return Optional.ofNullable(session);
                }

                @Override public void renew() {
                    session = null;
                }
            });
        }

        @Override public void abandon() {
            closeAsked = true;
            if (channel.stillOpen()) channel.requestClose();
        }

        private static SlotSnapshot snapshot(AbstractContainerMenu opened, int slot) {
            if (slot < 0 || slot >= opened.slots.size()) return SlotSnapshot.empty();
            var stack = opened.slots.get(slot).getItem();
            return stack.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(stack);
        }
    }

    /** 多久以前：给 LLM 看的粗略说法。 */
    private static String ago(Instant seenAt, Instant now) {
        long seconds = Math.max(0, Duration.between(seenAt, now).getSeconds());
        return seconds < 60 ? " " + seconds + " 秒前" : " " + seconds / 60 + " 分钟前";
    }
}
