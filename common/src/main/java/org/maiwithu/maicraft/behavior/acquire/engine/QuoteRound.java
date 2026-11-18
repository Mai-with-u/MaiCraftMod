// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;

/**
 * 一轮问价的判断：把各来源对同一次请求的回答分好类，给得了的按总代价排成候选去处。
 * 动手挑路（引擎问遍来源后选路）与估算挑路（只问价不拿）共用这一份判断；
 * 有来源还没看清现场时是等是如实说不知道、做砸过的来源划不划掉，由两条路径各自决定，这里只做纯计算。
 */
final class QuoteRound {

    private QuoteRound() {}

    /** 一个来源对这次问询的回答：判断只看回答本身，动手时按来源找到执行的人。 */
    record Answered(ItemSource source, SourceQuote quote) {}

    /** 排进候选的一处：报价连同报价来自的来源，执行时按它找动手的人。 */
    record Chosen(ItemSource source, SourceQuote.Offer offer) {}

    /**
     * 一轮问价的判断结果：{@code candidates} 是按总代价排好、凑够件数的候选，顺序就是执行的顺序；
     * {@code refusals} 是其余来源没给成的回答，按问的先后排——超出愿意走的距离的报价在原位转成"给不了"。
     */
    record Sorted(List<Chosen> candidates, List<SourceQuote> refusals) {

        Sorted {
            candidates = List.copyOf(candidates);
            refusals = List.copyOf(refusals);
        }

        /** 有来源还没看清现场（回答里带着"再问"）：等不等、怎么向调用方说，由挑路的路径自己定。 */
        boolean stillLooking() {
            return refusals.stream().anyMatch(SourceQuote.NotYet.class::isInstance);
        }
    }

    /**
     * 从一轮回答里挑出值得去试的来源并分好类：
     * 报价上的名字在 {@code excludedOffers} 里的（做砸了划掉的）整条跳过，不进候选也不算给不了；
     * 超出 {@code maxDistanceBlocks} 的报价转成"在愿意走的几格之外"的给不了，挡在候选之外；
     * 余下给不了的按原样收进 refusals，给得了的经 {@link AcquisitionSelector#choose} 排序凑够件数。
     */
    static Sorted sort(List<Answered> answers, int stillNeeded, Double maxDistanceBlocks, Set<String> excludedOffers) {
        List<Answered> offerable = new ArrayList<>();
        List<SourceQuote> refusals = new ArrayList<>();
        for (Answered answered : answers) {
            switch (answered.quote()) {
                // 报价写的来源名和来源自称的不一样时（同一来源分好几路报价），也按报价上的名字认划掉的：
                // 不然做砸了划掉的名字对不上，又来问价、又去跑同一趟，原地转圈。划掉的来源必另有当时的经过。
                case SourceQuote.Offer offer -> {
                    if (excludedOffers.contains(offer.source())) continue;
                    if (maxDistanceBlocks != null && offer.cost().distanceBlocks() > maxDistanceBlocks) {
                        refusals.add(new SourceQuote.Unavailable(offer.source(),
                                "在愿意走的 " + blocks(maxDistanceBlocks) + " 格之外"));
                    } else {
                        offerable.add(answered);
                    }
                }
                default -> refusals.add(answered.quote());
            }
        }
        List<SourceQuote.Offer> chosen = AcquisitionSelector.choose(
                offerable.stream().map(Answered::quote).map(SourceQuote.Offer.class::cast).toList(), stillNeeded);
        List<Chosen> candidates = chosen.stream()
                .map(offer -> offerable.stream()
                        .filter(answered -> answered.quote() == offer)
                        .findFirst().orElseThrow())
                .map(answered -> new Chosen(answered.source(), (SourceQuote.Offer) answered.quote()))
                .toList();
        return new Sorted(candidates, refusals);
    }

    /** 距离给人看的写法：整数不带小数点，例如 50 而不是 50.0。 */
    private static String blocks(double distance) {
        return distance == Math.floor(distance) ? String.valueOf((long) distance) : String.valueOf(distance);
    }
}
