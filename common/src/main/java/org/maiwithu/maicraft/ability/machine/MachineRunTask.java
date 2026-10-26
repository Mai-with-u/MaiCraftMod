// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.FeedReport;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 用机器做东西的任务：认机器、按配方备料、投料、开机、等出口出东西、收产物。
 * 一台机器一次做几件；一条生产线上有几台机器就是几次 machine_run。
 *
 * <p>出口只算这次新增：投料前先记下出口里已有什么。动力不够这类事不预判——
 * 照常投料开机，等不到产物时把机器读数（转速 0、过载）写进结果。投料与开机的交互
 * 在等确认时停下不安全，等产物时可以插生存需求，回来接着等。
 *
 * <p>出口有两种看法：画出来看得见的（置物台、工作盆）站在旁边一直盯着；
 * 格子在界面里的（通用机械）投料前先点开看一眼原有几件，投完料隔一个加工周期回去点开看、出了就收。
 */
final class MachineRunTask extends PhasedTask<MachineRunTask.Phase> {

    /**
     * 认机器 → 选工序 →（出口在界面里的先看一眼）→ 备料 → 投料 → 开机 → 等产物 → 收产物；
     * 机器自己从头做到尾的（蓝图桌、蓝图加农炮）认完直接交给它。
     */
    enum Phase { RESOLVE, RECIPE, PEEK, GATHER, FEED, SWITCH, WAIT, COLLECT, FULL }

    /** 等产物时多久看一眼出口。 */
    private static final int POLL_INTERVAL_TICKS = 20;
    /** 这么久没有新东西出炉，就当作不再出，如实结算；加工总有一个周期，长过它就该看读数了。 */
    private static final int NO_GROWTH_LIMIT_TICKS = 20 * 60;

    private final MachineRunInput input;
    private final MachineServices services;
    private final MachineTargets targets;
    private MachineType type;
    private BlockPos cell;
    /** 选中的配方与要做的批数；只开机时没有。 */
    private ShownRecipe recipe;
    private int batches;
    /** 备料与投料各一条队，按配方的顺序从前往后走。 */
    private final Deque<MachineRunDetails.Fed> toGather = new ArrayDeque<>();
    private final Deque<MachineRunDetails.Fed> toFeed = new ArrayDeque<>();
    /** 投进去的账与过程中值得知道的事。 */
    private final List<MachineRunDetails.Fed> fed = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    /** 等产物的账：出口基线、新增、拿回数与时间点，都收在这里。 */
    private final Watch watch = new Watch();
    /** 开关那两步：走近了没有、出手前方块长什么样。 */
    private Switching switching;
    /** 下一刻要推进的动作：上一刻按现场备好，进入阶段时交给基类。 */
    private Action prepared;
    /** 正在投的这一样料交得出实情时（扔进去这类不一定件件都进的投法）留着它，做完按实情记账。 */
    private FeedReport feeding;
    /** 机器自己从头做到尾的那一次（蓝图桌写蓝图、蓝图加农炮施工）；按配方做的为 null。 */
    private FullRun fullRun;
    /** 这次是往发电机这类烧燃料的机器里投燃料：item 就是燃料，投完就收场，不拨开关、不等产物。 */
    private boolean fuelRun;
    /** 出口在界面里、站在外面看不见：投料前先点开看一眼，投完料隔一个周期回去点开看。 */
    private boolean menuExit;
    /** 开工前点开界面看出口的那一眼；接不上界面时为 null，等产物时就照读不到收手。 */
    private OutputLook peeking;
    /** 出口在界面里的回头看产物；别的出口为 null。 */
    private MenuOutputLookBack lookBack;

    MachineRunTask(MachineRunInput input, MachineServices services) {
        super("用机器做东西", Phase.RESOLVE,
                new ProgressTracker(NO_GROWTH_LIMIT_TICKS + 600, (long) input.maxSeconds() * 20 + 2400));
        this.input = input;
        this.services = services;
        this.targets = new MachineTargets(services);
    }

    @Override protected Action enter(Phase phase) {
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case RESOLVE -> resolve(context);
            case RECIPE -> pickRecipe(context);
            case PEEK -> peek(context);
            case GATHER -> gather(context);
            case FEED -> feed(context);
            case SWITCH -> flipSwitch(context);
            case WAIT -> waitForOutput(context);
            case COLLECT -> collect(context);
            case FULL -> full(context);
        };
    }

    // 认机器：目标那一格要有机器类型认领；出口基线在这里记下，之后的增长才算这次的。
    private Next<Phase> resolve(TickContext context) {
        MachineTargets.Resolution resolved = targets.resolve(input.target());
        if (resolved instanceof MachineTargets.Resolution.DeadEnd dead) {
            return Next.fail(dead.problem());
        }
        MachineTargets.Resolution.Found found = (MachineTargets.Resolution.Found) resolved;
        if (found.state() == null) {
            return Next.fail(Problem.of(Problem.Kind.UNREACHABLE,
                    "目标那一格还没加载，动不了机器", "先走近一点再试"));
        }
        type = services.claiming(found.state());
        cell = found.cell();
        if (type == null) {
            return Next.fail(services.unclaimedHere(found.state(), "用机器做东西"));
        }
        // 出口怎么看在这里定：界面里的投料前点开看，别的站在旁边读。
        menuExit = !type.outputVisible(cell);
        watch.baseline = menuExit ? 0 : outputCount(input.item());
        recordProgress("认出了" + describeMachine() + (menuExit ? "；出口在界面里，投料前点开看一眼原有几件"
                : "；出口里现在有 " + watch.baseline + " 件" + (input.item() == null ? "" : " " + input.item())));
        // 蓝图桌写蓝图、蓝图加农炮照蓝图施工：不按配方投料，整段交给机器类型；给了蓝图却不是这种机器就当场说。
        Optional<FullRun> whole = type.fullRun(cell, new RunRequest(input.item(), input.count(), input.file(),
                input.anchor(), input.rotation(), input.maxSeconds()), input.permissions());
        if (whole.isPresent()) {
            fullRun = whole.get();
            prepared = fullRun;
            return Next.go(Phase.FULL, describeMachine() + "从头做到尾");
        }
        if (input.file() != null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    describeMachine() + "不收蓝图：file、anchor 只给蓝图桌（写蓝图）和蓝图加农炮（照蓝图施工）",
                    "去掉 file、anchor 按配方做，或换成蓝图桌、蓝图加农炮"));
        }
        // 发电机这类烧燃料的：item 就是燃料，备齐直接投，不查配方；不吃料的发电机没有可投的格子，照实说不用投。
        FuelUse fuel = type.fuelUse(cell);
        if (input.item() != null && fuel.kind() == FuelUse.Kind.NO_FUEL) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    describeMachine() + "不烧燃料、没有燃料格，不用投料：" + fuel.note(),
                    "不给 item 只看它的读数，或换一台烧燃料的发电机"));
        }
        if (input.item() != null && fuel.kind() == FuelUse.Kind.FUEL) {
            fuelRun = true;
            notes.add(describeMachine() + "烧的燃料：" + fuel.note());
            toGather.add(new MachineRunDetails.Fed(input.item(), input.count()));
            return Next.go(Phase.GATHER, "把 " + input.item() + " 当燃料投进" + describeMachine()
                    + "，先备 " + input.count() + " 件");
        }
        return input.item() == null
                ? Next.go(Phase.SWITCH, "没说要做什么，只开机")
                : Next.go(Phase.RECIPE, "查这台机器做 " + input.item() + " 的配方");
    }

    // 选工序：查到的配方里要有在这台机器上做的；按要做的件数算出几批，原料排进备料队。
    private Next<Phase> pickRecipe(TickContext context) {
        RecipeLookup.Answer answer = services.recipes().making(input.item());
        List<ShownRecipe> atMachine = answer.recipes().stream()
                .filter(candidate -> candidate.madeAt(type.id())).toList();
        if (atMachine.isEmpty()) {
            // 配方查看器没装时看不出"在哪做"，如实说；装了还是没有，就是这台机器做不了。
            String withoutViewer = answer.answered() ? "" : "（这个实例没有配方查看器，看不出哪些配方在哪台机器上做）";
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "查到的配方里没有在" + describeMachine() + "上做 " + input.item() + " 的" + withoutViewer,
                    "先 lookup 查这台机器能做什么"));
        }
        recipe = atMachine.get(0);
        if (atMachine.size() > 1) {
            notes.add("查到 " + atMachine.size() + " 条都能在这台机器上做，用了第一条（" + recipe.category() + "）");
        }
        batches = batchesNeeded(recipe);
        queueIngredients(recipe);
        recordProgress("选中配方 " + recipe.category() + "，要做 " + batches + " 批");
        if (menuExit) {
            peeking = services.interactions().lookIntoMenu(cell, input.item(), false, input.permissions())
                    .orElse(null);
            if (peeking != null) {
                prepared = peeking;
                return Next.go(Phase.PEEK, "开工前点开" + describeMachine() + "看一眼出口");
            }
        }
        return afterPeek();
    }

    private Next<Phase> afterPeek() {
        return Next.go(toGather.isEmpty() ? Phase.SWITCH : Phase.GATHER,
                toGather.isEmpty() ? "配方的东西带不进机器，先开机试一次" : "备投料要的东西");
    }

    // 开工前看一眼界面里的出口：原有几件记成起点，回头看时只算新出的；看不成就说不清原有几件，照常开工。
    private Next<Phase> peek(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                watch.baseline = peeking.seen();
                notes.addAll(peeking.notes());
                recordProgress("开工前出口里有 " + watch.baseline + " 件 " + input.item());
                yield afterPeek();
            }
            case ActionStatus.Failed failed -> {
                notes.add("开工前没看成出口（" + failed.problem().message() + "），出口里原有几件说不清，回头看到的都算这次的");
                yield afterPeek();
            }
        };
    }

    // 配方原料按批次放大：物品的备料去拿。背包带不进去的化学品，机器能从附加格吃物品换的（灌注物从煤、红石换），
    // 换成那样物品排在主料前面先投：储罐里先有了灌注物，主料一放进去就开工。换不了的流体与化学品写进结果。
    private void queueIngredients(ShownRecipe chosen) {
        List<MachineRunDetails.Fed> mainItems = new ArrayList<>();
        for (ShownIngredient ingredient : chosen.inputs()) {
            Optional<ShownStack> item = ingredient.options().stream()
                    .filter(option -> option.kind() == ShownStack.Kind.ITEM).findFirst();
            if (ingredient.tag() == null && item.isEmpty()) {
                Optional<IngredientFeed> byItem = type.ingredientFeed(cell, ingredient, ingredient.amount() * batches);
                if (byItem.isPresent()) {
                    notes.add(byItem.get().note());
                    toGather.add(new MachineRunDetails.Fed(byItem.get().itemId(), byItem.get().count()));
                    continue;
                }
                notes.add("配方还要机器自己要的东西（" + describeIngredient(ingredient)
                        + "），带不进机器，做不做得看机器里还有没有");
                continue;
            }
            WantedItem wanted = ingredient.tag() != null
                    ? WantedItem.ofTag(ingredient.tag())
                    : WantedItem.ofItem(item.get().id());
            long count = (long) ingredient.amount() * batches;
            mainItems.add(new MachineRunDetails.Fed(wanted.specifier(),
                    (int) Math.min(Integer.MAX_VALUE, count)));
        }
        toGather.addAll(mainItems);
    }

    // 备料：一样一样去拿，缺了按缺东西收场；拿到了挪进投料队。
    private Next<Phase> gather(TickContext context) {
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> {
                    toFeed.add(toGather.poll());
                    yield Next.go(toGather.isEmpty() ? Phase.FEED : Phase.GATHER,
                            toGather.isEmpty() ? "料备齐了" : "拿到了一样料，接着备下一样");
                }
                case ActionStatus.Failed failed -> Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                        "备投料要的东西没拿齐：" + failed.problem().message(),
                        failed.problem().suggestion()));
            };
        }
        MachineRunDetails.Fed next = toGather.peek();
        if (next == null) {
            return Next.go(Phase.FEED, "料备齐了");
        }
        prepared = services.needs().actionFor(new ItemRequest(new WantedItem(next.item()), next.count(),
                "投进机器的料"), input.permissions(), records());
        return Next.go(Phase.GATHER, "去拿 " + next.item() + " ×" + next.count());
    }

    // 投料：交给机器类型的投法（手持右键顶面、放进输入槽），投好一样记一样。
    private Next<Phase> feed(TickContext context) {
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> {
                    MachineRunDetails.Fed done1 = settleFeed(toFeed.poll());
                    yield Next.go(toFeed.isEmpty() ? Phase.SWITCH : Phase.FEED,
                            toFeed.isEmpty() ? (fuelRun ? "燃料投完了" : "投料完了，开机") : "投进去一样，接着投下一样");
                }
                case ActionStatus.Failed failed -> stop(failed.problem());
            };
        }
        MachineRunDetails.Fed next = toFeed.peek();
        if (next == null) {
            return Next.go(Phase.SWITCH, "投料完了，开机");
        }
        return type.feed(cell, next.item(), next.count(), input.permissions())
                .map(action -> {
                    prepared = action;
                    feeding = action instanceof FeedReport report ? report : null;
                    return Next.go(Phase.FEED, "把 " + next.item() + " 投进" + describeMachine());
                })
                .orElseGet(() -> stop(Problem.of(Problem.Kind.UNSUPPORTED,
                        describeMachine() + "不收 " + next.item() + " 这种料")));
    }

    // 一样料投完记账：投法交得出实情（扔进去的）按进去的件数记，没进去的在哪写进说明；
    // 一下就整份放好的（手持右键放上置物台）按要投的件数记。没进去的不重投，交给 LLM 看说明再定。
    private MachineRunDetails.Fed settleFeed(MachineRunDetails.Fed asked) {
        int count = feeding == null ? asked.count() : feeding.delivered();
        if (feeding != null) {
            notes.addAll(feeding.notes());
            feeding = null;
        }
        MachineRunDetails.Fed settled = new MachineRunDetails.Fed(asked.item(), count);
        fed.add(settled);
        if (count > 0) {
            recordChange(new Change(Change.Kind.ITEM_CONSUMED, asked.item(), count, "投进了" + describeMachine()));
        }
        recordProgress(count == asked.count() ? "投进了 " + asked.item() + " ×" + count
                : "要投 " + asked.item() + " ×" + asked.count() + "，进去了 " + count + " 件");
        return settled;
    }

    // 开机：在转就不拨；读数说停着的，走近右键拨一下；别的读数不预判，投了料等产物见分晓。
    // 投的是燃料时到这里就收场：发电机有燃料自己烧，右键只会打开它的界面。
    private Next<Phase> flipSwitch(TickContext context) {
        if (fuelRun) {
            return finishFuel();
        }
        MachineState now = type.state(cell);
        if (now.activity() == MachineState.Activity.RUNNING) {
            recordProgress("机器本来就在转，不拨开关");
            return Next.go(Phase.WAIT, "机器在转，等产物");
        }
        if (now.activity() != MachineState.Activity.OFFLINE) {
            recordProgress("机器读数：" + describeState(now) + "，先等产物看做不做");
            return Next.go(Phase.WAIT, "看不出停着，等产物");
        }
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                // 走近那一步完成，重进阶段装右键；右键完成也重进阶段，下一刻转入等待。
                case ActionStatus.Done done -> Next.go(Phase.SWITCH,
                        switching.before() == null ? "走到了开关跟前" : "拨过了，去等产物");
                case ActionStatus.Failed failed -> stop(failed.problem());
            };
        }
        if (switching == null) {
            switching = new Switching(null);
            prepared = services.close().toward(ApproachTarget.ofBlock(cell), input.permissions());
            return Next.go(Phase.SWITCH, describeMachine() + "停着，走到开关跟前");
        }
        if (switching.before() == null) {
            // 出手前冻结方块状态，拨没拨得动以状态变没变为准。
            BlockState before = worldState();
            switching = new Switching(before);
            prepared = services.interactions().useBlock(cell,
                    InteractionConfirmation.blockChanged(cell, before));
            return Next.go(Phase.SWITCH, "右键拨一下开关");
        }
        recordAttempt("拨开关", "右键过了，开关读数 " + describeState(type.state(cell)));
        return Next.go(Phase.WAIT, "开关拨过了，等产物");
    }

    // 等产物：出口里目标物品的增长是进展；到量、到时限或久无新货就收，不等死。
    private Next<Phase> waitForOutput(TickContext context) {
        if (input.item() == null) {
            return finishRun(true);
        }
        if (menuExit) {
            return peeking == null ? unseenOutput() : lookBack(context);
        }
        long tick = context.gameTick();
        watch.lastTick = tick;
        if (watch.startTick < 0) {
            watch.startTick = tick;
            watch.lastGrowthTick = tick;
            watch.nextPollTick = tick;
        }
        if (tick >= watch.nextPollTick) {
            watch.nextPollTick = tick + POLL_INTERVAL_TICKS;
            int made = outputCount(input.item()) - watch.baseline;
            if (made > watch.produced) {
                watch.produced = made;
                watch.lastGrowthTick = tick;
                recordProgress("出口新增 " + made + " 件 " + input.item());
            }
        }
        if (watch.produced >= input.count()) {
            return Next.go(Phase.COLLECT, "出够了 " + watch.produced + " 件");
        }
        if (tick - watch.lastGrowthTick >= NO_GROWTH_LIMIT_TICKS) {
            notes.add("等了 " + NO_GROWTH_LIMIT_TICKS / 20 + " 秒没有新东西出炉，机器读数："
                    + describeState(type.state(cell)));
            return Next.go(Phase.COLLECT, "久无新货，收手");
        }
        if (tick - watch.startTick >= (long) input.maxSeconds() * 20) {
            notes.add("等到 " + input.maxSeconds() + " 秒上限，出了 " + watch.produced + " 件，机器读数："
                    + describeState(type.state(cell)));
            return Next.go(Phase.COLLECT, "到了等待上限，收手");
        }
        return Next.stay();
    }

    // 出口在界面里：交给回头看产物——等一个周期、点开看、出了就收；收手后按它交回的账结算。
    private Next<Phase> lookBack(TickContext context) {
        if (lookBack == null) {
            LookBackSchedule schedule = new LookBackSchedule(
                    LookBackSchedule.cycleTicks(recipe == null ? null : recipe.definition()), watch.baseline);
            lookBack = new MenuOutputLookBack(services.interactions(), cell, input.item(), input.count(),
                    input.collect(), input.permissions(), (long) input.maxSeconds() * 20, schedule);
            prepared = lookBack;
            return Next.go(Phase.WAIT, "隔一个加工周期回去点开" + describeMachine() + "看产物");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> settleLookBack(null);
            case ActionStatus.Failed failed -> settleLookBack(failed.problem());
        };
    }

    // 回头看收手：出了几件、收进背包几件、等了多久看了几回照它的账写；够数算完成，
    // 几回都没出、到了上限、看不成都按部分完成，附机器读数，交给 LLM 看是没电、没转还是配方不对。
    private Next<Phase> settleLookBack(Problem problem) {
        watch.produced = lookBack.produced();
        watch.collected = lookBack.collected();
        notes.addAll(lookBack.notes());
        if (watch.collected > 0) {
            recordChange(Change.of(Change.Kind.ITEM_GAINED, input.item(), watch.collected));
        }
        if (watch.collected > watch.produced) {
            notes.add("收进背包的 " + watch.collected + " 件里有 " + (watch.collected - watch.produced)
                    + " 件是开工前就在出口里的");
        }
        if (lookBack.leftInExit() > 0) {
            notes.add(lookBack.leftInExit() + " 件 " + input.item() + " 留在出口"
                    + (input.collect() ? "（背包放不下，或没搬成）" : "（按要求不收）"));
        }
        String looked = "，投完料等了 " + lookBack.waitedTicks() / 20 + " 秒、回去点开看了 " + lookBack.looks() + " 回";
        String machine = describeState(type.state(cell)) + describeNetworks();
        if (problem == null && lookBack.ending() == MenuOutputLookBack.Ending.ENOUGH) {
            return Next.done(TaskResult.done(describeMachine() + "做出了 " + watch.produced + " 件 " + input.item()
                    + (input.collect() ? "，已收进背包" : "，留在出口") + looked));
        }
        Problem why = problem != null ? problem
                : lookBack.ending() == MenuOutputLookBack.Ending.NOTHING_NEW
                ? Problem.of(Problem.Kind.STUCK, "连着 " + LookBackSchedule.EMPTY_LOOKS_LIMIT
                        + " 回点开看都没出新的 " + input.item() + "；机器读数：" + machine)
                : Problem.of(Problem.Kind.STUCK, "到了等待上限 " + input.maxSeconds() + " 秒还没出够；机器读数：" + machine);
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, describeMachine() + "出了 " + watch.produced
                + " / " + input.count() + " 件 " + input.item() + looked).problem(why).build());
    }

    // 收产物：拿得进背包就拿回，拿不进或不要了就留在出口，都如实写。
    private Next<Phase> collect(TickContext context) {
        if (!input.collect() || watch.produced == 0) {
            if (!input.collect()) {
                notes.add("按要求把 " + watch.produced + " 件留在出口");
            }
            return finishRun(false);
        }
        if (action() != null) {
            return switch (runAction(context)) {
                case ActionStatus.Running running -> Next.stay();
                case ActionStatus.Done done -> {
                    watch.collected = watch.produced;
                    recordChange(Change.of(Change.Kind.ITEM_GAINED, input.item(), watch.produced));
                    yield finishRun(false);
                }
                case ActionStatus.Failed failed -> {
                    notes.add("从出口拿东西没成：" + failed.problem().message() + "；东西留在出口");
                    yield finishRun(false);
                }
            };
        }
        return type.take(cell, input.item(), watch.produced, input.permissions())
                .map(action -> {
                    prepared = action;
                    return Next.go(Phase.COLLECT, "从出口拿回 " + watch.produced + " 件");
                })
                .orElseGet(() -> {
                    notes.add("这台机器的出口拿不进背包，东西留在出口");
                    return finishRun(false);
                });
    }

    // 机器自己从头做到尾：动作跑完按它交回的件数、做全没有与说明结算；半路失败时做出过的照实算数。
    private Next<Phase> full(TickContext context) {
        if (action() == null) {
            return finishFull(null);
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> finishFull(null);
            case ActionStatus.Failed failed -> finishFull(failed.problem());
        };
    }

    private Next<Phase> finishFull(Problem problem) {
        watch.produced = fullRun.produced();
        notes.addAll(fullRun.notes());
        if (input.item() != null && watch.produced > 0) {
            watch.collected = watch.produced;
            recordChange(Change.of(Change.Kind.ITEM_GAINED, input.item(), watch.produced));
        }
        String made = describeMachine() + "做出了 " + watch.produced + (input.item() == null ? " 格" : " 件 " + input.item());
        if (problem != null) {
            return watch.produced == 0 ? Next.fail(problem)
                    : Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, made + "，没做完").problem(problem).build());
        }
        if (fullRun.complete()) {
            return Next.done(TaskResult.done(made));
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, made + "，没做完")
                .problem(Problem.of(Problem.Kind.STUCK, "没做完；机器读数：" + describeState(type.state(cell)))).build());
    }

    // 出口在界面里、这一处却点不开界面看（没接上界面）：投料开机到此为止，不对着读不到的出口干等，也不说做好了；
    // 做没做好交给 LLM 之后打开界面看。
    private Next<Phase> unseenOutput() {
        notes.add(describeMachine() + "的出口在界面里，这里点不开界面看：料投进去了，做没做好之后用 use 打开它看");
        if (input.collect()) notes.add("出口读不到，这次没收产物");
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                        "料投进了" + describeMachine() + "，出口读不到，没等产物")
                .problem(Problem.of(Problem.Kind.UNSUPPORTED, describeMachine() + "的出口这里读不到，等不了产物；"
                        + "机器读数：" + describeState(type.state(cell)) + describeNetworks()))
                .build());
    }

    // 燃料投完：要的都进去了算完成；燃料格放不下的按部分完成，读数照给，发电没发电由读数与 LLM 再看。
    private Next<Phase> finishFuel() {
        int delivered = fuelDelivered();
        String line = "往" + describeMachine() + "里投了 " + delivered + " 件 " + input.item() + " 当燃料；机器读数："
                + describeState(type.state(cell)) + describeNetworks();
        if (delivered >= input.count()) {
            return Next.done(TaskResult.done(line));
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL, line)
                .problem(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "要投 " + input.count() + " 件，燃料格只放进去 " + delivered + " 件",
                        "燃料格满了就等它烧掉一些再投，或少投几件"))
                .build());
    }

    private int fuelDelivered() {
        return fed.stream().mapToInt(MachineRunDetails.Fed::count).sum();
    }

    // 收尾：出了要的量算完成，差一点按部分完成，机器读数与网络读数一起给。
    private Next<Phase> finishRun(boolean justSwitchedOn) {
        String machine = describeState(type.state(cell)) + describeNetworks();
        if (justSwitchedOn) {
            return Next.done(TaskResult.done("开机了；机器读数：" + machine));
        }
        if (watch.produced >= input.count()) {
            return Next.done(TaskResult.done(describeMachine() + "做出了 " + watch.produced + " 件 "
                    + input.item() + (input.collect() ? "，已收进背包" : "，留在出口")));
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                        describeMachine() + "出了 " + watch.produced + " / " + input.count()
                                + " 件 " + input.item())
                .problem(Problem.of(Problem.Kind.STUCK, "没等到全部产物；机器读数：" + machine)).build());
    }

    // 投料往后的失败：投进去的与已出的照实算数，剩下的进剩余说明；一样没投按失败。
    private Next<Phase> stop(Problem problem) {
        if (fed.isEmpty() && watch.produced == 0) {
            return Next.fail(problem);
        }
        return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                        "做到一半停下：已投 " + describeFed() + "，出口新增 " + watch.produced + " 件")
                .problem(problem).build());
    }

    private String describeFed() {
        List<String> parts = new ArrayList<>();
        for (MachineRunDetails.Fed item : fed) {
            parts.add(item.item() + " ×" + item.count());
        }
        return String.join("、", parts);
    }

    /** 出口里目标物品现在有几件；没给目标物品或读不到按 0 算：等的是增长，基线之下不会误报。 */
    private int outputCount(String itemId) {
        if (itemId == null) {
            return 0;
        }
        return type.output(cell).stream()
                .filter(shown -> shown.itemId().equals(itemId))
                .mapToInt(shown -> shown.count())
                .sum();
    }

    private int batchesNeeded(ShownRecipe chosen) {
        long perBatch = chosen.outputs().stream()
                .filter(stack -> stack.kind() == ShownStack.Kind.ITEM && stack.id().equals(input.item()))
                .mapToLong(ShownStack::amount).sum();
        if (perBatch <= 0) {
            return 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, (input.count() + perBatch - 1) / perBatch);
    }

    private String describeIngredient(ShownIngredient ingredient) {
        if (ingredient.tag() != null) {
            return ingredient.tag();
        }
        return ingredient.options().stream().findFirst().map(ShownStack::id).orElse("未知的东西");
    }

    private String describeMachine() {
        return type.name();
    }

    // 机器读数的一句话：在干什么、转速、进度，读不到的把原因带上。
    private String describeState(MachineState state) {
        String activity = state.activity().name().toLowerCase(Locale.ROOT);
        StringBuilder line = new StringBuilder(activity);
        if (state.speed() != null) {
            line.append("，转速 ").append(state.speed());
        }
        if (state.progress() != null) {
            line.append("，进度 ").append(Math.round(state.progress() * 100)).append("%");
        }
        if (!state.note().isEmpty()) {
            line.append("（").append(state.note()).append("）");
        }
        return line.toString();
    }

    // 结束时把机器挂着的网络读数带上：转速 0、过载、没电这些一眼能对上原因。
    private String describeNetworks() {
        StringBuilder line = new StringBuilder();
        for (NetworkReader reader : services.networkReaders()) {
            Optional<String> id = reader.membership(cell);
            if (id.isEmpty()) {
                continue;
            }
            var summary = reader.summary(id.get());
            line.append(line.isEmpty() ? "；网络：" : "，").append(reader.kind().id());
            if (!summary.readable()) {
                line.append("读不到（").append(summary.note()).append("）");
                continue;
            }
            List<String> readings = new ArrayList<>();
            summary.readings().forEach((name, value) -> readings.add(name + "=" + value));
            if (!readings.isEmpty()) {
                line.append("（").append(String.join("，", readings)).append("）");
            }
        }
        return line.toString();
    }

    private BlockState worldState() {
        return services.world().stateAt(cell).orElse(null);
    }

    @Override protected ResultDetails details() {
        if (type == null) {
            // 没认出机器就结束的（目标没落实）：没有机器读数可给。
            return new MachineRunDetails(null, 0, fed, watch.produced, watch.collected, 0, 0, "", notes);
        }
        return new MachineRunDetails(recipe == null ? null
                        : new MachineRunDetails.Recipe(recipe.category(), recipe.recipeId()),
                batches, fed, watch.produced, watch.collected, waitedSeconds(),
                lookBack == null ? 0 : lookBack.looks(), describeState(type.state(cell)), notes);
    }

    // 等产物等了多久：回头看的按它的账，站在旁边盯着的按开始等到最后一次看出口。
    private int waitedSeconds() {
        if (lookBack != null) {
            return (int) (lookBack.waitedTicks() / 20);
        }
        return watch.startTick < 0 ? 0 : (int) ((watch.lastTick - watch.startTick) / 20);
    }

    @Override protected List<String> remaining() {
        if (fuelRun) {
            int unfed = input.count() - fuelDelivered();
            return unfed <= 0 ? List.of() : List.of("还有 " + unfed + " 件燃料 " + input.item() + " 没投进去");
        }
        int left = input.count() - watch.produced;
        if (input.item() == null || left <= 0) {
            return List.of();
        }
        return List.of("还差 " + left + " 件 " + input.item());
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case RESOLVE -> "认机器";
            case RECIPE -> "选工序";
            case PEEK -> "开工前看一眼出口";
            case GATHER -> "备料";
            case FEED -> "投料";
            case SWITCH -> "开机";
            case WAIT -> "等产物";
            case COLLECT -> "收产物";
            case FULL -> "机器自己做";
        };
    }

    /** 等产物的账：出口基线与时间点。 */
    private static final class Watch {
        int baseline;
        int produced;
        int collected;
        long startTick = -1;
        long lastTick;
        long lastGrowthTick;
        long nextPollTick;
    }

    /** 开关那两步的进度：before 为 null 是走近那一步装好了，不为 null 是右键装好了（出手前冻结的方块状态）。 */
    private record Switching(BlockState before) {
    }
}
