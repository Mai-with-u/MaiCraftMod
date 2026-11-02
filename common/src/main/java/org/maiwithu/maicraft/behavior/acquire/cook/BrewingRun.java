// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuClicks;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuReopenCount;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在酿造台上照一整条酿药水：走到跟前点开 → 燃料条见底放一个烈焰粉 → 这一组的瓶子一瓶一瓶放进瓶位 →
 * 放一件这一步的材料，看见开始冒泡就关上界面 → 关着界面等这一炉 → 等够了回去点开，酿完了放下一步的材料 →
 * 一组最后一步酿完把瓶子收回背包，还有下一组接着放瓶子 → 都酿完关上，这台记成用过的。
 *
 * <p>关着界面等的这二十秒是空当，和关着界面等熔炉一样，不急的生存需求可以插进来；后面几炉还缺的料
 * （下一步的材料、下一组的水瓶、燃料不够剩下几炉时的烈焰粉）趁这会儿经拿东西的引擎去弄，
 * 两炉之间不专门停下来跑去备料；没有要弄的就转过去看着酿造台冒泡。眼前一炉缺的（瓶子、材料、燃料）
 * 关上界面去弄来再回来。被打断回来重新点开：瓶位里是哪种就接着酿哪一步，在冒泡就接着等。
 *
 * <p>照原版界面的规矩：0–2 瓶位一格一瓶，3 材料格，4 燃料格只收烈焰粉；燃料、材料都只放一件，不整堆塞进去。
 * 头一回点开时瓶位或材料格里放着别人的东西就不动它，关上换下一台；放好了五秒还没开始冒泡，
 * 说明游戏不认这一组，瓶子和材料收回背包再如实说放了什么。
 */
final class BrewingRun implements Action {

    /** 酿造台界面容器侧的格子：0–2 瓶位、3 材料格、4 燃料格。 */
    private static final int BOTTLE_SLOTS = 3;
    private static final int INGREDIENT = 3;
    private static final int FUEL = 4;
    private static final String BLAZE_POWDER = BrewPlan.BLAZE_POWDER;
    static final String BREWING_STAND = "minecraft:brewing_stand";
    /** 材料放好以后等冒泡的期限（刻）：游戏下一刻就开始酿，五秒还没动就是不认这一组。 */
    static final int START_WAIT_TICKS = 100;
    /** 估摸酿完的时刻再多等一会儿才回去看（刻）。 */
    private static final int CHECK_MARGIN_TICKS = 10;
    /** 关着界面看着酿造台时隔多久报一次在等（刻）：守着冒泡的酿造台不算卡住。 */
    private static final int WATCH_REPORT_TICKS = 200;

    /** 推进的先后：走到跟前点开、照看（放料收瓶）、关界面、关着界面等。 */
    private enum Stage { VISIT, WORK, CLOSE, AWAY }

    /** 关上界面以后做什么：去等（顺手备料）、换下一台、收工、按问题收场。 */
    private enum AfterClose { AWAY, NEXT_STAND, DONE, FAIL }

    /** 手上这笔搬运在做什么：往格子里放一件、收一瓶酿好的、把东西收回背包。 */
    private enum MoveKind { PUT_IN, TAKE_OUT, TAKE_BACK }

    private final BrewPlan plan;
    private final List<WorldPosition> stands;
    private final Function<WorldPosition, StationVisit> visits;
    private final ItemNeeds needs;
    private final ToIntFunction<String> carried;
    private final Consumer<WorldPosition> occupied;
    private final Permissions permissions;
    private final WorldMemory memory;
    /** 发起任务的记账口：每收一瓶记一笔；null 不记。 */
    private final TaskRecords records;
    private final List<Integer> groups;
    private final MenuReopenCount reopens = new MenuReopenCount();
    /** 等的时候去弄过、没弄来的：这一趟不再为它跑，到了要用时作为眼前缺的再弄一次。 */
    private final Set<String> notFetched = new HashSet<>();

    private int standIndex;
    private Stage stage = Stage.VISIT;
    private AfterClose afterClose = AfterClose.DONE;
    private StationVisit visit;
    private MenuContent menus;
    private TransferRun run;
    private MenuSession closingSession;
    private MoveKind moveKind;
    private String moveWhat = "";
    private boolean closedByPause;
    /** 这台点开看过、瓶位材料格里没有别人的东西：之后里面的都是自己放的。 */
    private boolean ours;
    /** 第几组（从 0 起）。 */
    private int group;
    /** 这一组酿到第几步（从 0 起）。 */
    private int step;
    /** 这一组酿完了，正往外收瓶子。 */
    private boolean takingOut;
    /** 关着界面等的时候酿造台在冒泡：等的是这一步酿完。 */
    private boolean brewing;
    private int startWaited;
    private long awayUntil;
    private long lastReport;
    /** 上回点开时燃料条还够几炉；没点开过为 -1。 */
    private int fuelLeft = -1;
    /** 眼前就缺、不弄来这一炉放不下去的那一样（起步的瓶子、这一步的材料、燃料）。 */
    private ItemRequest neededNow;
    private Action fetching;
    private boolean fetchingNeededNow;
    private String fetchingWhat = "";
    /** 放好了游戏不认这一组：先把瓶子和材料收回背包，再按它收场。 */
    private Problem refused;
    private Problem failure;

    BrewingRun(BrewPlan plan, List<WorldPosition> stands, Function<WorldPosition, StationVisit> visits, ItemNeeds needs,
            ToIntFunction<String> carried, Consumer<WorldPosition> occupied, Permissions permissions, WorldMemory memory,
            TaskRecords records) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.stands = List.copyOf(stands);
        if (this.stands.isEmpty()) throw new IllegalArgumentException("酿药水至少要有一台酿造台");
        this.visits = Objects.requireNonNull(visits, "visits");
        this.needs = Objects.requireNonNull(needs, "needs");
        this.carried = Objects.requireNonNull(carried, "carried");
        this.occupied = Objects.requireNonNull(occupied, "occupied");
        this.permissions = permissions;
        this.memory = Objects.requireNonNull(memory, "memory");
        this.records = records;
        this.groups = plan.groups();
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        if (failure != null) return ActionStatus.failed(failure);
        return switch (stage) {
            case VISIT -> visit(tick);
            case WORK -> work(tick);
            case CLOSE -> close(tick);
            case AWAY -> away(tick);
        };
    }

    private WorldPosition station() {
        return stands.get(standIndex);
    }

    private String target() {
        return plan.target().written();
    }

    // 走到跟前点开：每次重开都重新走一遍，人可能被挤开、撤离过、去备过料。
    private ActionStatus visit(TickContext tick) {
        if (visit == null) visit = visits.apply(station());
        ActionStatus status = visit.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            failure = failed.problem();
            return status;
        }
        if (status instanceof ActionStatus.Done) {
            menus = visit.menus();
            OpenedMenu claimed = ClientOpenedMenu.claimCurrent(visit.context(), visit.menus());
            run = claimed == null ? null : new TransferRun(claimed);
            if (run == null) return fail(Problem.Kind.STUCK, "酿造台的界面点开了却认领不到，酿不了" + target());
            closingSession = null;
            startWaited = 0;
            stage = Stage.WORK;
            return ActionStatus.progressed();
        }
        return status;
    }

    // 照看一炉：手上有一笔搬运先推进它；在冒泡就关上去等；酿完一组收瓶子；不然按瓶位里是哪种接着放燃料、瓶子、材料。
    private ActionStatus work(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isEmpty()) return reopen();
        if (!run.idle()) return run.tickAction(this::onMoveSettled);
        MenuContent.Reading open = reading.get();
        List<SlotSnapshot> slots = open.containerSnapshots();
        if (slots.size() <= FUEL || !(tick.player().localPlayer().containerMenu instanceof BrewingStandMenu stand)) {
            return fail(Problem.Kind.UNSUPPORTED, "打开的界面不是酿造台的，酿不了" + target());
        }
        // 头一回点开这台：瓶位、材料格里放着别人的东西就不动它，关上换一台；燃料格里的烈焰粉不算。
        if (!ours) {
            if (!bottlesIn(slots).isEmpty() || !slots.get(INGREDIENT).isEmpty()) return occupiedHere();
            ours = true;
        }
        fuelLeft = stand.getFuel();
        if (refused != null) return takeBackOrFail(open);
        if (stand.getBrewingTicks() > 0) return leaveToWait(tick, stand.getBrewingTicks());
        List<StackKey> bottles = bottlesIn(slots);
        if (takingOut) {
            if (!bottles.isEmpty()) return takeOut(open);
            // 这一组都收回背包了：下一组从头放瓶子；都酿完就收工。
            takingOut = false;
            group++;
            step = 0;
            if (group >= groups.size()) return finish();
        }
        int groupSize = groups.get(group);
        boolean loading = bottles.isEmpty() || bottles.size() < groupSize && bottles.stream().allMatch(plan.start()::equals);
        if (loading) {
            Optional<Integer> from = carriedSlot(open, plan.start().written());
            if (from.isPresent()) {
                return putIn(open, from.get(), firstEmptyBottle(slots), "一瓶 " + plan.start().written() + " 放进瓶位");
            }
            return needNow(plan.start().written(), groupSize - bottles.size(), "酿药水起步的瓶子");
        }
        StackKey now = bottles.getFirst();
        if (!bottles.stream().allMatch(now::equals)) {
            return fail(Problem.Kind.STUCK, "酿造台瓶位里的药水不是同一种了（" + bottles + "），接不下去");
        }
        if (now.equals(plan.target())) {
            takingOut = true;
            return takeOut(open);
        }
        int at = stepFrom(now);
        if (at < 0) {
            return fail(Problem.Kind.STUCK, "酿造台瓶位里是 " + now.written() + "，不在酿" + target() + "的这一整条里");
        }
        step = at;
        BrewStep current = plan.steps().get(at);
        SlotSnapshot ingredient = slots.get(INGREDIENT);
        // 材料格里放着别的（上回没酿成剩下的）：先收回背包，再放这一步的。
        if (!ingredient.isEmpty() && !StackKey.parse(ingredient.key()).itemId().equals(current.ingredient())) {
            return takeBack(open, INGREDIENT);
        }
        // 燃料条见底、燃料格也空着：放一个烈焰粉；燃料格里还有的游戏会自己烧进燃料条。
        if (stand.getFuel() <= 0 && slots.get(FUEL).isEmpty()) {
            Optional<Integer> powder = carriedSlot(open, BLAZE_POWDER);
            if (powder.isEmpty()) return needNow(BLAZE_POWDER, 1, "酿造台的燃料");
            return putIn(open, powder.get(), FUEL, "一个烈焰粉放进燃料格");
        }
        if (ingredient.isEmpty()) {
            startWaited = 0;
            Optional<Integer> from = carriedSlot(open, current.ingredient());
            if (from.isEmpty()) return needNow(current.ingredient(), 1, "酿 " + current.to().written() + " 的材料");
            return putIn(open, from.get(), INGREDIENT, "一件 " + current.ingredient() + " 放进材料格");
        }
        // 材料放好了还没冒泡（燃料格的烈焰粉要下一刻才烧进燃料条）：等一会儿；五秒不动就是游戏不认这一组。
        if (++startWaited > START_WAIT_TICKS) {
            refused = Problem.of(Problem.Kind.REFUSED_BY_GAME, "酿造台瓶位里放了 " + bottles.size() + " 瓶 "
                    + now.written() + "、材料格放了 " + current.ingredient() + "，五秒都没开始冒泡，游戏不认这一组", null);
            return ActionStatus.progressed();
        }
        return ActionStatus.running();
    }

    // 往格子里放恰好一件：瓶子一格一瓶，燃料、材料也只放一件，不整堆塞进去；核对"背包那格少一件、这一格多一件"。
    private ActionStatus putIn(MenuContent.Reading open, int playerIndex, int containerIndex, String what) {
        SlotSnapshot shown = open.playerSnapshots().get(playerIndex);
        int from = open.playerSlotIds().get(playerIndex);
        int to = open.containerSlotIds().get(containerIndex);
        moveWhat = what;
        run.begin(new TransferRun.Move(MenuClicks.exact(from, to, shown, 1), new TransferRun.Check.Normal(from, to, 1), what));
        moveKind = MoveKind.PUT_IN;
        return ActionStatus.progressed();
    }

    // 收一瓶酿好的：shift 点回背包，按背包里这种药水真多出来的记。
    private ActionStatus takeOut(MenuContent.Reading open) {
        int index = firstFullBottle(open.containerSnapshots());
        String key = open.containerSnapshots().get(index).key();
        moveWhat = "收一瓶 " + key;
        run.begin(new TransferRun.Move(List.of(new MenuClick(open.containerSlotIds().get(index), true, 0, false)),
                new TransferRun.Check.IntoBackpack(snapshot -> !snapshot.isEmpty() && snapshot.key().equals(key), 1),
                moveWhat));
        moveKind = MoveKind.TAKE_OUT;
        return ActionStatus.progressed();
    }

    // 把格子里的东西整堆收回背包：材料格里剩的别的材料、游戏不认那一组时放进去的瓶子和材料。
    private ActionStatus takeBack(MenuContent.Reading open, int index) {
        SlotSnapshot shown = open.containerSnapshots().get(index);
        String key = shown.key();
        moveWhat = "把 " + key + " 收回背包";
        run.begin(new TransferRun.Move(List.of(new MenuClick(open.containerSlotIds().get(index), true, 0, false)),
                new TransferRun.Check.IntoBackpack(snapshot -> !snapshot.isEmpty() && snapshot.key().equals(key),
                        shown.count()), moveWhat));
        moveKind = MoveKind.TAKE_BACK;
        return ActionStatus.progressed();
    }

    // 游戏不认这一组：瓶位、材料格里还有的一格一格收回背包，收完关上界面按它收场。
    private ActionStatus takeBackOrFail(MenuContent.Reading open) {
        List<SlotSnapshot> slots = open.containerSnapshots();
        for (int index = 0; index <= INGREDIENT; index++) {
            if (!slots.get(index).isEmpty()) return takeBack(open, index);
        }
        afterClose = AfterClose.FAIL;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 一笔搬运出了结论：放没放进去、收没收回背包都如实算；界面中途没了下一刻重开接着做。
    private ActionStatus onMoveSettled(TransferRun.MoveResult result) {
        if (result instanceof TransferRun.MoveResult.MenuGone) return ActionStatus.progressed();
        boolean moved = result instanceof TransferRun.MoveResult.Confirmed(int amount) && amount > 0;
        return switch (moveKind) {
            case PUT_IN -> moved ? ActionStatus.progressed()
                    : fail(Problem.Kind.REFUSED_BY_GAME, moveWhat + "没能放进去（游戏不收它，或放进了别的格）");
            case TAKE_OUT -> {
                if (!moved) yield fail(Problem.Kind.INVENTORY_FULL, "酿好的 " + target() + " 收不进背包，背包可能满了");
                if (records != null) records.change(Change.of(Change.Kind.ITEM_GAINED, target(), 1));
                yield ActionStatus.progressed();
            }
            case TAKE_BACK -> moved ? ActionStatus.progressed()
                    : fail(Problem.Kind.INVENTORY_FULL, moveWhat + "没收回来，背包可能满了");
        };
    }

    // 在冒泡：关上界面去等，估摸这一炉酿完的时刻再回来。
    private ActionStatus leaveToWait(TickContext tick, int brewingTicks) {
        brewing = true;
        awayUntil = tick.player().clientTick() + brewingTicks + CHECK_MARGIN_TICKS;
        afterClose = AfterClose.AWAY;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 眼前这一炉缺东西：关上界面经引擎弄来再回来，不开着界面干等。
    private ActionStatus needNow(String key, int count, String purpose) {
        neededNow = new ItemRequest(WantedItem.ofItem(key), Math.max(1, count), purpose);
        brewing = false;
        afterClose = AfterClose.AWAY;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 瓶位、材料格里放着别人的东西：不动它，告诉来源一声，关上换下一台。
    private ActionStatus occupiedHere() {
        occupied.accept(station());
        afterClose = AfterClose.NEXT_STAND;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 都酿完了：这台记成用过的，关上收工。
    private ActionStatus finish() {
        memory.rememberWorkstationUsed(station(), BREWING_STAND, Instant.now());
        afterClose = AfterClose.DONE;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    /**
     * 关着界面等：眼前缺的先去弄；不缺就把后面几炉还缺的一样一样去弄；都不缺就转过去看着酿造台冒泡，
     * 估摸酿完了回去点开。这一段是空当，打断它不会弄乱酿造台里的东西。
     */
    private ActionStatus away(TickContext tick) {
        if (fetching != null) return fetch(tick);
        if (neededNow != null) {
            startFetch(neededNow, true);
            return ActionStatus.progressed();
        }
        Optional<ItemRequest> later = laterNeed();
        if (later.isPresent()) {
            startFetch(later.get(), false);
            return ActionStatus.progressed();
        }
        long now = tick.player().clientTick();
        if (!brewing || now >= awayUntil) {
            brewing = false;
            stage = Stage.VISIT;
            return ActionStatus.progressed();
        }
        lookAtStand(tick.player());
        if (now - lastReport >= WATCH_REPORT_TICKS) {
            lastReport = now;
            return ActionStatus.progressed();
        }
        return ActionStatus.running();
    }

    private void startFetch(ItemRequest request, boolean now) {
        fetching = needs.actionFor(request, permissions);
        fetchingNeededNow = now;
        fetchingWhat = request.wanted().specifier();
    }

    // 推进去弄东西的那一串：眼前缺的弄不来就收场；后面几炉的没弄来先接着酿眼前这炉，到了要用时再弄一次。
    private ActionStatus fetch(TickContext tick) {
        ActionStatus status = fetching.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        fetching.close();
        fetching = null;
        if (status instanceof ActionStatus.Failed failed) {
            if (fetchingNeededNow) {
                return fail(failed.problem().kind(), "酿" + target() + "缺的 " + fetchingWhat + " 弄不来："
                        + failed.problem().message());
            }
            notFetched.add(fetchingWhat);
        }
        if (fetchingNeededNow) neededNow = null;
        return ActionStatus.progressed();
    }

    /**
     * 后面几炉要的、身上还缺的第一样：这一组正冒泡那一步之后每步的材料、后面几组的瓶子和材料，
     * 燃料条不够剩下的炉数时再一个烈焰粉。没弄来过的这一趟不再算。
     */
    private Optional<ItemRequest> laterNeed() {
        Map<String, Integer> want = plan.stillToUse(group, brewing ? step + 1 : step, fuelLeft);
        for (Map.Entry<String, Integer> entry : want.entrySet()) {
            if (notFetched.contains(entry.getKey())) continue;
            int missing = entry.getValue() - carried.applyAsInt(entry.getKey());
            if (missing > 0) {
                return Optional.of(new ItemRequest(WantedItem.ofItem(entry.getKey()), missing, "后面几炉酿药水要的"));
            }
        }
        return Optional.empty();
    }

    // 没有要弄的：转过去看着酿造台冒泡，不对着空地发呆。
    private void lookAtStand(PlayerContext player) {
        if (player == null || player.localPlayer() == null) return;
        WorldPosition at = station();
        player.input().lookAt(player.localPlayer(), Vec3.atCenterOf(new BlockPos(at.x(), at.y(), at.z())));
    }

    // 瓶位里是这种药水时该酿哪一步：一整条里从它起的那一步；不在这一整条里给 -1。
    private int stepFrom(StackKey now) {
        List<BrewStep> steps = plan.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).from().equals(now)) return i;
        }
        return -1;
    }

    private static List<StackKey> bottlesIn(List<SlotSnapshot> slots) {
        List<StackKey> bottles = new ArrayList<>();
        for (int index = 0; index < BOTTLE_SLOTS; index++) {
            if (!slots.get(index).isEmpty()) bottles.add(StackKey.parse(slots.get(index).key()));
        }
        return bottles;
    }

    private static int firstEmptyBottle(List<SlotSnapshot> slots) {
        for (int index = 0; index < BOTTLE_SLOTS; index++) {
            if (slots.get(index).isEmpty()) return index;
        }
        return 0;
    }

    private static int firstFullBottle(List<SlotSnapshot> slots) {
        for (int index = 0; index < BOTTLE_SLOTS; index++) {
            if (!slots.get(index).isEmpty()) return index;
        }
        return 0;
    }

    // 背包那一侧第一格对得上的：药水按写着的种类认，材料、烈焰粉按物品 ID 认。
    private static Optional<Integer> carriedSlot(MenuContent.Reading open, String key) {
        WantedItem wanted = WantedItem.ofItem(key);
        List<SlotSnapshot> player = open.playerSnapshots();
        for (int index = 0; index < player.size(); index++) {
            SlotSnapshot shown = player.get(index);
            if (!shown.isEmpty() && wanted.matches(shown.key(), Set.of())) return Optional.of(index);
        }
        return Optional.empty();
    }

    // 界面没了：被打断时关的、或别的原因关的，都回到跟前重新点开接着酿；不是打断关的、一再被关掉才如实失败。
    private ActionStatus reopen() {
        if (!reopens.mayReopen(closedByPause)) {
            return fail(Problem.Kind.STUCK, "酿造台的界面一再被关掉，" + target() + "酿不下去");
        }
        closedByPause = false;
        dropVisit();
        stage = Stage.VISIT;
        return ActionStatus.progressed();
    }

    private void dropVisit() {
        if (visit != null) visit.close();
        visit = null;
        menus = null;
        run = null;
        closingSession = null;
    }

    // 还在的界面才读：点开的那份已经关了（就算又开了别的界面）都按界面没了算。
    private Optional<MenuContent.Reading> openReading() {
        return menus == null ? Optional.empty() : menus.current().filter(reading -> reading.channel().stillOpen());
    }

    // 关界面：一份界面会话从头用到尾，只请求游戏关一次；关上了（或关不上、本来就没了）按关上以后要做的接着走。
    private ActionStatus close(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isPresent()) {
            if (closingSession == null) {
                MenuSession.Claim claim = MenuSession.claim(reading.get().channel());
                if (claim instanceof MenuSession.Claim.Owned owned) closingSession = owned.session();
            }
            if (closingSession != null) {
                var closing = closingSession.closeNow(reading.get().channel(), tick.player().clientTick());
                if (!(closing instanceof MenuSession.Closing.Closed || closing instanceof MenuSession.Closing.Failed)) {
                    return ActionStatus.running();
                }
            }
        }
        dropVisit();
        return switch (afterClose) {
            case AWAY -> {
                stage = Stage.AWAY;
                yield ActionStatus.progressed();
            }
            case NEXT_STAND -> {
                standIndex++;
                ours = false;
                if (standIndex >= stands.size()) {
                    yield fail(Problem.Kind.STUCK, "记得的酿造台瓶位、材料格里都放着别人的东西，没动");
                }
                stage = Stage.VISIT;
                yield ActionStatus.progressed();
            }
            case DONE -> ActionStatus.done();
            case FAIL -> {
                failure = refused;
                yield ActionStatus.failed(refused);
            }
        };
    }

    private ActionStatus fail(Problem.Kind kind, String message) {
        failure = Problem.of(kind, message, null);
        return ActionStatus.failed(failure);
    }

    // 被生存需求打断：关着界面等的时候只停下正在弄的那一串；走到一半的停住；界面开着就关上，回来重新点开接着酿。
    @Override
    public void pause() {
        if (stage == Stage.AWAY) {
            if (fetching != null) fetching.pause();
            return;
        }
        boolean menuOpen = openReading().isPresent();
        if (visit != null) visit.pause();
        abandonMenu();
        if (menuOpen) closedByPause = true;
        if (stage == Stage.VISIT) dropVisit();
    }

    @Override
    public void close() {
        if (fetching != null) fetching.close();
        if (visit != null) visit.close();
        abandonMenu();
    }

    private void abandonMenu() {
        MenuContent open = menus != null ? menus : visit != null ? visit.menus() : null;
        if (open != null) {
            open.current().filter(reading -> reading.channel().stillOpen())
                    .ifPresent(reading -> reading.channel().requestClose());
        }
    }

    // 关着界面等是空当，不急的生存需求可以插进来；这一下点出去还没等到游戏确认时不宜打断。
    @Override
    public Interruptibility interruptibility() {
        if (stage == Stage.AWAY) {
            return fetching != null ? fetching.interruptibility() : Interruptibility.BETWEEN_ACTIONS;
        }
        return run != null && !run.idle() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        if (stage == Stage.AWAY && fetching != null) {
            return (brewing ? "趁酿造台在冒泡去弄 " : "去弄酿药水缺的 ") + fetchingWhat + "：" + fetching.describe();
        }
        if (stage == Stage.AWAY) return "关着界面等酿造台酿 " + target();
        return "在酿造台上酿 " + target() + "（第 " + (group + 1) + "/" + groups.size() + " 组，第 "
                + (step + 1) + "/" + plan.steps().size() + " 步）";
    }
}
