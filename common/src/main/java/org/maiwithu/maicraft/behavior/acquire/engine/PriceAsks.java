// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 问价不拿：问遍所有登记的物品来源，记下把一点东西弄进背包最省一条路的总代价（折成格），
 * 并记下这一刻有没有来源还没看清现场（附近的方块还没扫完）。做法排先后、挑可以互换的原料都用它；
 * 不动手、不记事实、不改世界记忆。
 */
final class PriceAsks {

    /** 问远近时，说不准有没有的报价按多走这么多格算：排在看得见、记得有的后面，但不比真远的地方更远。 */
    static final double UNKNOWN_STOCK_BLOCKS = 32;

    private final List<ItemSource> sources;
    private final ReadsCharacterPosition position;

    PriceAsks(List<ItemSource> sources, ReadsCharacterPosition position) {
        this.sources = List.copyOf(sources);
        this.position = position;
    }

    /** 一遍问价的结果：最省一条路的总代价（问不出时为空），以及有没有来源还没看清现场。 */
    record Price(Optional<Double> cost, boolean looking) {}

    /**
     * 问一遍：不算现做时跳过合成、烧炼这类要动手做的来路；只有点名才做的途径（交易）照旧不参与。
     * 还在扫描的来源给不出报价：记下"还没看完"，让问的人分辨"真没有来路"与"只是还没看完"。
     */
    Price ask(ItemRequest request, Permissions permissions, boolean includeMaking) {
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
        return new Price(cheapest, looking);
    }
}
