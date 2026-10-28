// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate.Effort;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 拿东西的估算：只问价不拿，按引擎此刻会走的那条路往下估原料，给 LLM 一份"要花多大功夫"，好自己排先后。
 *
 * <p>挑路和动手时是同一套：问遍来源、按总代价挑、凑够件数；挑中的走不通，就像引擎做砸了一样划掉换下一条。
 * 挑中的是现做时，问来源这次还缺哪几样原料，逐样再估一遍：整件取最重的档位、秒数相加、最远取最大。
 * 原料照引擎备料的规矩问——途径、距离、半径只管最外层那一样，原料问遍所有来源、不出门找。
 *
 * <p>不动手、不记事实、不动世界记忆；附近还没扫完不等，如实说还没看完，要不要等由调用方定。
 * 弄不到的原因按"同一样东西只讲一次"摊平在最外层讲：一层套一层地抄原因，几层下来同一句话会重复很多遍。
 */
final class AcquisitionEstimates {

    private final List<ItemSource> sources;
    private final ReadsCharacterPosition position;
    private final int maxDepth;

    AcquisitionEstimates(List<ItemSource> sources, ReadsCharacterPosition position, int maxDepth) {
        this.sources = List.copyOf(sources);
        this.position = position;
        this.maxDepth = maxDepth;
    }

    /** 估一样东西：限定照最外层的写法，原料不带。 */
    GoalEstimate estimate(ItemRequest request, Permissions permissions, ItemAcquisition.Scope scope) {
        Session session = new Session(permissions);
        Part part = session.estimate(request, scope);
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

    /** 一次估算：许可、正在估的那一串（防转圈、限深）、里层弄不到的原因（摊平讲一次）。 */
    private final class Session {
        private final Permissions permissions;
        private final Deque<String> chain = new ArrayDeque<>();
        private final Map<String, String> blockedWhy = new LinkedHashMap<>();

        Session(Permissions permissions) {
            this.permissions = permissions;
        }

        Part estimate(ItemRequest request, ItemAcquisition.Scope scope) {
            String what = describe(request);
            String key = request.wanted().specifier();
            // 防转圈与限深和引擎一样：同一样东西在一条原料链上出现两次、或层数太深，这一样说估不了，不卡住。
            if (chain.contains(key)) return Part.unknown(what, "原料链转回了它自己", false);
            if (chain.size() >= maxDepth) return Part.unknown(what, "原料链超过 " + maxDepth + " 层", false);
            chain.push(key);
            try {
                return consult(request, scope, what);
            } finally {
                chain.pop();
            }
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
                switch (source.quote(request, context)) {
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
            List<ItemRequest> missing = planned.source().missingIngredients(portion, offer, context);
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
}
