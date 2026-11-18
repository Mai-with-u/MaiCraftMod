// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.LinkedHashMap;
import java.util.Map;

import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackView;

/**
 * 捡到的判断：核验"捡到没有"只看背包主格里多了什么。
 *
 * <p>原版拾取掉落物只把东西放进背包主格（副手与护甲栏不吃拾取物），所以追捡地上掉落物时
 * 判断捡没捡到、捡了几件，数的是主背包里这种东西的新增，不含副手——这与"身上有多少"
 * （{@link CarriedItems}，主背包加副手、药水按种类分开）是两种问法：
 * 拾取核验问的是"这一趟捡进了几件"，身上够不够才连副手一起算。按键用物品 ID，
 * 药水不拆种：确认的是"捡到了这种东西"，不是哪种药水。
 */
public final class PickedUpItems {

    private PickedUpItems() {}

    /** 主背包里每样东西各几件（按物品 ID，药水不拆种）：动手前记一份作起点，动手后比一比就是捡到的。 */
    public static Map<String, Integer> byItem(BackpackView backpack) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var stack : backpack.stacks()) {
            counts.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        return counts;
    }

    /** 比起先前那次清点，主背包里多出来的东西各几件；少了的、没变的不算。 */
    public static Map<String, Integer> gainedSince(Map<String, Integer> before, BackpackView backpack) {
        Map<String, Integer> gained = new LinkedHashMap<>();
        byItem(backpack).forEach((itemId, count) -> {
            int more = count - before.getOrDefault(itemId, 0);
            if (more > 0) gained.put(itemId, more);
        });
        return gained;
    }

    /** 主背包里要的东西有几件：去追一个掉落物前记下作起点，之后多出来的是这一处捡到的。 */
    public static int matching(BackpackView backpack, WantedItem wanted, ReadsItemTags tags) {
        int total = 0;
        for (var stack : backpack.stacks()) {
            if (wanted.matches(stack, tags.tagsOf(stack.itemId()))) {
                total += stack.count();
            }
        }
        return total;
    }

    /** 主背包里这种物品有几件：判断一个刚消失的掉落物是不是已经被捡进包里时用。 */
    public static int countOf(BackpackView backpack, String itemId) {
        int count = 0;
        for (var stack : backpack.stacks()) {
            if (stack.itemId().equals(itemId)) count += stack.count();
        }
        return count;
    }
}
