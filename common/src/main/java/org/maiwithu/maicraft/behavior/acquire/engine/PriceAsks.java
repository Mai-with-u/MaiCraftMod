// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.field.DropsFrom;
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
 * <p>估算期间同一样东西同一种问法只问一遍：估的时候游戏停着，答案不会变；做法排先后时同一样原料会被
 * 好几条做法、好几层问到，问价没答出来时还要再问一遍"是不是还没看完"，不记下来要把各来源问上好几轮。
 * 估算期间每问遍一次各来源还要从这次估算的额度里扣一次，扣完了不再问、答不出价，由估算说没估完。
 *
 * <p>动手拿东西时问一轮价也这样记，但只记这一轮：引擎问遍各来源挑路是在同一刻里同步算完的，这中间背包和世界都没动；
 * 下一刻背包和世界都可能变了，要重新问。这一轮不扣额度、不限遍数，挑法和不记时一样，只是少问重复的；
 * 问得慢时记一行日志，说清要的是什么、问了几次、几次用了记下的、哪个来源拖的。
 */
final class PriceAsks {

    private static final Logger LOG = LoggerFactory.getLogger(PriceAsks.class);
    /** 问远近时，说不准有没有的报价按多走这么多格算：排在看得见、记得有的后面，但不比真远的地方更远。 */
    static final double UNKNOWN_STOCK_BLOCKS = 32;

    private final List<ItemSource> sources;
    private final ReadsCharacterPosition position;
    /** 估算期间、动手问价那一轮里问过的价；都不在时为 null，每次都问遍来源。 */
    private Map<Question, Price> remembered;
    /** 正在做的那次估算的问价额度；不在估算里时为 null，不限遍数。 */
    private QuoteBudget budget;
    /** 动手问价那一轮的账：问了谁几次、各花多久、几次用了记下的；不在那一轮里时为 null，不记账。 */
    private QuoteTally tally;

    PriceAsks(List<ItemSource> sources, ReadsCharacterPosition position) {
        this.sources = List.copyOf(sources);
        this.position = position;
    }

    /** 一遍问价的结果：最省一条路的总代价（问不出时为空），以及有没有来源还没看清现场。 */
    record Price(Optional<Double> cost, boolean looking) {}

    /** 一种问法：要什么、要几件、偏好与掉落线索、许可、算不算现做。用途只是说明，不影响报价。 */
    private record Question(WantedItem wanted, int count, List<String> prefer, DropsFrom dropsFrom,
                            Permissions permissions, boolean includeMaking) {}

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

    /**
     * 问一遍：不算现做时跳过合成、烧炼这类要动手做的来路；只有点名才做的途径（交易）照旧不参与。
     * 还在扫描的来源给不出报价：记下"还没看完"，让问的人分辨"真没有来路"与"只是还没看完"。
     */
    Price ask(ItemRequest request, Permissions permissions, boolean includeMaking) {
        Question question = new Question(request.wanted(), request.count(), request.prefer(), request.dropsFrom(),
                permissions, includeMaking);
        // 估算期间、动手问价那一轮里问过同一种问法：直接用记下的，不算又问了一遍。
        if (remembered != null) {
            Price known = remembered.get(question);
            if (known != null) {
                if (tally != null) tally.recalled();
                return known;
            }
        }
        // 这次估算的额度用完了：不再问遍来源，答不出价；额度那边数下被拒，估算据此说没估完。
        if (budget != null && !budget.takeIngredientAsk()) return new Price(Optional.empty(), false);
        if (tally != null) tally.askedAround();
        SourceContext context = SourceContext.unscoped(position.currentPosition(), permissions);
        Optional<Double> cheapest = Optional.empty();
        boolean looking = false;
        for (ItemSource source : sources) {
            String via = source.via().name();
            if (!ItemAcquisition.Scope.ALL.allows(via) || (!includeMaking && AcquireVia.makes(via))) continue;
            SourceQuote quote = quote(source, request, context);
            if (quote instanceof SourceQuote.Offer offer) {
                double cost = offer.cost().total() + (offer.countUnknown() ? UNKNOWN_STOCK_BLOCKS : 0);
                if (cheapest.isEmpty() || cost < cheapest.get()) cheapest = Optional.of(cost);
            }
            // 还在扫描的来源给不出报价：记下"还没看完"，不把它当成真没有来路。
            if (quote instanceof SourceQuote.NotYet) looking = true;
        }
        Price price = new Price(cheapest, looking);
        if (remembered != null) remembered.put(question, price);
        return price;
    }
}
