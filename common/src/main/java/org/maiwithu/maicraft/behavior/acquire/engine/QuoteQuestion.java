// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.List;

import org.maiwithu.maicraft.behavior.acquire.field.DropsFrom;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.StarvingFoodReport;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 一种问法：认"这次问价和以前哪一次是同一种问法"的键，问价记忆全仓只按它存一份。
 * 要什么、几件、偏好与掉落线索、饿急通报、许可、算不算现做、限定，以及做法不拿来当原料的那段外层——
 * 这些任何一样不同，来源的回答就可能不同，不能借用一个答另一个。给不给你想要的，报价只认这些；
 * 用途只是给人看的说明，不影响报价，不进键。问遍各来源的那一种问法不指名来源，{@code source} 为 null。
 */
record QuoteQuestion(ItemSource source, WantedItem wanted, int count, List<String> prefer, DropsFrom dropsFrom,
        StarvingFoodReport foodReport, Permissions permissions, boolean includeMaking,
        ItemAcquisition.Scope scope, List<WantedItem> outerWanted) {

    QuoteQuestion {
        prefer = prefer == null ? List.of() : List.copyOf(prefer);
        outerWanted = outerWanted == null ? List.of() : List.copyOf(outerWanted);
    }

    /** 向一个来源问一次价的问法；问遍各来源的那一种把来源留空、不带限定。 */
    static QuoteQuestion ofSource(ItemSource source, ItemRequest request, Permissions permissions,
            boolean includeMaking, ItemAcquisition.Scope scope, List<WantedItem> outerWanted) {
        return new QuoteQuestion(source, request.wanted(), request.count(), request.prefer(), request.dropsFrom(),
                request.foodReport(), permissions, includeMaking, scope, outerWanted);
    }
}
