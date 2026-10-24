// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 往 ME 网络里存东西的生产实现：挑一台终端（在身边时身上的无线终端优先，没带或点了地方就找装着的）→
 * 拿出无线终端右键打开，或走过去点在装着的终端面板上 → 核对是这台的界面、等连接状态同步过来
 * → 把要存的几样东西一样样存进网络（像玩家潜行点背包格）：整格快速移动，不足一整格的尾数
 * 拿上光标退回多余的件、凑出正好要存的几件再整份交回网络 → 都存完才关上，不每样开关一次。
 *
 * <p>每一下按背包加光标里那种东西的合计少了几件结算（凑尾数的几件先在光标上、交回网络时不再过背包，
 * 只看背包看不到已发生的存入），少了几件才记"存进了网络"几件；点了一会儿没少、光标上还挂着没交出去的，
 * 是网络放不下（存储满了、不收这种东西），没收下的照实点回背包，如实收场。没连上网络（超出访问点范围、没电）
 * 照抄界面上的原因收场并记下这台没连上，下次挑终端排后面；腾地方改走箱子或丢。认终端、开终端和取货是同一份（{@link NearbyTerminals}）。
 */
public final class MenuNetworkStores implements NetworkStorage {

    /** 打开以后存东西的期限（刻）：存几格、关上，半分钟足够；到点如实收场。走过去的路由靠近自己管。 */
    private static final long GIVE_UP_TICKS = 20L * 30;
    /** 打开后等连接状态同步过来的期限（刻）。 */
    private static final int LINK_WAIT_TICKS = 40;
    /** 快速移动发出去后等背包同步过来的宽限（刻）；过了还没少就是网络放不下。 */
    private static final int SETTLE_TICKS = 10;

    private final Ae2Compat compat;
    /** 认终端、到终端跟前：和取货共用一套认法。 */
    private final NearbyTerminals terminals;

    public MenuNetworkStores(Ae2Compat compat, PlayerServices services, SeenNetworkStock seen,
            Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.terminals = new NearbyTerminals(compat, services, seen, clock);
    }

    @Override public boolean reachable(TerminalSearch search) {
        return pick(search).access() != null;
    }

    @Override public TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search,
            TaskRecords records) {
        if (stacks.isEmpty()) return new TerminalPick.NotFound("没有要存的东西");
        Picked picked = pick(search);
        return picked.access() == null ? new TerminalPick.NotFound(picked.reason())
                : new TerminalPick.Chosen(new Storing(picked.access(), stacks, purpose, records));
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

    private enum Stage { OPEN, SYNC, STORE, CLOSE, DONE }

    /** 存几堆进网络：打开 → 等连上 → 一样样一格格快速移动 → 关上，跨刻推进。 */
    private final class Storing implements Action {

        /** 到网络跟前的办法；describe 给结果与日志看是哪一台。 */
        private final TerminalAccess access;
        private final String terminal;
        private final List<BackpackStack> stacks;
        /** 正在存第几样。 */
        private int current;
        /** 这一样确认存进的件数。 */
        private int storedThis;
        private BackpackStack stack;
        private final String purpose;
        private final TaskRecords records;
        private final MenuOpening opening;
        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private long waited;
        private int linkWaited;
        /** 确认存进网络的件数。 */
        private int stored;
        /** 正在等结算的那一下：发之前背包加光标里这种东西的合计件数（没有在等的为 -1），与等了几刻。 */
        private int before = -1;
        private int quiet;
        /** 正在凑光标上那一小份：还没点完的几下（先拿起整份，再退回多余的件）；点完这一趟才整份交给网络。 */
        private final Deque<TrimClick> trimming = new ArrayDeque<>();
        /** 凑尾数拿起的那一格：网络没收下的部分照这一格点回背包。 */
        private int trimSourceSlot = -1;
        /** 正在把光标上没存出去的几件点回背包：点完（光标空了）才按这一趟的实情收场。 */
        private boolean returning;
        /** 光标上的部分没被网络收下时收场的原因。 */
        private Problem heldProblem;
        /** 收场的原因：一件都没存进时按它失败；存进了就算做完。 */
        private Problem stopReason;
        /** 最近一样存不进的原因：样样都存不进时按它收场。 */
        private Problem lastProblem;

        /** 凑尾数的一下普通点击：在哪一格、左键还是右键；takesToCursor 表示这一下把东西拿上了光标。 */
        private record TrimClick(int slotId, int button, boolean takesToCursor) {}

        Storing(TerminalAccess access, List<BackpackStack> stacks, String purpose, TaskRecords records) {
            this.access = access;
            this.terminal = access.describe();
            this.stacks = List.copyOf(stacks);
            this.stack = this.stacks.getFirst();
            this.purpose = purpose;
            this.records = records;
            this.opening = access.opening();
        }

        @Override public ActionStatus tick(TickContext tick) {
            // 走过去、点开由打开动作自己管（走不到它会如实失败）；点开以后才开始算存东西的期限。
            if (stage != Stage.OPEN && ++waited > GIVE_UP_TICKS && stage.ordinal() < Stage.CLOSE.ordinal()) {
                stop(Problem.of(Problem.Kind.STUCK, "往 " + terminal + " 连着的网络存东西超时", null));
            }
            return switch (stage) {
                case OPEN -> open(tick);
                case SYNC -> sync(tick);
                case STORE -> storeNext(tick);
                case CLOSE -> closeMenu(tick);
                case DONE -> result();
            };
        }

        // 拿出终端右键打开：打不开把原因原样交回，腾地方改走别的路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        // 核对是无线终端的界面，等连接状态同步过来；没连上就照抄界面上的原因收场。
        private ActionStatus sync(TickContext tick) {
            AbstractContainerMenu raw = tick.player().localPlayer().containerMenu;
            if (menu.reading().isEmpty()) {
                return finish(Problem.of(Problem.Kind.TARGET_GONE, "终端界面刚打开就被关上了", null));
            }
            if (!access.belongs(raw, tick.player().level())) {
                return stopNow(Problem.of(Problem.Kind.TARGET_GONE, "点开的不是 " + terminal + " 的界面", null));
            }
            Optional<Boolean> linked = compat.networkLinked(raw);
            if (linked.isEmpty()) {
                if (++linkWaited > LINK_WAIT_TICKS) {
                    return stopNow(Problem.of(Problem.Kind.STUCK, "终端界面开了，网络连接状态一直没同步过来", null));
                }
                return ActionStatus.running();
            }
            if (!linked.get()) {
                // 记下这台没连上：下次存东西挑终端排后面，取货报价也知道。连上时不记，存货没等全，记了会把没到的当成没有。
                access.remember(false, List.of(), true);
                String why = compat.unlinkedWhy(raw).map(text -> "（" + text + "）").orElse("（没电或没频道）");
                return stopNow(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, terminal + " 没连上 ME 网络" + why + "，存不了", null));
            }
            stage = Stage.STORE;
            return ActionStatus.progressed();
        }

        // 上一下少了几件就记账；没在等的话按还要存的量挑下一格：整格用得上就快速移动，
        // 不足一整格的尾数先凑上光标再整份交回网络——快速移动一下就是一整格，不截量会把 count=8 存成 64。
        // 结算的基准是动手前背包加光标的合计：凑尾数的全程东西在光标与背包之间挪，合计不变，
        // 交回网络之后合计少几件就是网络收下几件；交回是光标对网络，只看背包会看不到已发生的存入。
        private ActionStatus storeNext(TickContext tick) {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) return finish(null);
            if (menu.busy()) return ActionStatus.running();
            MenuContent.Reading snapshot = reading.get();
            int backpack = total(snapshot.playerSnapshots());
            int onCursor = cursorCount(snapshot);
            int onHand = backpack + onCursor;
            // 正在凑光标上那一小份：一下一下点，退回多余的件；点完这趟光标上正好是要存的几件，整份交给网络。
            if (!trimming.isEmpty()) {
                TrimClick click = trimming.peek();
                if (click.takesToCursor()) menu.noteCursorTakenFrom(click.slotId());
                if (!menu.click(click.slotId(), click.button())) return ActionStatus.running();
                trimming.poll();
                if (trimming.isEmpty()) compat.putBack(tick.player().localPlayer().containerMenu);
                return ActionStatus.progressed();
            }
            if (returning) return putLeftoversBack(onCursor);
            if (before >= 0) {
                ActionStatus settled = settleLastMove(onHand, onCursor);
                if (settled != null) return settled;
            }
            if (storedThis >= stack.count()) return nextKind(null);
            return switch (NetworkStorePlan.next(stack.count() - storedThis, slotCounts(snapshot))) {
                case NetworkStorePlan.Done done -> nextKind(storedThis > 0 ? null
                        : Problem.of(Problem.Kind.NEED_ITEM, "背包里已经没有 " + stack.itemId() + " 了", null));
                case NetworkStorePlan.QuickMove move -> {
                    // 本刻发不出去（上一下没结清、界面没画好、没有交互机会）就下一刻再点，不算点过。
                    if (!menu.quickMove(slotIdOf(snapshot, move.slotIndex()))) {
                        yield ActionStatus.running();
                    }
                    before = onHand;
                    quiet = 0;
                    yield ActionStatus.progressed();
                }
                case NetworkStorePlan.TrimToCursor trim -> {
                    // 拿起这一格整份（左键），再把多出的几件一件件右键退回源格；点完的下一刻交给网络。
                    // 拆量的几下要在交回网络前点完，不然快速移动会把整格多存进去。
                    // 基准在此刻记下：这几下只会在背包与光标之间挪，合计不变，交回后才见分晓。
                    int slotId = slotIdOf(snapshot, trim.slotIndex());
                    trimming.add(new TrimClick(slotId, 0, true));
                    for (int i = 0; i < trim.slotCount() - trim.keep(); i++) {
                        trimming.add(new TrimClick(slotId, 1, false));
                    }
                    before = onHand;
                    trimSourceSlot = slotId;
                    quiet = 0;
                    yield ActionStatus.progressed();
                }
            };
        }

        // 结算上一击：合计少了几件就是存进了网络几件，账与背包光标的变化一致；还没看出结果或到期限收场的，
        // 返回要推进的状态（返回 null 是结算完了，接着挑下一步）。
        private ActionStatus settleLastMove(int onHand, int onCursor) {
            return switch (NetworkStoreSettlement.settle(before, onHand, onCursor)) {
                case NetworkStoreSettlement.Stored storedMove -> {
                    credit(storedMove.moved());
                    before = -1;
                    yield null;
                }
                case NetworkStoreSettlement.Held held -> {
                    // 期限到了光标还拿着没收完的：已经进了网络的照实记，剩下的点回背包再按放不下收场。
                    if (++quiet < SETTLE_TICKS) yield ActionStatus.running();
                    if (held.moved() > 0) credit(held.moved());
                    before = -1;
                    heldProblem = held.moved() > 0
                            ? Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "网络只收下了 " + stack.itemId()
                                    + " " + held.moved() + " 件，剩下的放不下（存储满了或不收这种东西）", null)
                            : Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                                    "网络放不下 " + stack.itemId() + "（存储满了或不收这种东西）", null);
                    returning = true;
                    yield ActionStatus.progressed();
                }
                case NetworkStoreSettlement.Waiting waiting -> {
                    if (++quiet < SETTLE_TICKS) yield ActionStatus.running();
                    // 背包和光标都没少：网络放不下（存储满了、不收这种东西），如实记一笔，接着存下一样。
                    before = -1;
                    yield nextKind(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                            "网络放不下 " + stack.itemId() + "（存储满了或不收这种东西）", null));
                }
            };
        }

        // 把网络没收下的几件点回凑尾数的那一格：光标空了才按这一趟的实情收场，不让东西挂在光标上。
        private ActionStatus putLeftoversBack(int onCursor) {
            if (onCursor > 0) {
                return menu.click(trimSourceSlot, 0) ? ActionStatus.progressed() : ActionStatus.running();
            }
            returning = false;
            return nextKind(heldProblem);
        }

        // 按实况记账：合计少了几件就是存进了网络几件，账与背包的变化一致。
        private void credit(int moved) {
            stored += moved;
            storedThis += moved;
            records.change(new Change(Change.Kind.ITEM_STORED, stack.itemId(), moved,
                    purpose + "：存进了 " + terminal + " 连着的网络"));
        }

        // 这一样存完或存不进：存不进的记一笔试过；还有下一样就接着存，都过完了才关上。
        private ActionStatus nextKind(Problem problem) {
            if (problem != null) {
                lastProblem = problem;
                records.attempt("把 " + stack.itemId() + " 存进 " + terminal + " 连着的网络", problem.message());
            }
            if (++current >= stacks.size()) {
                return stopNow(stored > 0 ? null : lastProblem);
            }
            stack = stacks.get(current);
            storedThis = 0;
            quiet = 0;
            return ActionStatus.progressed();
        }

        // 关界面：打开者负责关闭；关不上照常收场。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return result();
        }

        // 收场：存进了就算做完；一件都没存进就按收场原因失败，腾地方改走别的路。
        private ActionStatus result() {
            if (stored > 0 || stopReason == null) return ActionStatus.done();
            return ActionStatus.failed(stopReason);
        }

        private ActionStatus stopNow(Problem reason) {
            stop(reason);
            return ActionStatus.progressed();
        }

        // 收场：还没打开界面就没有要关的，直接结束；开着就去关。
        private void stop(Problem reason) {
            if (stopReason == null) stopReason = reason;
            stage = menu == null ? Stage.DONE : Stage.CLOSE;
        }

        // 界面已经不在了：不用关，直接收场。
        private ActionStatus finish(Problem reason) {
            if (stopReason == null) stopReason = reason;
            stage = Stage.DONE;
            return result();
        }

        private int total(List<SlotSnapshot> player) {
            int sum = 0;
            for (SlotSnapshot slot : player) {
                if (!slot.isEmpty() && itemId(slot).equals(stack.itemId())) sum += slot.count();
            }
            return sum;
        }

        // 光标上拿着的这种东西有几件：交回网络是光标对网络，网络没收下的部分挂在光标上，结算要把它算作还没存出去的那份。
        private int cursorCount(MenuContent.Reading reading) {
            SlotSnapshot carried = reading.channel().cursor();
            return !carried.isEmpty() && itemId(carried).equals(stack.itemId()) ? carried.count() : 0;
        }

        // 背包里这种东西每一格的件数，按格子顺序；别的格不算。
        private List<Integer> slotCounts(MenuContent.Reading reading) {
            List<Integer> counts = new ArrayList<>();
            for (SlotSnapshot slot : reading.playerSnapshots()) {
                if (!slot.isEmpty() && itemId(slot).equals(stack.itemId())) counts.add(slot.count());
            }
            return counts;
        }

        // 这种东西的第 itemSlotIndex 格在界面里的槽位号：计划里的格序号是"这种东西的第几格"，要换算回界面槽位号。
        private int slotIdOf(MenuContent.Reading reading, int itemSlotIndex) {
            int seen = -1;
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot slot = reading.playerSnapshots().get(i);
                if (slot.isEmpty() || !itemId(slot).equals(stack.itemId())) continue;
                if (++seen == itemSlotIndex) return reading.playerSlotIds().get(i);
            }
            throw new IllegalStateException("要存的 " + stack.itemId() + " 第 "
                    + itemSlotIndex + " 格不在背包里，动手前的快照和现在的对不上");
        }

        private String itemId(SlotSnapshot slot) {
            return BuiltInRegistries.ITEM.getKey(slot.stack().getItem()).toString();
        }

        // 凑光标上那一小份、没收下的部分还没点回背包时停不得：东西在光标上，撒手会落进流程外。
        @Override public Interruptibility interruptibility() {
            return !trimming.isEmpty() || returning
                    ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，光标上的东西由原版的关闭流程还回背包。
        @Override public void close() {
            opening.close();
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
        }

        @Override public String describe() {
            return stacks.size() == 1
                    ? "把 " + stack.count() + " 个 " + stack.itemId() + " 存进 " + terminal + " 连着的网络"
                    : "把 " + stacks.size() + " 样东西存进 " + terminal + " 连着的网络（正在存 " + stack.itemId() + "）";
        }
    }
}
