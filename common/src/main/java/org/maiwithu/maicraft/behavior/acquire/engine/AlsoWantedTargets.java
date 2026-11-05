// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 一次拿东西顺路取货的那几样（同一次要货里还缺的另外几样）各要拿到几件：第一次推进时按"身上原有加还缺的"定目标，
 * 之后按身上的重新算还缺几件，已经拿够的不再交给来源，免得开箱时多拿。
 */
final class AlsoWantedTargets {

    /** 这一趟顺手还要拿的另外几样（同一次要货里还缺的）；没有为空。 */
    private final List<ItemRequest> alsoWanted;
    /** 这次拿东西本身要的那一样：顺路的单子里有同一样时不另定目标。 */
    private final ItemRequest request;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;
    /** 顺手还要的那几样第一次推进时定的目标件数（身上原有加还缺的），按物品写法记。 */
    private final Map<String, Integer> alsoTargets = new LinkedHashMap<>();
    /** 顺手还要的那几样的目标定过没有。 */
    private boolean alsoStarted;

    AlsoWantedTargets(ItemRequest request, List<ItemRequest> alsoWanted, BackpackView backpack,
            OffhandContents offhand, ReadsItemTags tags) {
        this.request = request;
        this.alsoWanted = List.copyOf(alsoWanted);
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
    }

    // 顺手还要的那几样这一刻还缺几件：第一次推进时按"身上原有加还缺的"定目标，之后按身上的重新算。
    // 已经拿够的不再交给来源，免得开箱时多拿。
    List<ItemRequest> remaining() {
        if (!alsoStarted) return List.of();
        List<ItemRequest> pending = new ArrayList<>();
        for (ItemRequest extra : alsoWanted) {
            Integer goal = alsoTargets.get(extra.wanted().specifier());
            if (goal == null) continue;
            int remaining = goal - CarriedItems.matching(backpack, offhand, extra, tags);
            if (remaining > 0) pending.add(extra.just(remaining));
        }
        return pending;
    }

    void start() {
        if (alsoStarted) return;
        alsoStarted = true;
        for (ItemRequest extra : alsoWanted) {
            String key = extra.wanted().specifier();
            if (key.equals(request.wanted().specifier())) continue;
            alsoTargets.putIfAbsent(key,
                    CarriedItems.matching(backpack, offhand, extra, tags) + extra.count());
        }
    }
}
