// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.game.player.EverydayFood;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 饿了身上没吃的：像玩家一样去弄点现成的口粮。面包、熟肉、熟鱼、蔬菜、水果几类和"能吃的口粮"一大类
 * （含生肉、模组食物）同一轮问拿东西的估算哪类整条路走得最近（网络里、箱子里有就拿，没有再做、再打猎），
 * 挑最近的那类拿几件，一样近时熟食优先；有预算，超时或哪儿都没有如实说。
 * 估算在主线程上做、一类可能要估好一会儿，所以每刻只估一类，几类估齐了再排先后，不在同一刻连估六类卡住游戏。
 * 没弄满也不要紧：吃饭任务每刻看身上，弄到第一件这会儿能吃的（打到的生肉、烤好的第一块熟肉）就把这里叫停、先吃。
 */
final class FetchesFood implements Action {

    private static final Logger LOG = LoggerFactory.getLogger(FetchesFood.class);
    /** 口粮按这几类找（通用物品标签）；一样近时排前面的先。 */
    static final List<String> KINDS = List.of("c:foods/bread", "c:foods/cooked_meat", "c:foods/cooked_fish",
            "c:foods/vegetable", "c:foods/fruit");
    /** 一次问几件：箱子、网络里一把拿够吃一阵，不囤；要打猎、烧炼的弄到第一件就会被叫停先吃。 */
    static final int COUNT = 8;
    /**
     * 估算说附近还没看完时最多等几刻再排先后：冷索引大半径的视野扫描要几秒到十几秒才补完，
     * 和拿东西的引擎问价等看清现场的窗口一样长；等太久按问得出的排，不一直等着饿着。
     */
    static final int MAX_ESTIMATE_WAIT_TICKS = 400;
    /** 一类口粮估了超过这么多毫秒就记一行，带上这一刻的空气值：实机里估算慢和憋气能直接对上。 */
    private static final long SLOW_ESTIMATE_MILLIS = 50;

    private final ItemNeeds needs;
    private final long budgetTicks;
    private long deadline = -1;
    private Action current;
    private String chosen;
    /** 这一轮已经估过的几类口粮（按 KINDS 的顺序）：估齐了排先后，排完清空，下一轮重估。 */
    private final Map<String, GoalEstimate> estimated = new LinkedHashMap<>();
    /** 从哪一刻起为"附近还没看完"等着；没在等为 -1，挑定一类就清掉。 */
    private long waitingSince = -1;
    /** 已经去弄过、没弄成的几类：换下一类，不在同一类上反复。 */
    private final Set<String> tried = new HashSet<>();
    private Problem lastFailure;

    FetchesFood(ItemNeeds needs, long budgetTicks) {
        this.needs = Objects.requireNonNull(needs, "needs");
        this.budgetTicks = budgetTicks;
    }

    @Override public ActionStatus tick(TickContext context) {
        if (deadline < 0) deadline = context.gameTick() + budgetTicks;
        if (context.gameTick() > deadline) {
            if (current != null) current.close();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    "弄吃的过了 " + budgetTicks / 20 + " 秒还没弄到" + (chosen == null ? "" : "（在弄" + chosen + "）"),
                    "给角色一些普通食物"));
        }
        if (current == null) {
            // 每刻只估一类：这一轮还有没估的就估下一类，没估齐这一刻到此为止，下一刻接着估。
            Optional<String> next = nextToEstimate();
            if (next.isPresent()) {
                long started = System.nanoTime();
                estimated.put(next.get(), needs.estimate(requestFor(next.get()).just(1), Permissions.DEFAULT));
                logSlowEstimate(next.get(), (System.nanoTime() - started) / 1_000_000, context);
                if (nextToEstimate().isPresent()) return ActionStatus.running();
            }
            boolean waitedOut = waitingSince >= 0 && context.gameTick() - waitingSince >= MAX_ESTIMATE_WAIT_TICKS;
            Pick pick = nearestOf(waitedOut);
            estimated.clear();
            // 有的口粮估算说附近还没看完（刚换地方，方块扫描还在补）：和拿东西的引擎问价一样先等它看完再排，
            // 不拿第一步的距离硬排——那会让"身边放熔炉"0 格的面包压过 2 格外的羊。等着看清现场算真实进展，
            // 下一轮重估；等有上限，等满了按问得出的排。
            if (pick.stillLooking() && !waitedOut) {
                if (waitingSince < 0) waitingSince = context.gameTick();
                return ActionStatus.progressed();
            }
            Optional<ItemRequest> nearest = pick.request();
            if (nearest.isEmpty()) {
                if (lastFailure != null) return ActionStatus.failed(lastFailure);
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "饿了，身上没有口粮，附近、箱子里、网络里也都没找到现成的面包、熟肉、熟鱼、蔬菜、水果或别的能吃的",
                        "给角色一些普通食物，或种地、养殖"));
            }
            waitingSince = -1;
            tried.add(nearest.get().wanted().specifier());
            chosen = nearest.get().wanted().describe();
            current = needs.actionFor(nearest.get(), Permissions.DEFAULT);
        }
        ActionStatus status = current.tick(context);
        // 这一类问价时说有、去弄却没弄成（报价太乐观）：换下一类，不就此饿着。
        // 换类别只是换个想法再试，身体没动、东西没进包，不算真实进展——算进展会让停滞守卫永不熔断。
        if (status instanceof ActionStatus.Failed failed) {
            current.close();
            current = null;
            lastFailure = failed.problem();
            return ActionStatus.running();
        }
        return status;
    }

    /** 挑口粮的结果：挑中的那一类（哪类都没有为空），以及有没有哪类的估算还在等附近看完。 */
    private record Pick(Optional<ItemRequest> request, boolean stillLooking) {}

    // 这一轮下一类要估的：按 KINDS 的顺序，叫得出名字的几类之后是"能吃的口粮"一大类；去弄过没弄成的不再估。
    private Optional<String> nextToEstimate() {
        List<String> all = new ArrayList<>(KINDS);
        all.add(EverydayFood.TAG);
        return all.stream()
                .filter(kind -> !tried.contains(WantedItem.ofTag(kind).specifier()) && !estimated.containsKey(kind))
                .findFirst();
    }

    private static ItemRequest requestFor(String kind) {
        return new ItemRequest(WantedItem.ofTag(kind), COUNT, "饿了，弄点吃的");
    }

    // 几类叫得出名字的口粮和"能吃的口粮"一大类（含生肉、模组食物）同一轮估齐，挑整条路最远走多远最近的去拿：
    // 面包虽常有小麦合成报价，但远处有羊有鸡时打猎更近，不该绕远去做面包；一样近时按 KINDS 的顺序挑，
    // 熟的、不用再加工的排前面，生肉是饿急了的备选。
    // 排先后只问弄到一件要走多远：弄到第一件就会被叫停先吃，按一次要的件数去估，身边两只羊只够两件、
    // 凑满还得把远处箱子里的算上，整条路最远就被远箱子抬成和面包一样远，近处的羊反而排不到前面。去拿时仍按一次要的件数要。
    // 估得出整条路的（含不用离开的）排在只问得出第一步距离的前面：第一步距离里"身边放熔炉"是 0 格，和真估出来的
    // 0 格打平就会靠排在前面抢走，羊在三格内也去做面包。身边几格就有的已经是最近，不为别类还没看完再等。
    private Pick nearestOf(boolean waitedOut) {
        ItemRequest best = null;
        double bestDistance = Double.MAX_VALUE;
        ItemRequest bestFallback = null;
        double bestFallbackDistance = Double.MAX_VALUE;
        boolean stillLooking = false;
        List<String> considered = new ArrayList<>();
        for (Map.Entry<String, GoalEstimate> entry : estimated.entrySet()) {
            ItemRequest request = requestFor(entry.getKey());
            GoalEstimate estimate = entry.getValue();
            if (estimate.stillLooking() && estimate.farthestBlocks() == null && !waitedOut) {
                stillLooking = true;
                considered.add(request.wanted().describe() + "：附近还没看完，等着（" + estimate.how() + "）");
                continue;
            }
            Optional<Double> firstStep = firstStepBlocks(estimate, request.just(1));
            Optional<Double> distance = walkingDistance(estimate, firstStep);
            considered.add(describeCandidate(request, estimate, firstStep, distance));
            if (distance.isEmpty()) continue;
            if (firstStep.isPresent()) {
                if (distance.get() < bestFallbackDistance) {
                    bestFallback = request;
                    bestFallbackDistance = distance.get();
                }
            } else if (distance.get() < bestDistance) {
                best = request;
                bestDistance = distance.get();
            }
        }
        if (best == null) {
            best = bestFallback;
            bestDistance = bestFallbackDistance;
        } else if (bestDistance == 0) {
            stillLooking = false;
        }
        logPick(best, bestDistance, stillLooking, waitedOut, considered);
        return new Pick(Optional.ofNullable(best), stillLooking);
    }

    // 一类口粮这一轮是怎么算的：估算档位、距离按什么来（整条路最远、不用离开、退回第一步）、算出来几格。
    private static String describeCandidate(ItemRequest request, GoalEstimate estimate, Optional<Double> firstStep,
            Optional<Double> distance) {
        String basis;
        if (estimate.effort() == GoalEstimate.Effort.BLOCKED) basis = "弄不到";
        else if (estimate.effort() == GoalEstimate.Effort.READY) basis = "不用离开";
        else if (estimate.farthestBlocks() != null) basis = "整条路最远";
        else if (firstStep.isPresent()) basis = "估不出整条路，退回第一步距离";
        else basis = "估不出整条路，第一步也问不出";
        return request.wanted().describe() + "：" + estimate.effort().wireName() + "，" + basis
                + distance.map(blocks -> " " + Math.round(blocks) + " 格").orElse("，不参与")
                + "（" + estimate.how() + "）";
    }

    // 估得慢时记一行，带上这一刻的空气：饿了找吃的估算卡住主线程时，水下的角色换不了气。
    private static void logSlowEstimate(String kind, long millis, TickContext context) {
        if (millis <= SLOW_ESTIMATE_MILLIS) return;
        var player = context.player() == null ? null : context.player().localPlayer();
        String air = player == null ? "不知道" : player.getAirSupply() + "/" + player.getMaxAirSupply();
        LOG.info("[弄吃的] 估 {} 用了 {} 毫秒，这一刻空气 {}", kind, millis, air);
    }

    // 挑定或开始等时各写一行，等着的每一刻不重复写：方便实机对照 doing 里选了哪类、为什么。
    private void logPick(ItemRequest best, double bestDistance, boolean stillLooking, boolean waitedOut,
            List<String> considered) {
        if (!LOG.isDebugEnabled()) return;
        // 这一轮会不会继续等：和 tick 里的判断同一条——有的还没看完、等的刻数还没满；已经在等的不重复写。
        boolean waiting = stillLooking && !waitedOut;
        if (waiting && waitingSince >= 0) return;
        String outcome;
        if (waiting) {
            outcome = "有的还没看完，先等" + (best == null ? "" : "（此刻最近的是 " + best.wanted().describe() + "）");
        } else if (best == null) {
            outcome = "哪类都弄不到";
        } else {
            outcome = "选 " + best.wanted().describe() + "（" + Math.round(bestDistance) + " 格，最近"
                    + (waitedOut ? "；等满了按问得出的排" : "") + "）";
        }
        LOG.debug("[弄吃的] {}；各类：{}", outcome, String.join("；", considered));
    }

    // 估不出整条路（附近还没看完、引擎没接上）时才问第一步的距离；估得出、不用离开、弄不到的都不必再问一遍价。
    private Optional<Double> firstStepBlocks(GoalEstimate estimate, ItemRequest request) {
        boolean needed = estimate.farthestBlocks() == null && estimate.effort() != GoalEstimate.Effort.READY
                && estimate.effort() != GoalEstimate.Effort.BLOCKED;
        return needed ? needs.nearestDistanceBlocks(request, Permissions.DEFAULT) : Optional.empty();
    }

    /**
     * 排口粮远近用的距离：角色为这一类口粮实际要走多远。
     * 来源自报代价里的距离只是第一步的（烧炼、合成报"身边放一个"是 0 格），缺的原料要去多远的箱子取不在里面；
     * 按它排，面包链第一步 0 格永远压过 57 格外的羊，角色饿着直奔百格外的深箱子。拿东西的估算会沿原料链往下估，
     * 最远走多远才是整条路的数，排序按它；东西在身上或身边几格的不用离开，按 0 格算；弄不到的不参与；
     * 估不出整条路的（附近还没扫完）退回第一步的距离，不因为估不了就说哪儿都没有。
     */
    static Optional<Double> walkingDistance(GoalEstimate estimate, Optional<Double> firstStepBlocks) {
        if (estimate.effort() == GoalEstimate.Effort.BLOCKED) return Optional.empty();
        if (estimate.effort() == GoalEstimate.Effort.READY) return Optional.of(0d);
        if (estimate.farthestBlocks() != null) return Optional.of(estimate.farthestBlocks());
        return firstStepBlocks;
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String describe() {
        return chosen == null ? "找能弄到的口粮" : "去弄" + chosen;
    }
}
