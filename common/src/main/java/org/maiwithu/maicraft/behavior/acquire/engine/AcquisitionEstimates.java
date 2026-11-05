// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate.Effort;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拿东西的估算：只问价不拿，按引擎此刻会走的那条路往下估原料，给 LLM 一份"要花多大功夫"，好自己排先后。
 *
 * <p>挑路和动手时是同一套：问遍来源、按总代价挑、凑够件数；挑中的走不通，就像引擎做砸了一样划掉换下一条。
 * 挑中的是现做时，问来源这次还缺哪几样原料，逐样再估一遍：整件取最重的档位、秒数相加、最远取最大。
 * 原料照引擎备料的规矩问——途径、距离、半径只管最外层那一样，原料问遍所有来源、不出门找。
 *
 * <p>不动手、不记事实、不动世界记忆；附近还没扫完不等，如实说还没看完，要不要等由调用方定。
 * 弄不到的原因按"同一样东西只讲一次"摊平在最外层讲：一层套一层地抄原因，几层下来同一句话会重复很多遍。
 *
 * <p>估算在主线程上做，估的时候游戏停着：同一次估算里同一样东西估过就记下结果，原料链别处再缺它直接用；
 * 同一来源对同一请求只问一次价；问价有上限，问满了余下的原料如实说没估完，不让一次估算卡住游戏好几秒。
 */
final class AcquisitionEstimates {

    private static final Logger LOG = LoggerFactory.getLogger(AcquisitionEstimates.class);
    /**
     * 一次估算最多问几次价：向一个来源问一次算一次，做法排先后时为一样原料问遍各来源一遍也算一次。
     * 食物、燃料这类原料链分叉多、配方互相绕回来（小麦与干草块、锭与块），不设上限时一次估算要问上千次价、
     * 主线程卡好几秒，角色原地不动；问满了余下的说没估完，由外面按估得出的排。
     * 装了几个大模组的客户端里实测，热起来后每次 0.1 到 0.5 毫秒，问满 100 次约 50 毫秒以内：
     * 面包、熟牛肉、铁镐都估得完，金胡萝卜这类原料链长的估到一半说没估完。
     */
    static final int MAX_QUOTES = 100;
    /** 估算花的时间超过这么多毫秒就写一行日志：实机卡顿时看得出是哪样东西估得慢、问了多少次价。 */
    private static final long SLOW_ESTIMATE_MILLIS = 50;
    /** 问满了价还没估完的那样原料怎么说。 */
    static final String OUT_OF_QUOTES = "原料链太长，没估完";

    private final List<ItemSource> sources;
    private final ReadsCharacterPosition position;
    private final int maxDepth;
    /** 做法排先后时问原料价走的那一份：估算期间由它记住问过的原料价，问一遍从这次估算的额度里扣一次。 */
    private final PriceAsks priceAsks;

    /** 做法不拿来当原料的那段外层此刻要的东西：估过的、问过的价按它分开记，和动手问价同一个口径。 */
    private final Supplier<List<WantedItem>> outerWanted;

    AcquisitionEstimates(List<ItemSource> sources, ReadsCharacterPosition position, int maxDepth, PriceAsks priceAsks,
            Supplier<List<WantedItem>> outerWanted) {
        this.sources = List.copyOf(sources);
        this.position = position;
        this.maxDepth = maxDepth;
        this.priceAsks = priceAsks;
        this.outerWanted = outerWanted;
    }

    /** 估一样东西：限定照最外层的写法，原料不带。 */
    GoalEstimate estimate(ItemRequest request, Permissions permissions, ItemAcquisition.Scope scope) {
        long started = System.nanoTime();
        QuoteBudget budget = new QuoteBudget(MAX_QUOTES);
        Session session = new Session(permissions, budget);
        // 估的时候游戏停着：排做法时问过的原料价记下来，同一样原料别的做法、别的层再问直接用；
        // 排做法时问原料价也从这次估算的额度里扣，一次报价里问上几十遍也收得住。
        Part part = priceAsks.during(budget, () -> session.estimate(request, scope));
        long millis = (System.nanoTime() - started) / 1_000_000;
        // 估得慢时记一行：哪样东西、多少毫秒、问了几次价、有没有问满，实机卡顿时拿它查是哪样东西拖慢了。
        if (millis > SLOW_ESTIMATE_MILLIS) {
            LOG.info("[估算] {} 用了 {} 毫秒，问价 {} 次（其中排做法时问原料价 {} 遍）{}；最费时的来源：{}",
                    describe(request), millis, budget.used(), budget.ingredientAsks(),
                    budget.refused() > 0 ? "，问满了没估完" : "", session.slowestSources());
        }
        return part.toEstimate(session.blockedWhy);
    }

    /** 物品与件数的写法：minecraft:wooden_sword ×1。 */
    static String describe(ItemRequest request) {
        return request.wanted().specifier() + " ×" + request.count();
    }

    /**
     * 一层的估算结果，最外层再换成对外的估算。
     *
     * @param line 这一层走哪条路的一句（"自己做（附近没有工作台…）"）；弄不到时是"弄不到"
     * @param how  这一层的全部说法：路、缺的原料、定了档位的那样原料怎么来；弄不到时是这一层问过的各来源的原因
     */
    private record Part(Effort effort, String what, String line, String how, double seconds, double farthest,
            boolean stillLooking) {

        static Part blocked(String what, String why) {
            return new Part(Effort.BLOCKED, what, "弄不到", why, 0, 0, false);
        }

        static Part unknown(String what, String why, boolean stillLooking) {
            return new Part(Effort.UNKNOWN, what, why, why, 0, 0, stillLooking);
        }

        GoalEstimate toEstimate(Map<String, String> blockedWhy) {
            String said = how;
            if (effort == Effort.BLOCKED) {
                // 弄不到：这一层的原因之后，把里层弄不到的那几样各讲一次（自己这样已经讲过，不重复）。
                StringBuilder all = new StringBuilder(how);
                blockedWhy.forEach((item, why) -> {
                    if (!item.equals(what)) all.append("；").append(item).append(" 弄不到：").append(why);
                });
                said = all.toString();
            }
            Integer rounded = effort.timed() ? GoalEstimate.roughSeconds(seconds) : null;
            return new GoalEstimate(effort, what, said, farthest > 0 ? farthest : null, rounded, List.of(),
                    stillLooking);
        }
    }

    /**
     * 一次估算：许可、正在估的那一串（防转圈、限深）、里层弄不到的原因（摊平讲一次）、
     * 估过的东西与问过的价（同一次里不重估、不重问）、问了几次价。
     */
    private final class Session {
        private final Permissions permissions;
        private final Deque<String> chain = new ArrayDeque<>();
        private final Map<String, String> blockedWhy = new LinkedHashMap<>();
        private final Map<Asked, Recalled> recalled = new HashMap<>();
        private final Map<Quoted, SourceQuote> quotes = new HashMap<>();
        /** 正在估的每一样往下问到了哪些东西、最深几层：估完并进上一样，决定记下的结果别处能不能直接用。 */
        private final Deque<Trace> traces = new ArrayDeque<>();
        /** 这次估算的问价额度：问来源、排做法时问原料价都从这里扣。 */
        private final QuoteBudget budget;
        /** 各来源问价一共花了多少纳秒：估得慢时日志里说是哪几个来源拖的。 */
        private final Map<String, Long> nanosBySource = new HashMap<>();
        private final Map<String, Integer> quotesBySource = new HashMap<>();
        private final Map<String, Long> slowestQuoteBySource = new HashMap<>();

        Session(Permissions permissions, QuoteBudget budget) {
            this.permissions = permissions;
            this.budget = budget;
        }

        Part estimate(ItemRequest request, ItemAcquisition.Scope scope) {
            String what = describe(request);
            String key = request.wanted().specifier();
            Trace caller = traces.peek();
            if (caller != null) caller.reach(key);
            // 防转圈与限深和引擎一样：同一样东西在一条原料链上出现两次、或层数太深，这一样说估不了，不卡住。
            if (chain.contains(key)) return Part.unknown(what, "原料链转回了它自己", false);
            if (chain.size() >= maxDepth) {
                if (caller != null) caller.depthCut = true;
                return Part.unknown(what, "原料链超过 " + maxDepth + " 层", false);
            }
            // 原料链别处估过同样的东西：只要这回往下走不会碰到不同的转圈与限深，结果和重估一遍一样，直接用。
            Asked asked = new Asked(request, scope, outerWanted.get());
            Recalled earlier = recalled.get(asked);
            if (earlier != null && earlier.fits(chain, maxDepth)) {
                if (caller != null) caller.absorb(earlier.reaches(), earlier.height(), earlier.depthCut(), false);
                return earlier.part();
            }
            Trace trace = new Trace();
            traces.push(trace);
            chain.push(key);
            Part part;
            try {
                part = consult(request, scope, what);
            } finally {
                chain.pop();
                traces.pop();
            }
            // 问满了价的结果是半截的，不记；其余记下这回往下问到了哪些、链上已有的哪几样被问到、最深几层。
            if (!trace.budgetCut) {
                Set<String> outerSeen = trace.reaches.stream().filter(chain::contains).collect(Collectors.toSet());
                recalled.put(asked, new Recalled(part, Set.copyOf(trace.reaches), outerSeen, chain.size(),
                        trace.height, trace.depthCut));
            }
            if (caller != null) caller.absorb(trace.reaches, trace.height, trace.depthCut, trace.budgetCut);
            return part;
        }

        // 最费时的三个来源：名字、问了几次、一共几毫秒、最慢的一次几毫秒，按花的时间从多到少。
        String slowestSources() {
            return String.join("；", nanosBySource.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .map(entry -> entry.getKey() + " " + quotesBySource.get(entry.getKey()) + " 次共 "
                            + entry.getValue() / 1_000_000 + " 毫秒（最慢一次 "
                            + slowestQuoteBySource.get(entry.getKey()) / 1_000_000 + " 毫秒）")
                    .toList());
        }

        // 同一来源对同一请求只问一次价；没问过的要新问时先看还剩几次，问满了返回空。
        private SourceQuote quote(ItemSource source, ItemRequest request, ItemAcquisition.Scope scope,
                SourceContext context) {
            Quoted asked = new Quoted(source, request, scope, outerWanted.get());
            SourceQuote known = quotes.get(asked);
            if (known != null) return known;
            if (!budget.takeQuote()) return null;
            int refusedBefore = budget.refused();
            long started = System.nanoTime();
            SourceQuote fresh = source.quote(request, context);
            long spent = System.nanoTime() - started;
            nanosBySource.merge(source.describe(), spent, Long::sum);
            slowestQuoteBySource.merge(source.describe(), spent, Math::max);
            quotesBySource.merge(source.describe(), 1, Integer::sum);
            // 做法类来源报价时给每条候选做法问原料价，中途把额度问光了：这次报价是拿不全的原料价排的，
            // 不作数也不记下，跟问满了一样说没估完。
            if (budget.refused() != refusedBefore) return null;
            quotes.put(asked, fresh);
            return fresh;
        }

        // 问满了价：这一样不再往下估，如实说没估完；它和它上面几层都是半截的，不记下来给别处用。
        private Part outOfQuotes(String what) {
            traces.element().budgetCut = true;
            return Part.unknown(what, OUT_OF_QUOTES, false);
        }

        // 问遍来源 → 按总代价挑出要走的那几处 → 每处往下估原料；挑中的走不通就划掉它再挑，都走不通才说弄不到。
        private Part consult(ItemRequest request, ItemAcquisition.Scope scope, String what) {
            SourceContext context = SourceContext.scoped(position.currentPosition(), permissions,
                    scope.radiusBlocks(), scope.maxDistanceBlocks());
            List<Planned> offers = new ArrayList<>();
            List<String> approvals = new ArrayList<>();
            List<String> refusals = new ArrayList<>();
            List<String> looking = new ArrayList<>();
            for (ItemSource source : sources) {
                if (!scope.allows(source.via().name())) continue;
                SourceQuote quote = quote(source, request, scope, context);
                // 问满了价：这一样不再往下估，如实说没估完；它和它上面几层都是半截的，不记下来给别处用。
                if (quote == null) return outOfQuotes(what);
                switch (quote) {
                    case SourceQuote.Offer offer -> {
                        if (scope.maxDistanceBlocks() != null && offer.cost().distanceBlocks() > scope.maxDistanceBlocks()) {
                            refusals.add(offer.source() + "：在愿意走的 " + blocks(scope.maxDistanceBlocks()) + " 格之外");
                        } else {
                            offers.add(new Planned(source, offer));
                        }
                    }
                    case SourceQuote.Unavailable unavailable -> refusals.add(unavailable.source() + "：" + unavailable.reason());
                    case SourceQuote.NeedsApproval approval -> approvals.add(approval.source() + "：要放开许可（"
                            + approval.problem().message() + "）");
                    // 模组没装、还没实现的来源不算问过，说出来只是噪音。
                    case SourceQuote.Unsupported unsupported -> { }
                    case SourceQuote.NotYet notYet -> looking.add(notYet.source() + "：" + notYet.reason());
                }
            }
            // 有来源还没看清现场：动手时引擎拿着别的报价也先等它看完再挑路，这时挑出来的未必是动手时走的那条，
            // 估算照实说还在看，由外面等一会儿再估。
            if (!looking.isEmpty()) return Part.unknown(what, "附近还没看完：" + String.join("；", looking), true);
            List<String> deadEnds = new ArrayList<>();
            while (true) {
                List<Planned> plan = choose(offers, request.count());
                if (plan.isEmpty()) {
                    List<String> reasons = new ArrayList<>(approvals);
                    reasons.addAll(refusals);
                    reasons.addAll(deadEnds);
                    String why = reasons.isEmpty() ? "没有一个来源能给" : String.join("；", reasons);
                    blockedWhy.putIfAbsent(what, why);
                    return Part.blocked(what, why);
                }
                List<Part> legs = legs(plan, request, context, what);
                Part combined = combine(what, legs, covered(plan), request.count());
                if (combined.effort() != Effort.BLOCKED) return combined;
                // 挑中的有一处走不通：像引擎做砸了一样划掉它，经过记下，再挑下一条。
                for (int i = 0; i < plan.size(); i++) {
                    if (legs.get(i).effort() != Effort.BLOCKED) continue;
                    offers.remove(plan.get(i));
                    deadEnds.add(legs.get(i).how());
                }
            }
        }

        // 挑法和引擎一样：只看报价本身，按总代价排、凑够件数。
        private List<Planned> choose(List<Planned> offers, int count) {
            List<SourceQuote.Offer> chosen = AcquisitionSelector.choose(offers.stream().map(Planned::offer).toList(), count);
            return chosen.stream()
                    .map(offer -> offers.stream().filter(planned -> planned.offer() == offer).findFirst().orElseThrow())
                    .toList();
        }

        // 每一处拿多少：数量明确的拿它报的那么多，最后一处（或数量说不准的）拿剩下的。
        private List<Part> legs(List<Planned> plan, ItemRequest request, SourceContext context, String what) {
            List<Part> legs = new ArrayList<>();
            int remaining = request.count();
            for (Planned planned : plan) {
                int portion = planned.offer().countUnknown() ? remaining
                        : Math.min(remaining, planned.offer().obtainableCount());
                legs.add(leg(planned, request.just(Math.max(1, portion)), context, what));
                remaining = Math.max(0, remaining - portion);
            }
            return legs;
        }

        // 一处来源这一段：要不要出门、走多远、几秒；现做的再往下估缺的原料，整段取最重的档位。
        private Part leg(Planned planned, ItemRequest portion, SourceContext context, String what) {
            SourceQuote.Offer offer = planned.offer();
            double distance = offer.cost().distanceBlocks();
            Effort own = offer.searching() ? Effort.SEARCH : GoalEstimate.byDistance(distance);
            // 总代价是按走路速度折成的格数，除回去就是秒；现做时报价里"缺料折几格"是挑路用的粗算，
            // 估算换成原料自己估出来的秒数。
            double seconds = (offer.cost().total() - offer.cost().shortfallBlocks())
                    / GoalEstimate.BLOCKS_PER_SECOND;
            double farthest = distance;
            boolean stillLooking = false;
            Effort effort = own;
            int refusedBefore = budget.refused();
            List<ItemRequest> missing = planned.source().missingIngredients(portion, offer, context);
            // 列缺料时又排了一遍做法、中途把额度问光了：缺料清单是拿不全的原料价排出来的，不作数，说没估完。
            if (budget.refused() != refusedBefore) return outOfQuotes(what);
            List<Part> parts = new ArrayList<>();
            for (ItemRequest ingredient : missing) {
                Part part = estimate(ingredient, ItemAcquisition.Scope.ALL);
                parts.add(part);
                effort = effort.heavier(part.effort());
                seconds += part.seconds();
                farthest = Math.max(farthest, part.farthest());
                stillLooking |= part.stillLooking();
            }
            String line = line(offer);
            StringBuilder how = new StringBuilder(line);
            if (!missing.isEmpty()) {
                how.append("；缺 ").append(String.join("、", missing.stream().map(AcquisitionEstimates::describe).toList()));
            }
            // 只把定了档位的那一样原料怎么来接着讲：缺木板、木棍都从同一片树来，讲一样就够看出为什么要走过去。
            for (Part part : parts) {
                if (part.effort() == effort && effort.compareTo(own) > 0) {
                    how.append("；").append(part.what()).append("：")
                            .append(part.effort() == Effort.BLOCKED ? "弄不到" : part.how());
                    break;
                }
            }
            return new Part(effort, what, line, how.toString(), seconds, farthest, stillLooking);
        }

        // 几处凑够件数：取最重的档位、秒数相加、最远取最大；几处加起来不够数时如实说还差几件。
        private Part combine(String what, List<Part> legs, int covered, int wanted) {
            if (legs.size() == 1 && covered >= wanted) return legs.getFirst();
            Effort effort = Effort.READY;
            double seconds = 0;
            double farthest = 0;
            boolean stillLooking = false;
            List<String> hows = new ArrayList<>();
            for (Part leg : legs) {
                effort = effort.heavier(leg.effort());
                seconds += leg.seconds();
                farthest = Math.max(farthest, leg.farthest());
                stillLooking |= leg.stillLooking();
                hows.add(leg.how());
            }
            String how = String.join("；另外 ", hows);
            if (covered < wanted) how += "；这几处加起来只够 " + covered + " 件，还差 " + (wanted - covered) + " 件";
            return new Part(effort, what, legs.getFirst().line(), how, seconds, farthest, stillLooking);
        }

        // 挑中的几处里数量明确的一共能给几件；有一处数量说不准就当够数（开了才知道）。
        private int covered(List<Planned> plan) {
            int total = 0;
            for (Planned planned : plan) {
                if (planned.offer().countUnknown()) return Integer.MAX_VALUE;
                total += planned.offer().obtainableCount();
            }
            return total;
        }
    }

    // 这一处怎么拿的一句：来源名，远的带上几格外，再接上来源自己的风险说明（要出去找的说明里已写着最远找多远）。
    private static String line(SourceQuote.Offer offer) {
        StringBuilder line = new StringBuilder(offer.source());
        double distance = offer.cost().distanceBlocks();
        if (!offer.searching() && distance > GoalEstimate.READY_WITHIN_BLOCKS) {
            line.append("（").append(Math.round(distance)).append(" 格外）");
        }
        if (offer.risk() != null && !offer.risk().isBlank()) line.append("：").append(offer.risk());
        return line.toString();
    }

    /** 距离给人看的写法：整数不带小数点。 */
    private static String blocks(double distance) {
        return distance == Math.floor(distance) ? String.valueOf((long) distance) : String.valueOf(distance);
    }

    private record Planned(ItemSource source, SourceQuote.Offer offer) {}

    /** 估过的是哪一样：同样的请求、同样的限定、做法不拿来当原料的同一段外层。 */
    private record Asked(ItemRequest request, ItemAcquisition.Scope scope, List<WantedItem> outerWanted) {}

    /**
     * 问过的是哪一次价：同一来源、同样的请求、同样的限定（限定决定问价时的半径与距离）、
     * 做法不拿来当原料的同一段外层（它决定做法剔掉哪几种）。
     */
    private record Quoted(ItemSource source, ItemRequest request, ItemAcquisition.Scope scope,
            List<WantedItem> outerWanted) {}

    /**
     * 估过一样东西记下的：结果，往下问到过的东西，当时链上已有的几样里被问到的，当时链有多长，往下最深几层，
     * 有没有碰到限深。
     */
    private record Recalled(Part part, Set<String> reaches, Set<String> outerSeen, int depth, int height,
            boolean depthCut) {

        // 这回链上的东西和当时一样地挡住或不挡住往下问到的每一样（转圈判断一样），层数也一样够或不够，
        // 重估一遍得到的就是同一个结果。碰到过限深的只在同样深的地方用。
        boolean fits(Deque<String> chain, int maxDepth) {
            for (String reached : reaches) {
                if (chain.contains(reached) != outerSeen.contains(reached)) return false;
            }
            return depthCut ? chain.size() == depth : chain.size() + height < maxDepth;
        }
    }

    /** 正在估的一样往下的足迹：问到了哪些东西、最深几层、碰没碰到限深、问没问满价。 */
    private static final class Trace {
        private final Set<String> reaches = new HashSet<>();
        private int height;
        private boolean depthCut;
        private boolean budgetCut;

        // 往下问了一样东西（转圈、限深挡回去的也算问到）：至少深一层。
        void reach(String key) {
            reaches.add(key);
            height = Math.max(height, 1);
        }

        // 往下那一样估完了：把它的足迹并进来，深度在它的基础上加一层。
        void absorb(Set<String> childReaches, int childHeight, boolean childDepthCut, boolean childBudgetCut) {
            reaches.addAll(childReaches);
            height = Math.max(height, childHeight + 1);
            depthCut |= childDepthCut;
            budgetCut |= childBudgetCut;
        }
    }
}
