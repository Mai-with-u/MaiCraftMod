// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 身上几件同样的东西都能拿去锻造台升级（两把钻石剑）时挑哪一件：附魔多的先，一样多挑磨损少的。
 * 升级以后附魔、耐久、名字都跟到新物品上，玩家都升级自己那套附魔装备，不拿一把白板剑去换下界合金的。
 */
final class ItemToUpgrade {

    private ItemToUpgrade() {}

    /**
     * 背包里一件能拿去升级的东西。
     *
     * @param slotIndex         它在界面背包侧的第几格
     * @param enchantmentLevels 身上附魔的等级加起来
     * @param damage            已经磨损了多少耐久
     */
    record Candidate(int slotIndex, int enchantmentLevels, int damage) {}

    /** 挑出要升级的那一件；一件都没有时为空。附魔、磨损都一样时取背包里靠前的那件。 */
    static Optional<Candidate> pick(List<Candidate> candidates) {
        return candidates.stream()
                .min(Comparator.comparingInt((Candidate candidate) -> -candidate.enchantmentLevels())
                        .thenComparingInt(Candidate::damage)
                        .thenComparingInt(Candidate::slotIndex));
    }
}
