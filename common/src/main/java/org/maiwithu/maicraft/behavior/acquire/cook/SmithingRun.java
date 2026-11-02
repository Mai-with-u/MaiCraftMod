// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.enchantment.Enchantment;

import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuReopenCount;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 在锻造台上升级一轮：走到跟前点开 → 模板、要升级的物品、升级材料依次 shift 点进三个格子 → 产出格出货就收进背包，
 * 还要做就接着放下一件 → 做够件数关上。照原版界面的规矩：0 模板、1 要升级的物品、2 升级材料、3 产出，
 * shift 点击由游戏按配方认该进哪一格，拿走产出三格各扣一件，关界面时格子里剩下的还回背包。
 *
 * <p>身上几件同样的东西都能升级时挑附魔多的、一样多挑磨损少的（见 {@link ItemToUpgrade}），附魔跟着过去。
 * 点进去的不是该进的那一格（模组配方让同一件东西认进了别的格）就如实失败，不硬拖；三格都放好了两秒还没出货，
 * 说明游戏不认这一组，如实说放了什么。界面中途被关掉（吃东西、自卫打断）就回到跟前重新点开接着放。
 */
final class SmithingRun implements Action {

    /** 锻造台界面容器侧的格子：模板、要升级的物品、升级材料、产出。 */
    private static final int TEMPLATE = 0;
    private static final int BASE = 1;
    private static final int ADDITION = 2;
    private static final int RESULT = 3;
    /** 三格都放好以后等产出格出货的期限（刻）：游戏当刻就算，两秒还没有就是不认这一组。 */
    static final int RESULT_WAIT_TICKS = 40;
    /** 一轮的总期限：走过去、点开、放料收货都算上，每多做一件多给一些。 */
    private static final int BASE_GIVE_UP_TICKS = 20 * 60;
    private static final int TICKS_PER_UPGRADE = 20 * 5;

    /** 推进的先后：走到跟前点开、放料收货、关上。 */
    private enum Stage { VISIT, WORK, CLOSE }

    /** 手上这笔搬运在做什么：放进三格之一，还是收产出。 */
    private enum MoveKind { PUT_IN, TAKE_OUTPUT }

    private final SmithingRecipe upgrade;
    private final WorkstationRecipe recipe;
    private final WorldPosition station;
    private final Function<WorldPosition, StationVisit> visits;
    private final WorldMemory memory;
    /** 发起任务的记账口：每收一次产出记一笔；null 不记。 */
    private final TaskRecords records;
    private final int targetItems;
    private final int giveUpTicks;
    private final MenuReopenCount reopens = new MenuReopenCount();

    private Stage stage = Stage.VISIT;
    private StationVisit visit;
    private MenuContent menus;
    private TransferRun run;
    private MenuSession closingSession;
    private MoveKind moveKind;
    private String moveWhat = "";
    private int waited;
    private int resultWaited;
    private boolean closedByPause;
    /** 背包里实际多出来的产出件数。 */
    private int produced;
    private Problem failure;

    SmithingRun(SmithingRecipe upgrade, WorkstationRecipe recipe, WorldPosition station, int times,
            Function<WorldPosition, StationVisit> visits, WorldMemory memory, TaskRecords records) {
        this.upgrade = Objects.requireNonNull(upgrade, "upgrade");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.station = Objects.requireNonNull(station, "station");
        this.visits = Objects.requireNonNull(visits, "visits");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.records = records;
        this.targetItems = times * recipe.resultCount();
        this.giveUpTicks = BASE_GIVE_UP_TICKS + times * TICKS_PER_UPGRADE;
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        if (failure != null) return ActionStatus.failed(failure);
        if (++waited > giveUpTicks) {
            return fail(Problem.Kind.STUCK, "在锻造台上升级" + recipe.result().describe() + "超时，先收手");
        }
        return switch (stage) {
            case VISIT -> visit(tick);
            case WORK -> work();
            case CLOSE -> close(tick);
        };
    }

    // 走到跟前点开：每次重开都重新走一遍，人可能被挤开、撤离过。
    private ActionStatus visit(TickContext tick) {
        if (visit == null) visit = visits.apply(station);
        ActionStatus status = visit.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            failure = failed.problem();
            return status;
        }
        if (status instanceof ActionStatus.Done) {
            menus = visit.menus();
            OpenedMenu claimed = ClientOpenedMenu.claimCurrent(visit.context(), visit.menus());
            run = claimed == null ? null : new TransferRun(claimed);
            if (run == null) return fail(Problem.Kind.STUCK, "锻造台的界面点开了却认领不到，升级不了");
            closingSession = null;
            resultWaited = 0;
            stage = Stage.WORK;
            return ActionStatus.progressed();
        }
        return status;
    }

    // 放料与收货：手上有一笔搬运先推进它；产出格有货先收；做够了关上；否则把空着的那一格补上。
    private ActionStatus work() {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isEmpty()) return reopen();
        if (!run.idle()) return run.tickAction(this::onMoveSettled);
        if (produced >= targetItems) {
            memory.rememberWorkstationUsed(station, recipe.kind().blockType(), Instant.now());
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }
        MenuContent.Reading open = reading.get();
        List<SlotSnapshot> container = open.containerSnapshots();
        if (container.size() <= RESULT) {
            return fail(Problem.Kind.UNSUPPORTED, "打开的锻造台界面格子对不上，升级不了" + recipe.result().describe());
        }
        if (!container.get(RESULT).isEmpty()) return takeOutput(open);
        if (container.get(TEMPLATE).isEmpty()) return putIn(open, TEMPLATE);
        if (container.get(BASE).isEmpty()) return putIn(open, BASE);
        if (container.get(ADDITION).isEmpty()) return putIn(open, ADDITION);
        // 三格都放好了：游戏当刻就把产出算出来，两秒还没有就是不认这一组，如实说放了什么。
        if (++resultWaited > RESULT_WAIT_TICKS) {
            return fail(Problem.Kind.REFUSED_BY_GAME, "锻造台里放了" + describe(container.get(TEMPLATE)) + "、"
                    + describe(container.get(BASE)) + "、" + describe(container.get(ADDITION)) + "，没有出"
                    + recipe.result().describe());
        }
        return ActionStatus.running();
    }

    // 把身上配方认的那一堆 shift 点进这一格：要升级的物品挑附魔多、磨损少的那件，模板和升级材料整堆点进去，
    // 多的关界面时还回背包。核对式按"背包那一格恰好少、这一格恰好多"，点进了别的格就如实失败。
    private ActionStatus putIn(MenuContent.Reading open, int slot) {
        resultWaited = 0;
        Optional<Integer> from = slot == BASE ? itemToUpgrade(open) : firstAccepted(open, acceptor(slot));
        if (from.isEmpty()) {
            return fail(Problem.Kind.NEED_ITEM, "身上没有锻造台" + slotName(slot) + "格要的东西，升级不出"
                    + recipe.result().describe());
        }
        SlotSnapshot shown = open.playerSnapshots().get(from.get());
        int playerSlotId = open.playerSlotIds().get(from.get());
        moveWhat = shown.stack().getHoverName().getString() + "放进锻造台的" + slotName(slot) + "格";
        run.begin(new TransferRun.Move(List.of(new MenuClick(playerSlotId, true, 0, false)),
                new TransferRun.Check.Normal(playerSlotId, open.containerSlotIds().get(slot), shown.count()),
                moveWhat));
        moveKind = MoveKind.PUT_IN;
        return ActionStatus.progressed();
    }

    // 收产出：整件 shift 收进背包，按背包里这件东西真多出来的记。
    private ActionStatus takeOutput(MenuContent.Reading open) {
        int slotId = open.containerSlotIds().get(RESULT);
        int amount = open.containerSnapshots().get(RESULT).count();
        moveWhat = "收" + recipe.result().describe();
        run.begin(new TransferRun.Move(List.of(new MenuClick(slotId, true, 0, false)),
                new TransferRun.Check.IntoBackpack(snapshot -> !snapshot.isEmpty()
                        && itemId(snapshot.stack()).equals(recipe.result().itemId()), amount),
                moveWhat));
        moveKind = MoveKind.TAKE_OUTPUT;
        return ActionStatus.progressed();
    }

    // 一笔搬运出了结论：放料没放进该进的那一格如实失败；收产出没动就是背包满了；界面中途没了下一刻重开接着做。
    private ActionStatus onMoveSettled(TransferRun.MoveResult result) {
        if (result instanceof TransferRun.MoveResult.MenuGone) return ActionStatus.progressed();
        if (moveKind == MoveKind.PUT_IN) {
            if (result instanceof TransferRun.MoveResult.Confirmed) return ActionStatus.progressed();
            return fail(Problem.Kind.REFUSED_BY_GAME, moveWhat + "没能放进去（游戏把它认进了别的格，或不收它）");
        }
        if (result instanceof TransferRun.MoveResult.Confirmed(int amount) && amount > 0) {
            produced += amount;
            if (records != null) records.change(Change.of(Change.Kind.ITEM_GAINED, recipe.result().itemId(), amount));
            return ActionStatus.progressed();
        }
        return fail(Problem.Kind.INVENTORY_FULL, "锻造台产出格的" + recipe.result().describe() + "收不进背包，背包可能满了");
    }

    // 要升级的那一件：背包里配方认的几件里，附魔多的先、一样多挑磨损少的。
    private Optional<Integer> itemToUpgrade(MenuContent.Reading open) {
        List<ItemToUpgrade.Candidate> candidates = new ArrayList<>();
        List<SlotSnapshot> player = open.playerSnapshots();
        for (int i = 0; i < player.size(); i++) {
            ItemStack stack = player.get(i).stack();
            if (player.get(i).isEmpty() || !upgrade.isBaseIngredient(stack)) continue;
            candidates.add(new ItemToUpgrade.Candidate(i, enchantmentLevels(stack), stack.getDamageValue()));
        }
        return ItemToUpgrade.pick(candidates).map(ItemToUpgrade.Candidate::slotIndex);
    }

    private static int enchantmentLevels(ItemStack stack) {
        int total = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            total += entry.getIntValue();
        }
        return total;
    }

    private static Optional<Integer> firstAccepted(MenuContent.Reading open, Predicate<ItemStack> accepts) {
        List<SlotSnapshot> player = open.playerSnapshots();
        for (int i = 0; i < player.size(); i++) {
            if (!player.get(i).isEmpty() && accepts.test(player.get(i).stack())) return Optional.of(i);
        }
        return Optional.empty();
    }

    private Predicate<ItemStack> acceptor(int slot) {
        return slot == TEMPLATE ? upgrade::isTemplateIngredient : upgrade::isAdditionIngredient;
    }

    private static String slotName(int slot) {
        return switch (slot) {
            case TEMPLATE -> "模板";
            case BASE -> "要升级的物品";
            default -> "升级材料";
        };
    }

    // 界面没了：被打断时关的、或别的原因关的，都回到跟前重新点开接着放；格子里的东西关界面时原版已还回背包。
    // 不是打断关的、一再被关掉才如实失败。
    private ActionStatus reopen() {
        if (!reopens.mayReopen(closedByPause)) {
            return fail(Problem.Kind.STUCK, "锻造台的界面一再被关掉，" + recipe.result().describe() + "升级不下去");
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

    // 关界面：一份界面会话从头用到尾，只请求游戏关一次；关不上不冒充没开过，照常收场让引擎清点。
    private ActionStatus close(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isEmpty()) return ActionStatus.done();
        if (closingSession == null) {
            MenuSession.Claim claim = MenuSession.claim(reading.get().channel());
            if (claim instanceof MenuSession.Claim.Refused) return ActionStatus.done();
            closingSession = ((MenuSession.Claim.Owned) claim).session();
        }
        var closing = closingSession.closeNow(reading.get().channel(), tick.player().clientTick());
        if (closing instanceof MenuSession.Closing.Closed || closing instanceof MenuSession.Closing.Failed) {
            return ActionStatus.done();
        }
        return ActionStatus.running();
    }

    private ActionStatus fail(Problem.Kind kind, String message) {
        failure = Problem.of(kind, message, null);
        return ActionStatus.failed(failure);
    }

    private static String describe(SlotSnapshot shown) {
        return shown.stack().getHoverName().getString() + "×" + shown.count();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // 被生存需求打断：走到一半的先停住；界面开着就关上（格子里的东西原版还回背包），回来重新点开接着放。
    @Override
    public void pause() {
        boolean menuOpen = openReading().isPresent();
        if (visit != null) visit.pause();
        abandonMenu();
        if (menuOpen) closedByPause = true;
        if (stage == Stage.VISIT) dropVisit();
    }

    @Override
    public void close() {
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

    // 这一下点出去还没等到游戏确认时不宜打断：鼠标上可能还拿着东西。
    @Override
    public Interruptibility interruptibility() {
        return run != null && !run.idle() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return "在锻造台上升级出 " + recipe.result().describe();
    }
}
