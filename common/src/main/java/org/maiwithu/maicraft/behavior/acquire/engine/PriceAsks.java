// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 问价不拿：问遍所有登记的物品来源，记下把一点东西弄进背包最省一条路的总代价（折成格），
 * 并记下这一刻有没有来源还没看清现场（附近的方块还没扫完）。做法排先后、挑可以互换的原料都用它；
 * 不动手、不记事实、不改世界记忆。
 *
 * <p>问价记忆只有一份，按 {@code QuoteQuestion} 一种问法一条：估算期间、动手问价那一轮里问过的价记下来，
 * 同一种问法不再把来源问第二遍。两个使用者各自带自己的用法：估算问遍来源要扣这次估算的额度、问满了答不出价；
 * 动手问一轮价不扣额度、不限遍数，问得慢时记账写日志。问完（那次估算结束、那一轮问完）就忘掉——
 * 下一刻背包和世界都可能变了，答案要重新问。
 */
final class PriceAsks {

    private static final Logger LOG = LoggerFactory.getLogger(PriceAsks.class);
    /** 问远近时，说不准有没有的报价按多走这么多格算：排在看得见、记得有的后面，但不比真远的地方更远。 */
    static final double UNKNOWN_STOCK_BLOCKS = 32;

    private final List<ItemSource> sources;
    private final ReadsCharacterPosition position;
    /** 估算期间、动手问价那一轮里记下的价，一种问法一条；不在记账期间为 null，每次都问遍来源。 */
    private Map<QuoteQuestion, SourceQuote> remembered;
    /** 正在做的那次估算的问价额度；不在估算里时为 null，不限遍数。 */
    private QuoteBudget budget;
    /** 动手问价那一轮的账：问了谁几次、各花多久、几次用了记下的；不在那一轮里时为 null，不记账。 */
    private QuoteTally tally;

    /** 做法不拿来当原料的那段外层此刻要的东西：同一种问法在这段外层不同的时候，做法挑得出的不一样，分开记。 */
    private final Supplier<List<WantedItem>> outerWanted;

    PriceAsks(List<ItemSource> sources, ReadsCharacterPosition position, Supplier<List<WantedItem>> outerWanted) {
        this.sources = List.copyOf(sources);
        this.position = position;
        this.outerWanted = outerWanted;
    }

    /** 一遍问价的结果：最省一条路的总代价（问不出时为空），以及有没有来源还没看清现场。 */
    record Price(Optional<Double> cost, boolean looking) {}

    /**
     * 一种问法的键：带上此刻做法不拿来当原料的那段外层。问一个来源一次价的键指名来源、带上限定；
     * 问遍各来源的那一种不带这两样。
     */
    QuoteQuestion questionOf(ItemSource source, ItemRequest request, Permissions permissions, boolean includeMaking,
            ItemAcquisition.Scope scope) {
        return QuoteQuestion.ofSource(source, request, permissions, includeMaking, scope, outerWanted.get());
    }

    /** 同一种问法记下的价；不在记账期间、或这种问法还没问过，为 null。 */
    SourceQuote cached(QuoteQuestion question) {
        return remembered == null ? null : remembered.get(question);
    }

    /** 记下一次问价：只在估算期间、动手问价那一轮里有效，那次估算或那一轮问完就忘。 */
    void remember(QuoteQuestion question, SourceQuote quote) {
        if (remembered != null) remembered.put(question, quote);
    }

    /**
     * 一次估算期间：问过的价记下来，问遍各来源一次从这次估算的额度里扣一次；干完就忘掉、不再扣。
     * 估算里套着估算时沿用外层那一份记忆和额度，由外层收尾。
     */
    <T> T during(QuoteBudget estimateBudget, Supplier<T> work) {
        if (remembered != null) return work.get();
        remembered = new HashMap<>();
        budget = estimateBudget;
        try {
            return work.get();
        } finally {
            remembered = null;
            budget = null;
        }
    }

    /**
     * 动手拿东西时问一轮价：引擎问遍各来源挑路，做法类来源为每条候选做法、每样原料回头问价，同一样原料会被问上好几遍。
     * 这一轮在同一刻里同步算完：问过的价记下来，同一种问法只问遍一次来源，不扣额度、不限遍数；问完就忘，
     * 下一轮（下一刻）重新问。这一轮花的时间过了慢日志的线就记一行。
     *
     * @param asked 这一轮要的东西与件数，只用来写日志
     */
    <T> T duringConsult(ItemRequest asked, Supplier<T> work) {
        // 动手问价里不会再套一轮、也不会做估算：真套上了沿用外层那一份记忆和账，由外层收尾。
        if (remembered != null) return work.get();
        remembered = new HashMap<>();
        tally = new QuoteTally(AcquisitionEstimates.describe(asked), System::nanoTime);
        try {
            return work.get();
        } finally {
            // 问得慢时记一行：哪样东西、多少毫秒、问了几次、几次用了记下的、哪个来源拖的，实机卡顿时拿它查。
            long millis = tally.elapsedMillis();
            if (QuoteTally.slow(millis)) LOG.info(tally.report(millis));
            remembered = null;
            tally = null;
        }
    }

    /**
     * 向一个来源问一次价。动手问价那一轮里记下问了谁、花了多久（做法类来源回头问原料价的时间算在它这一次里），
     * 问得慢时说得出是哪个来源拖的；不在那一轮里时直接问，不计时。
     */
    SourceQuote quote(ItemSource source, ItemRequest request, SourceContext context) {
        if (tally == null) return source.quote(request, context);
        // 这一轮问了一秒多还没问完：问这个来源之前先记一行进度，客户端要是卡死在这里，最后一行指得出是卡在哪个来源前面。
        tally.progressBefore(source.describe()).ifPresent(LOG::info);
        long started = System.nanoTime();
        try {
            return source.quote(request, context);
        } finally {
            tally.quoted(source.describe(), System.nanoTime() - started);
        }
    }

    /** 问一个来源一次价，同一种问法先看记下的：记过就不把来源问第二遍，没记过的问完记下来。 */
    private SourceQuote quoteRemembered(ItemSource source, QuoteQuestion question, ItemRequest request,
            SourceContext context) {
        SourceQuote known = cached(question);
        if (known != null) return known;
        SourceQuote fresh = quote(source, request, context);
        remember(question, fresh);
        return fresh;
    }

    /**
     * 问一遍：不算现做时跳过合成、烧炼这类要动手做的来路；只有点名才做的途径（交易）照旧不参与。
     * 还在扫描的来源给不出报价：记下"还没看完"，让问的人分辨"真没有来路"与"只是还没看完"。
     * 最省的总代价按记下的报价现算：一种问法各来源的价都记过时，不再把来源问第二遍也算得出一模一样的结果。
     */
    Price ask(ItemRequest request, Permissions permissions, boolean includeMaking) {
        List<ItemSource> participants = new ArrayList<>();
        List<QuoteQuestion> questions = new ArrayList<>();
        List<WantedItem> outer = outerWanted.get();
        for (ItemSource source : sources) {
            String via = source.via().name();
            if (!ItemAcquisition.Scope.ALL.allows(via) || (!includeMaking && AcquireVia.makes(via))) continue;
            participants.add(source);
            questions.add(QuoteQuestion.ofSource(source, request, permissions, includeMaking, null, outer));
        }
        // 这种问法各来源的价都记过：直接用记下的算，不算又问了一遍。
        boolean allKnown = remembered != null && questions.stream().allMatch(remembered::containsKey);
        if (!allKnown) {
            // 这次估算的额度用完了：不再问遍来源，答不出价；额度那边数下被拒，估算据此说没估完。
            if (budget != null && !budget.takeIngredientAsk()) return new Price(Optional.empty(), false);
            if (tally != null) tally.askedAround();
        } else if (tally != null) {
            tally.recalled();
        }
        SourceContext context = SourceContext.unscoped(position.currentPosition(), permissions);
        Optional<Double> cheapest = Optional.empty();
        boolean looking = false;
        for (int i = 0; i < participants.size(); i++) {
            SourceQuote quote = quoteRemembered(participants.get(i), questions.get(i), request, context);
            if (quote instanceof SourceQuote.Offer offer) {
                double cost = offer.cost().total() + (offer.countUnknown() ? UNKNOWN_STOCK_BLOCKS : 0);
                if (cheapest.isEmpty() || cost < cheapest.get()) cheapest = Optional.of(cost);
            }
            // 还在扫描的来源给不出报价：记下"还没看完"，不把它当成真没有来路。
            if (quote instanceof SourceQuote.NotYet) looking = true;
        }
        return new Price(cheapest, looking);
    }
}
