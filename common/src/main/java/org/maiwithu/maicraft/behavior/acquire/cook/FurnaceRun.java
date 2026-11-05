// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.inventory.hands.StackSplit;
import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuReopenCount;
import org.maiwithu.maicraft.behavior.menu.MenuSession;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用熔炉烧一批：走到熔炉跟前点开 → 没在烧又没燃料就按要烧的件数添燃料 → 把要烧的原料按件数装进投入口（一次一组）
 * → 关上界面在旁边等 → 估摸着烧好了（或炉子灭了）再走过去点开 → 收走成品。没烧完接着等，燃料烧完就补，
 * 投入口烧空了身上还有原料就再装一组；做够了只收回自己放进去的燃料和原料，关上界面再走。
 *
 * <p>等的时候界面关着：吃东西、自卫打断碰不到炉子里的东西，回来照常取；界面被关掉（打断或别的原因）
 * 不当成烧完了，重新走到跟前点开接着照看。炉子亮着就是在烧，按真实进展报，不让任务把等待当成原地打转。
 * 炉子里是别人的东西（投入口是别的原料、产出格是别的成品）就是正忙，不动它：这台里还没放过这次的东西，
 * 就关上界面换记得的下一台同种炉子（近的在前，连第一台最多三台，见 {@link FurnaceChoices}），换过去的那台
 * 走不到、点不开也接着换；都没用上才收场，逐台说清在哪、为什么。已经放过东西的那台不丢下，照旧在那台收场。
 *
 * <p>obtain 这一场里一炉还要烧一分钟以上：添燃料只按这一炉算、不够烧完先补，然后装好炉就走——原料燃料留在
 * 炉子里接着烧，记进留着烧的那几台（{@link FurnacesLeftCooking}）并挂上计时，关上界面这一趟就做完了。
 * 回到记着的那台点开先核对：还是那一炉就算自己的东西在里面，照旧在这台收场；到点了没烧完、
 * 或者已经不是那一炉了，如实记一笔。别的能力途中缺料来烧，照旧关着界面在旁边等。
 */
final class FurnaceRun implements Action {

    /** 原版熔炉烧一件的刻数；配方自己写了烧多久时按配方。 */
    private static final int DEFAULT_COOK_TICKS = 200;
    /** 估摸烧好的时刻再多等一会儿：宁可晚回来一点，也别回来看见还差一件又得再等。 */
    private static final int CHECK_MARGIN_TICKS = 20;
    /** 关着界面最少等多久再回来看，最多隔多久回来收一次货（烧得多时也隔一阵收一次、看看燃料够不够）。 */
    private static final int MIN_AWAY_TICKS = 40;
    private static final int MAX_AWAY_TICKS = 20 * 60;
    /** 炉子灭了这么久就提前回去：燃料烧完了或已经烧完。 */
    private static final int UNLIT_GRACE_TICKS = 40;
    /** 炉子亮着时隔多久报一次进展。 */
    private static final int LIT_REPORT_TICKS = 100;
    /** 走过去、点开、装料、收货、收拾的余量：总期限是烧的时间加上它。 */
    private static final int HANDLING_TICKS = 20 * 120;
    /** 换一台炉子多给的时间：走到下一台、点开看一眼。 */
    private static final int SWITCH_TICKS = 20 * 60;
    /** 界面不是被打断关上、而是无缘无故没了，最多重开几次。 */
    /** 零碎燃料的上限：木棍、树苗一件只烧半件，有木板以上的燃料时不拿它们。 */
    static final int SMALL_FUEL_TICKS = 300;
    /** 一格最多放一组。 */
    private static final int STACK = 64;
    /** 熔炉容器侧：0 投入口、1 燃料槽、2 产出格。 */
    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int OUTPUT = 2;

    /** 走过去点开、照看、收拾、关界面、关着界面等。 */
    private enum Stage { VISIT, TEND, TIDY, CLOSE, AWAY }

    /** 手上这一笔搬运在做什么：结论不同各家交待不同，按它分流。 */
    private enum MoveKind { TAKE_OUTPUT, TIDY, LOAD }

    /** 身上的一堆燃料：角色侧第几格、几件、一件烧多少刻。 */
    record FuelStack(int index, int count, int burnTicks) {}

    /** 挑中的燃料：角色侧第几格、放几件。 */
    record FuelPick(int index, int count) {}

    private final RecipeHolder<?> holder;
    private final WorkstationRecipe recipe;
    /** 记得的几台同种炉子，近的在前；眼下用的是其中一台。 */
    private final FurnaceChoices choices;
    private WorldPosition station;
    private BlockPos at;
    /** 到某一台炉子跟前点开的一趟。 */
    private final Function<WorldPosition, StationVisit> visits;
    private final ReadsFuels fuels;
    private final WorldMemory memory;
    /** 装炉就走留下的那几台：走的时候记一笔、挂计时，回到记着的那台时核对并划掉。 */
    private final FurnacesLeftCooking leftCooking;
    /** 这一场能不能装好炉就走：只有 obtain 这一场能，别的能力途中缺料来烧照旧在旁边等。 */
    private final boolean leavesLongCooking;
    private final int targetItems;
    private final int cookTicks;
    /** 总期限：烧的时间加上照看的余量，每换一台炉子再多给一些。 */
    private long giveUpTicks;

    private Stage stage = Stage.VISIT;
    private StationVisit visit;
    private MenuContent menus;
    private MenuSession closingSession;
    /** 关上界面以后是去旁边等（true）还是收场（false）。 */
    private boolean awayAfterClose;
    /** 关上界面以后换下一台炉子：这台正忙，里面又没有这次放的东西。 */
    private boolean moveOnAfterClose;
    /** 装好炉就走：关上界面以后这一趟就做完了，炉子接着烧。 */
    private boolean leaveAfterClose;
    /** 这一台点开以后核对过"是不是装炉就走留下的那台"没有：每到一台核对一次。 */
    private boolean checkedLeft;
    /** 装好就走前补过一次燃料：补一次就走，补不上也照走。 */
    private boolean fuelToppedUp;
    private long awayTicks;
    private long awayUntil;
    private long unlitSince = -1;
    private long lastLitReport;
    private int waited;
    /** 背包里实际多出来的成品件数：每次收货按收货前后背包里的数量差记。 */
    private int produced;
    /** 收货前背包里有几件成品：结论没直接报差时的对照。 */
    private int carriedBefore;
    /** 发起任务的记账口：每收一次货记一笔烧出来的东西，结果里的变化能和背包净变化对得上；null 不记。 */
    private final TaskRecords records;
    private final MenuReopenCount reopens = new MenuReopenCount();
    private boolean closedByPause;
    /** 自己放进燃料槽的是什么：收拾时只收回这一种，炉子里原有的燃料不拿走。 */
    private String fuelPlaced;
    /** 这次往投入口装过原料：收拾时才收回投入口剩下的。 */
    private boolean inputLoaded;
    private boolean fuelTidied;
    private boolean inputTidied;
    /** 收场的原因（缺料、缺燃料、炉子正忙、背包满了）；一件没收到时按它失败。 */
    private Problem ending;
    private Problem failure;
    /** 认领到的那份界面与手上的搬运：逐刻点、按核对式出结论都交给它。 */
    private OpenedMenu menu;
    private TransferRun run;
    private MoveKind moveKind;
    private String moveWhat = "";

    /**
     * @param stations 记得的同种炉子，近的在前，第一台是挑中的那台；正忙时按次序换下一台
     * @param visits   到某一台炉子跟前点开的一趟
     */
    FurnaceRun(RecipeHolder<?> holder, WorkstationRecipe recipe, List<WorldPosition> stations, int times,
            Function<WorldPosition, StationVisit> visits, ReadsFuels fuels, WorldMemory memory, TaskRecords records,
            FurnacesLeftCooking leftCooking, boolean leavesLongCooking) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.choices = new FurnaceChoices(stations);
        this.station = choices.current();
        this.at = new BlockPos(station.x(), station.y(), station.z());
        this.visits = Objects.requireNonNull(visits, "visits");
        this.fuels = Objects.requireNonNull(fuels, "fuels");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.records = records;
        this.leftCooking = Objects.requireNonNull(leftCooking, "leftCooking");
        this.leavesLongCooking = leavesLongCooking;
        this.targetItems = times * recipe.resultCount();
        this.cookTicks = holder.value() instanceof AbstractCookingRecipe cooking && cooking.getCookingTime() > 0
                ? cooking.getCookingTime() : DEFAULT_COOK_TICKS;
        this.giveUpTicks = (long) times * cookTicks + HANDLING_TICKS;
    }

    /** 这次用的炉子叫什么：熔炉、高炉或烟熏炉，按配方的设施种类说。 */
    private String stove() {
        return recipe.kind().displayName();
    }

    @Override
    public ActionStatus tick(TickContext tick) {
        if (failure != null) {
            return ActionStatus.failed(failure);
        }
        if (++waited > giveUpTicks) {
            return fail(Problem.Kind.STUCK, "用" + stove() + "烧" + recipe.result().describe() + "超时，先收手"
                    + (inputLoaded ? "；" + stove() + "里可能还有这次放进去的原料或烧好的成品" : ""));
        }
        return switch (stage) {
            case VISIT -> visit(tick);
            case TEND -> tend(tick);
            case TIDY -> tidy(tick);
            case CLOSE -> closeMenu(tick);
            case AWAY -> away(tick);
        };
    }

    // 走到熔炉跟前点开：每次回来都重新走一遍，人可能被挤开、撤离过。
    // 换过去的那台走不到、点不开，里面又没有这次放的东西：记下原因接着换下一台，不在这一台上收场。
    private ActionStatus visit(TickContext tick) {
        if (visit == null) {
            visit = visits.apply(station);
        }
        ActionStatus status = visit.tick(tick);
        if (status instanceof ActionStatus.Failed failed) {
            Problem why = failed.problem();
            if (!choices.onFirst() && nothingOfOursInside()) {
                return unreachable(why.headline());
            }
            return fail(why.kind(), why.message()
                    + (inputLoaded ? "；" + stove() + "里可能还有这次放进去的原料或烧好的成品" : ""));
        }
        if (status instanceof ActionStatus.Done) {
            menus = visit.menus();
            menu = ClientOpenedMenu.claimCurrent(visit.context(), visit.menus());
            run = menu == null ? null : new TransferRun(menu);
            closingSession = null;
            stage = Stage.TEND;
            return ActionStatus.progressed();
        }
        return status;
    }

    // 界面开着时照看熔炉，一刻只点一下：收成品 → 做够了收拾 → 添燃料 → 装原料 → 关上界面去旁边等。
    private ActionStatus tend(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isEmpty()) {
            return reopen();
        }
        if (run != null && !run.idle()) {
            return run.tickAction(this::onMoveSettled);
        }
        MenuContent.Reading open = reading.get();
        // 每到一台先看是不是装炉就走留下的那台：是就核对里面还是不是那一炉，再按炉子里实际有什么照看。
        if (!checkedLeft) {
            checkedLeft = true;
            checkLeft(open, tick);
        }
        SlotSnapshot input = open.containerSnapshots().get(INPUT);
        SlotSnapshot output = open.containerSnapshots().get(OUTPUT);
        // 炉子里是别人的东西：不动它。这台里还没放过这次的东西就关上换下一台，放过的照旧如实说正忙。
        if (!output.isEmpty() && !isResult(output.stack())) {
            return busy("产出格里放着" + output.stack().getHoverName().getString());
        }
        if (!input.isEmpty() && !isIngredient(input.stack())) {
            return busy("投入口里在烧" + input.stack().getHoverName().getString());
        }
        if (!output.isEmpty()) {
            return takeOutput(open);
        }
        if (produced >= targetItems) {
            return finish(null);
        }
        boolean fueled = lit(tick) || !open.containerSnapshots().get(FUEL).isEmpty();
        if (input.isEmpty()) {
            // 投入口空了：身上没原料就按做到多少收场；有就先备好燃料再装一组。
            int source = ingredientSource(open);
            if (source < 0) {
                return finish(Problem.of(Problem.Kind.NEED_ITEM,
                        "身上没有要烧的原料了，烧不出" + recipe.result().describe()));
            }
            return fueled ? loadInput(tick, open, source) : addFuel(tick, open);
        }
        // 投入口有料：没在烧又没燃料就补燃料，都齐了关上界面去旁边等。
        return fueled ? leaveToWait(tick, open, input.count()) : addFuel(tick, open);
    }

    // 回到装炉就走留下的那台：划掉记着的那一炉（人已经回来了，还挂着的计时摘掉）。里面还是那一炉，
    // 就算自己的东西在里面——收拾时收回自己的原料和燃料，炉子正忙也不丢下它；到点了还没烧完，
    // 如实记一笔（角色走远时区块不加载，炉子就停着）；已经不是那一炉了，记下当时里面是什么，不当成烧好了。
    // 别的配方留下的那一炉不归这一趟管，原样记着。
    private void checkLeft(MenuContent.Reading open, TickContext tick) {
        Optional<LeftCooking> left = leftCooking.at(station).filter(cooking -> cooking.recipeId().equals(recipe.id()));
        if (left.isEmpty()) return;
        leftCooking.collected(station);
        SlotSnapshot input = open.containerSnapshots().get(INPUT);
        SlotSnapshot output = open.containerSnapshots().get(OUTPUT);
        int oursIn = !input.isEmpty() && isIngredient(input.stack()) ? input.count() : 0;
        int oursOut = !output.isEmpty() && isResult(output.stack()) ? output.count() : 0;
        FurnaceLeaving.Return seen = FurnaceLeaving.onReturn(left.get(), oursIn, oursOut,
                "投入口" + slotText(input) + "，产出格" + slotText(output), tick.gameTick());
        if (seen.finding() != null && records != null) {
            records.attempt(FurnaceLeaving.returnTried(left.get()), seen.finding());
        }
        if (oursIn > 0) inputLoaded = true;
        if (seen.ours() && left.get().fuelItemId() != null) fuelPlaced = left.get().fuelItemId();
    }

    // 装原料：身上一堆配方认的原料分出还要烧的件数放进投入口，一次最多一组。
    private ActionStatus loadInput(TickContext tick, MenuContent.Reading open, int source) {
        ItemStack kind = open.playerSnapshots().get(source).stack();
        int amount = loadAmount(kind);
        inputLoaded = true;
        return load(open.playerSlotIds().get(source), open.containerSlotIds().get(INPUT), kind, amount,
                "往投入口装" + kind.getHoverName().getString());
    }

    // 这一组装几件：还要烧的件数、这一堆有几件、一格放得下几件，取最小的。
    private int loadAmount(ItemStack kind) {
        return Math.min(Math.min(stillToSmelt(), kind.getCount()), Math.min(kind.getMaxStackSize(), STACK));
    }

    // 添燃料：按要烧的件数挑一堆最合适的燃料，分出够烧完的件数放进燃料槽；身上没有能烧的就收场说明。
    private ActionStatus addFuel(TickContext tick, MenuContent.Reading open) {
        FuelPick pick = pickFuel(fuelStacks(open, null), (long) fuelFor(open) * cookTicks);
        if (pick == null) {
            return finish(Problem.of(Problem.Kind.NEED_ITEM, "身上没有能烧的燃料（工具、要烧的原料和成品不算），烧不了"
                    + recipe.result().describe()));
        }
        ItemStack kind = open.playerSnapshots().get(pick.index()).stack();
        fuelPlaced = itemId(kind);
        return load(open.playerSlotIds().get(pick.index()), open.containerSlotIds().get(FUEL), kind,
                pick.count(), "往燃料槽添" + kind.getHoverName().getString());
    }

    // 这次添燃料按几件算：能装好就走的这一场只按这一炉（投入口里的，或马上要装的那一组），
    // 走的时候不把后面几炉的燃料一次塞进炉子；不然按还要烧的全部，站着等时少添几回。
    private int fuelFor(MenuContent.Reading open) {
        if (!leavesLongCooking) return stillToSmelt();
        SlotSnapshot input = open.containerSnapshots().get(INPUT);
        if (!input.isEmpty()) return input.count();
        int source = ingredientSource(open);
        return source < 0 ? stillToSmelt() : loadAmount(open.playerSnapshots().get(source).stack());
    }

    // 身上能当燃料的几堆：工具、武器这类一格一件的不烧，这次要烧的原料和烧出来的成品也不烧；
    // onlyItem 给了就只要这一种（燃料槽里已经有燃料时只补得进同一种）。
    private List<FuelStack> fuelStacks(MenuContent.Reading open, String onlyItem) {
        List<FuelStack> stacks = new ArrayList<>();
        for (int i = 0; i < open.playerSnapshots().size(); i++) {
            SlotSnapshot stack = open.playerSnapshots().get(i);
            if (stack.isEmpty() || stack.stack().getMaxStackSize() <= 1
                    || isIngredient(stack.stack()) || isResult(stack.stack())) continue;
            if (onlyItem != null && !onlyItem.equals(itemId(stack.stack()))) continue;
            int burn = burnTicksOf(itemId(stack.stack()));
            if (burn > 0) stacks.add(new FuelStack(i, stack.count(), burn));
        }
        return stacks;
    }

    // 一件燃料在这种炉子里烧多少刻：燃料表给的是熔炉里烧多久，高炉、烟熏炉里同一件燃料只顶一半。
    private int burnTicksOf(String itemId) {
        return fuels.burnTicks(itemId) / recipe.kind().fuelBurnDivisor();
    }

    /**
     * 挑燃料：要烧 needTicks 刻。先挑一堆就够烧完的；再挑木板以上的（木棍、树苗一件只烧半件，太零碎）；
     * 同样的里挑烧完浪费最少的（烧两件东西不扔一块煤炭块进去），一样少时挑一件烧得久的（少点几下）。
     * 放几件按够烧完算，一组封顶；身上没有能烧的给 null。
     */
    static FuelPick pickFuel(List<FuelStack> stacks, long needTicks) {
        FuelStack best = null;
        long[] bestKey = null;
        long need = Math.max(1, needTicks);
        for (FuelStack stack : stacks) {
            if (stack.count() <= 0 || stack.burnTicks() <= 0) continue;
            long items = Math.min(stack.count(), ceilDiv(need, stack.burnTicks()));
            long covered = items * stack.burnTicks();
            long[] key = {covered >= need ? 0 : 1, stack.burnTicks() >= SMALL_FUEL_TICKS ? 0 : 1,
                    Math.max(0, covered - need), -stack.burnTicks()};
            if (best == null || Arrays.compare(key, bestKey) < 0) {
                best = stack;
                bestKey = key;
            }
        }
        if (best == null) {
            return null;
        }
        long count = Math.min(Math.min(best.count(), STACK), ceilDiv(need, best.burnTicks()));
        return new FuelPick(best.index(), (int) count);
    }

    // 料和燃料都齐了。这一场能装好就走、这一炉还要烧一分钟以上：燃料不够烧完先补，补好了装好炉就走；
    // 不然关上界面去旁边等，估摸这一炉烧完的时刻再回来，烧得多时隔一阵回来收一次货。
    private ActionStatus leaveToWait(TickContext tick, MenuContent.Reading open, int loaded) {
        float cooking = tick.player().localPlayer().containerMenu instanceof AbstractFurnaceMenu furnace
                ? furnace.getBurnProgress() : 0f;
        long left = FurnaceLeaving.cookTicksLeft(loaded, cooking, cookTicks);
        if (leavesLongCooking && FurnaceLeaving.leavesNow(left)) {
            Optional<ActionStatus> topUp = topUpFuel(open, left);
            return topUp.isPresent() ? topUp.get() : leaveCooking(tick, open, loaded, left);
        }
        awayTicks = waitTicks(loaded, cooking, cookTicks);
        awayAfterClose = true;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    /** 投入口 loaded 件、手上这件烧了 cooking 成，估摸多久烧完再回来看（有上下限）。 */
    static long waitTicks(int loaded, float cooking, int cookTicks) {
        long left = FurnaceLeaving.cookTicksLeft(loaded, cooking, cookTicks) + CHECK_MARGIN_TICKS;
        return Math.max(MIN_AWAY_TICKS, Math.min(MAX_AWAY_TICKS, left));
    }

    // 装好就走前看燃料槽里的够不够烧完这一炉：按槽里那种燃料一件烧多久乘件数算，正在烧的那块剩的不算，
    // 宁可多添一点。不够就补：槽里有燃料只补同一种（一格只放得下一种），空着按身上最合适的挑；
    // 补一次就走，补不上也照走，回来时炉子停着就如实说烧了几件。
    private Optional<ActionStatus> topUpFuel(MenuContent.Reading open, long needTicks) {
        if (fuelToppedUp) return Optional.empty();
        fuelToppedUp = true;
        SlotSnapshot slot = open.containerSnapshots().get(FUEL);
        String inSlot = slot.isEmpty() ? null : itemId(slot.stack());
        long covered = slot.isEmpty() ? 0 : (long) slot.count() * burnTicksOf(inSlot);
        if (covered >= needTicks) return Optional.empty();
        FuelPick pick = pickFuel(fuelStacks(open, inSlot), needTicks - covered);
        int room = slot.isEmpty() ? STACK : slot.stack().getMaxStackSize() - slot.count();
        if (pick == null || room <= 0) return Optional.empty();
        ItemStack kind = open.playerSnapshots().get(pick.index()).stack();
        if (fuelPlaced == null) fuelPlaced = itemId(kind);
        return Optional.of(load(open.playerSlotIds().get(pick.index()), open.containerSlotIds().get(FUEL), kind,
                Math.min(pick.count(), room), "往燃料槽补" + kind.getHoverName().getString()));
    }

    // 装炉就走：原料燃料留在炉子里接着烧，记下这一炉、挂上计时（同一台原先记着的旧计时摘掉），
    // 这台记成用过的工作站；关上界面这一趟就做完了，拿东西的引擎看到留下了一炉就当场收场。
    private ActionStatus leaveCooking(TickContext tick, MenuContent.Reading open, int loaded, long cookLeft) {
        ItemStack ingredient = open.containerSnapshots().get(INPUT).stack();
        long now = tick.gameTick();
        leftCooking.leave(new LeftCooking(station, recipe.kind(), recipe.id(), recipe.result().itemId(),
                recipe.result().describe(), itemId(ingredient), ingredient.getHoverName().getString(), fuelPlaced,
                loaded, loaded * recipe.resultCount(), carriedIngredients(open), now, now + cookLeft, null));
        memory.rememberWorkstationUsed(station, MenuRecipeRuns.workstationBlockType(recipe.kind()), Instant.now());
        leaveAfterClose = true;
        awayAfterClose = false;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 关着界面在旁边等：到了估摸的时刻回去看；炉子灭了一阵（燃料烧完或已经烧完）就提前回去。
    // 炉子亮着就是在烧，隔一阵按真实进展报一次。
    private ActionStatus away(TickContext tick) {
        long now = tick.player().clientTick();
        boolean lit = blockLit(tick);
        if (lit) {
            unlitSince = -1;
        } else if (unlitSince < 0) {
            unlitSince = now;
        }
        if (now >= awayUntil || !lit && now - unlitSince >= UNLIT_GRACE_TICKS) {
            stage = Stage.VISIT;
            return ActionStatus.progressed();
        }
        if (lit && now - lastLitReport >= LIT_REPORT_TICKS) {
            lastLitReport = now;
            return ActionStatus.progressed();
        }
        return ActionStatus.running();
    }

    // 收成品：产出格整堆快速移动进背包，交给搬运执行核对——背包里这件东西真多出来才算。
    // 收到几件按背包侧的实际差算，不按产出格上写的数猜；真烧出来几件就记几件，
    // 逐笔记进发起任务的结果，结果里的变化能和背包净变化对得上。
    private ActionStatus takeOutput(MenuContent.Reading open) {
        int slotId = open.containerSlotIds().get(OUTPUT);
        int amount = open.containerSnapshots().get(OUTPUT).count();
        carriedBefore = carriedResults(open);
        run.begin(new TransferRun.Move(List.of(new MenuClick(slotId, true, 0, false)),
                new TransferRun.Check.IntoBackpack(
                        snapshot -> !snapshot.isEmpty() && isResult(snapshot.stack()), amount),
                "收" + recipe.result().describe()));
        moveKind = MoveKind.TAKE_OUTPUT;
        return ActionStatus.progressed();
    }

    // 收拾：只收回自己放进去的——这次添的那种燃料、这次装的原料；炉子里原有的燃料不拿走。收不回（背包满了）就留着。
    private ActionStatus tidy(TickContext tick) {
        Optional<MenuContent.Reading> reading = openReading();
        if (reading.isEmpty()) {
            // 收拾到一半界面没了（被打断）：回去点开再收；无缘无故一再被关就按失败收场。
            return reopen();
        }
        MenuContent.Reading open = reading.get();
        SlotSnapshot fuel = open.containerSnapshots().get(FUEL);
        SlotSnapshot input = open.containerSnapshots().get(INPUT);
        int index = -1;
        if (!fuelTidied && fuelPlaced != null && !fuel.isEmpty() && itemId(fuel.stack()).equals(fuelPlaced)) {
            index = FUEL;
            fuelTidied = true;
        } else if (!inputTidied && inputLoaded && !input.isEmpty() && isIngredient(input.stack())) {
            index = INPUT;
            inputTidied = true;
        }
        if (index < 0) {
            if (produced > 0) {
                memory.rememberWorkstationUsed(station, MenuRecipeRuns.workstationBlockType(recipe.kind()), Instant.now());
            }
            awayAfterClose = false;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }
        int slotId = open.containerSlotIds().get(index);
        SlotSnapshot shown = open.containerSnapshots().get(index);
        // 收回自己放进去的：整堆快速移动进背包，收不回（背包满了）就留着，照常往下走。
        run.begin(new TransferRun.Move(List.of(new MenuClick(slotId, true, 0, false)),
                new TransferRun.Check.IntoBackpack(shown::sameIdentity, shown.count()),
                "收回" + shown.stack().getHoverName().getString()));
        moveKind = MoveKind.TIDY;
        return ActionStatus.progressed();
    }

    // 关界面：打开者负责关；关上以后要么去旁边等，要么收场。关不上也照常往下走，不冒充没开过。
    private ActionStatus closeMenu(TickContext tick) {
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
        if (leaveAfterClose) {
            // 装好炉就走：炉子接着烧，这一趟做完了。
            return ActionStatus.done();
        }
        if (moveOnAfterClose) {
            // 正忙的那台关好了：走去下一台，从点开看一眼重新来。
            moveOnAfterClose = false;
            goTo(choices.current());
            return ActionStatus.progressed();
        }
        if (awayAfterClose) {
            long now = tick.player().clientTick();
            awayUntil = now + awayTicks;
            unlitSince = -1;
            lastLitReport = now;
            stage = Stage.AWAY;
            return ActionStatus.progressed();
        }
        if (produced == 0 && ending != null) {
            failure = ending;
            return ActionStatus.failed(failure);
        }
        return ActionStatus.done();
    }

    // 收场：记下原因（为空就是做够了），先收拾自己放进去的再关界面。
    private ActionStatus finish(Problem why) {
        if (why != null) ending = why;
        stage = Stage.TIDY;
        return ActionStatus.progressed();
    }

    // 炉子正忙：不动别人的东西。这台里没有这次放的东西就换下一台；放过的收拾自己放进去的就走。
    // 只记得这一台时照旧一句"正忙"收场，由引擎换别的路。
    private ActionStatus busy(String what) {
        if (nothingOfOursInside() && choices.skip("正忙，" + what)) {
            return moveOn();
        }
        if (choices.tried() > 1 && nothingOfOursInside()) {
            return finish(allSkipped());
        }
        return finish(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, stove() + "正忙：" + what + "，不是这次要烧的",
                "换一台" + stove() + "，或等它烧完再来"));
    }

    // 换过去的那台走不到、点不开（界面没开着）：还有下一台就直接走过去，试满了以逐台说明收场。
    private ActionStatus unreachable(String why) {
        if (choices.skip(why)) {
            giveUpTicks += SWITCH_TICKS;
            goTo(choices.current());
            return ActionStatus.progressed();
        }
        failure = allSkipped();
        return ActionStatus.failed(failure);
    }

    // 正忙的这台界面开着：先关上（不收拾，里面没有自己的东西），关好了再走去下一台。
    private ActionStatus moveOn() {
        giveUpTicks += SWITCH_TICKS;
        moveOnAfterClose = true;
        awayAfterClose = false;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    // 换到某一台炉子：之后的走过去、点开、看炉子亮不亮、记用过的设施都按这一台。
    private void goTo(WorldPosition next) {
        station = next;
        at = new BlockPos(next.x(), next.y(), next.z());
        // 换了一台：点开以后重新核对是不是留下的那台，补燃料也按这一台重新算。
        checkedLeft = false;
        fuelToppedUp = false;
        dropVisit();
        stage = Stage.VISIT;
    }

    // 试满了都没用上：逐台说清在哪、为什么，一件没烧出来交给引擎换别的路。
    private Problem allSkipped() {
        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, choices.summary(stove()),
                "等它们烧完再来，或换别的路（例如就地摆一台新的" + stove() + "）");
    }

    // 这台炉子里没有这次放进去的东西：没装过原料、没添过燃料，丢下它去别处不落下自己的东西。
    private boolean nothingOfOursInside() {
        return !inputLoaded && fuelPlaced == null;
    }

    // 界面没了：被生存需求打断时关的、或别的原因关的，都回到熔炉跟前重新点开接着照看，不当成烧完了。
    // 不是打断关的、一再被关掉才如实失败；分堆点到一半鼠标上的东西，关界面时原版已经还回背包。
    private ActionStatus reopen() {
        if (!reopens.mayReopen(closedByPause)) {
            return fail(Problem.Kind.STUCK, "熔炉的界面一再被关掉，烧" + recipe.result().describe() + "烧不下去");
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
        menu = null;
        run = null;
        closingSession = null;
    }

    // 分堆装料：从角色侧一格分出 amount 件放进炉子一格的左右键串整笔交给搬运执行，
    // 每刻至多发一下；结清按"源格恰好减了这么多、光标放空"核对——炉格可能刚被炉子吃掉一件，不核对它。
    private ActionStatus load(int sourceSlotId, int destinationSlotId, ItemStack kind, int amount, String what) {
        List<MenuClick> clicks = new ArrayList<>();
        for (StackSplit.Step step : StackSplit.plan(kind.getCount(), amount, kind.getMaxStackSize())) {
            boolean takesToCursor = step.side() == StackSplit.Side.SOURCE
                    && step.after().cursor() > step.before().cursor();
            int slot = step.side() == StackSplit.Side.SOURCE ? sourceSlotId : destinationSlotId;
            clicks.add(new MenuClick(slot, false, step.button(), takesToCursor));
        }
        run.begin(new TransferRun.Move(clicks, new TransferRun.Check.Machine(sourceSlotId, amount), what));
        moveKind = MoveKind.LOAD;
        moveWhat = what;
        return ActionStatus.progressed();
    }

    // 一笔搬运出了结论：按它在做什么分流。没动与对不上都按游戏没让这件事发生交待；
    // 界面中途没了照常往下走，下一刻照看时发现界面没了会回到炉子跟前重开。
    private ActionStatus onMoveSettled(TransferRun.MoveResult result) {
        if (result instanceof TransferRun.MoveResult.MenuGone) {
            return ActionStatus.progressed();
        }
        switch (moveKind) {
            case TAKE_OUTPUT -> {
                int gained = result instanceof TransferRun.MoveResult.Confirmed(int amount) ? amount
                        : openReading().map(now -> Math.max(0, carriedResults(now) - carriedBefore)).orElse(0);
                if (gained <= 0) {
                    return finish(Problem.of(Problem.Kind.INVENTORY_FULL,
                            "产出格的" + recipe.result().describe() + "收不进背包，背包可能满了"));
                }
                produced += gained;
                if (records != null) {
                    records.change(Change.of(Change.Kind.ITEM_GAINED, recipe.result().itemId(), gained));
                }
                return ActionStatus.progressed();
            }
            case TIDY -> {
                // 收不回就留着：照常往下走，收场时界面上剩什么照实说。
                return ActionStatus.progressed();
            }
            case LOAD -> {
                if (result instanceof TransferRun.MoveResult.Confirmed) {
                    return ActionStatus.progressed();
                }
                return fail(Problem.Kind.REFUSED_BY_GAME, moveWhat + "没能得到游戏确认");
            }
        }
        return ActionStatus.progressed();
    }

    // 还在的界面才读：点开的那份已经关了（就算又开了别的界面）都按界面没了算。
    private Optional<MenuContent.Reading> openReading() {
        return menus == null ? Optional.empty() : menus.current().filter(reading -> reading.channel().stillOpen());
    }

    // 投入口空着时能装的原料：身上第一堆配方认的。
    private int ingredientSource(MenuContent.Reading open) {
        for (int i = 0; i < open.playerSnapshots().size(); i++) {
            SlotSnapshot stack = open.playerSnapshots().get(i);
            if (!stack.isEmpty() && isIngredient(stack.stack())) return i;
        }
        return -1;
    }

    // 还要烧几件原料才做够：按配方单次产出折算。
    private int stillToSmelt() {
        int missing = Math.max(0, targetItems - produced);
        return (int) ceilDiv(missing, Math.max(1, recipe.resultCount()));
    }

    private boolean lit(TickContext tick) {
        return tick.player().localPlayer().containerMenu instanceof AbstractFurnaceMenu furnace && furnace.isLit();
    }

    // 关着界面看不到格子，只看炉子亮不亮：亮着就是在烧。
    private boolean blockLit(TickContext tick) {
        BlockState state = tick.player().level().getBlockState(at);
        return state.hasProperty(AbstractFurnaceBlock.LIT) && state.getValue(AbstractFurnaceBlock.LIT);
    }

    // 背包里还有几件这次的原料：装炉就走时说给 LLM，回来还要装几炉心里有数。
    private int carriedIngredients(MenuContent.Reading open) {
        int total = 0;
        for (SlotSnapshot stack : open.playerSnapshots()) {
            if (!stack.isEmpty() && isIngredient(stack.stack())) total += stack.count();
        }
        return total;
    }

    // 一格里放着什么的说法：空着，或放着几个什么。
    private static String slotText(SlotSnapshot slot) {
        return slot.isEmpty() ? "空着" : "放着 " + slot.count() + " 个" + slot.stack().getHoverName().getString();
    }

    private int carriedResults(MenuContent.Reading open) {
        int total = 0;
        for (SlotSnapshot stack : open.playerSnapshots()) {
            if (!stack.isEmpty() && isResult(stack.stack())) total += stack.count();
        }
        return total;
    }

    private boolean isResult(ItemStack stack) {
        return itemId(stack).equals(recipe.result().itemId());
    }

    private boolean isIngredient(ItemStack stack) {
        return holder.value().getIngredients().stream().anyMatch(ingredient -> ingredient.test(stack));
    }

    private ActionStatus fail(Problem.Kind kind, String message) {
        failure = Problem.of(kind, message, null);
        return ActionStatus.failed(failure);
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // 被生存需求打断：走到一半、点到一半的先停住；界面开着就关上（炉子接着烧），回来重新走到跟前点开接着照看。
    @Override
    public void pause() {
        boolean menuOpen = openReading().isPresent();
        if (visit != null) visit.pause();
        abandonMenu();
        if (menuOpen) closedByPause = true;
        if (stage == Stage.VISIT) dropVisit();
    }

    // 收尾：没走完的走到、没确认的点开一并收尾，开着的界面请游戏关上。
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

    // 点到一半、分堆分到一半时不宜打断：鼠标上可能还拿着东西。关着界面在旁边等炉子烧是两件事之间的空当：
    // 饿了吃一口、天黑去睡这类不急的事也能插进来，炉子照烧，回来走到跟前点开接着收。
    @Override
    public Interruptibility interruptibility() {
        // 手上有一笔搬运就不宜打断：一笔没点完，鼠标上可能还拿着东西。
        if (run != null && !run.idle()) return Interruptibility.UNSAFE_TO_STOP;
        return stage == Stage.AWAY ? Interruptibility.BETWEEN_ACTIONS : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        String progress = "（已收 " + produced + "/" + targetItems
                + (choices.tried() > 1 ? "，换到第 " + choices.tried() + " 台" : "") + "）";
        return stage == Stage.AWAY ? "关着界面等熔炉烧" + recipe.result().describe() + progress
                : "用熔炉烧" + recipe.result().describe() + progress;
    }
}
