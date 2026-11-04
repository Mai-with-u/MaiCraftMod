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

/**
 * 问价不拿：问遍所有登记的物品来源，记下把一点东西弄进背包最省一条路的总代价（折成格），
 * 并记下这一刻有没有来源还没看清现场（附近的方块还没扫完）。做法排先后、挑可以互换的原料都用它；
 * 不动手、不记事实、不改世界记忆。
 *
 * <p>估算期间同一样东西同一种问法只问一遍：估的时候游戏停着，答案不会变；做法排先后时同一样原料会被
 * 好几条做法、好几层问到，问价没答出来时还要再问一遍"是不是还没看完"，不记下来要把各来源问上好几轮。
 * 估算期间每问遍一次各来源还要从这次估算的额度里扣一次，扣完了不再问、答不出价，由估算说没估完。
 */
final class PriceAsks {

    /** 问远近时，说不准有没有的报价按多走这么多格算：排在看得见、记得有的后面，但不比真远的地方更远。 */
    static final double UNKNOWN_STOCK_BLOCKS = 32;

    private final List<ItemSource> sources;
    private final ReadsCharacterPosition position;
    /** 估算期间问过的价；不在估算里时为 null，每次都问遍来源。 */
    private Map<Question, Price> remembered;
    /** 正在做的那次估算的问价额度；不在估算里时为 null，不限遍数。 */
    private QuoteBudget budget;

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
     * 问一遍：不算现做时跳过合成、烧炼这类要动手做的来路；只有点名才做的途径（交易）照旧不参与。
     * 还在扫描的来源给不出报价：记下"还没看完"，让问的人分辨"真没有来路"与"只是还没看完"。
     */
    Price ask(ItemRequest request, Permissions permissions, boolean includeMaking) {
        Question question = new Question(request.wanted(), request.count(), request.prefer(), request.dropsFrom(),
                permissions, includeMaking);
        // 估算期间问过同一种问法：直接用记下的，不算又问了一遍。
        if (remembered != null) {
            Price known = remembered.get(question);
            if (known != null) return known;
        }
        // 这次估算的额度用完了：不再问遍来源，答不出价；额度那边数下被拒，估算据此说没估完。
        if (budget != null && !budget.takeIngredientAsk()) return new Price(Optional.empty(), false);
        SourceContext context = SourceContext.unscoped(position.currentPosition(), permissions);
        Optional<Double> cheapest = Optional.empty();
        boolean looking = false;
        for (ItemSource source : sources) {
            String via = source.via().name();
            if (!ItemAcquisition.Scope.ALL.allows(via) || (!includeMaking && AcquireVia.makes(via))) continue;
            SourceQuote quote = source.quote(request, context);
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
