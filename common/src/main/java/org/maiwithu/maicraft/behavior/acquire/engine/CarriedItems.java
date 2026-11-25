// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;

/**
 * 身上清点：主背包加副手里，有多少件是这次要的东西。
 * "身上有多少"这一问全仓只有这一处数法，引擎重新清点与各能力看身上够不够都用它；
 * 追捡掉落物核验"这一趟捡到了什么"是另一种问法（只数背包主格的新增），见 {@link PickedUpItems}。
 */
public final class CarriedItems {

    private CarriedItems() {}

    /** 身上（主背包与副手）有多少件请求要的东西。 */
    public static int matching(BackpackView backpack, OffhandContents offhand,
            ItemRequest request, ReadsItemTags tags) {
        return matching(backpack, offhand, request.wanted(), tags);
    }

    /** 身上（主背包与副手）有多少件想要的东西；来源问价与引擎清点共用这一个数法。 */
    public static int matching(BackpackView backpack, OffhandContents offhand,
            WantedItem wanted, ReadsItemTags tags) {
        int total = 0;
        for (var stack : backpack.stacks()) {
            if (wanted.matches(stack, tags.tagsOf(stack.itemId()))) {
                total += stack.count();
            }
        }
        if (offhand != null) {
            total += offhand.heldInOffhand()
                    .filter(stack -> wanted.matches(stack, tags.tagsOf(stack.itemId())))
                    .map(BackpackStack::count)
                    .orElse(0);
        }
        return total;
    }

    /** 身上（主背包与副手）想要的东西按物品分开数（药水按种类分开，键是带种类的物品写法）：要一类（标签）时说得出拿到的是哪几样。 */
    public static Map<String, Integer> matchingByItem(BackpackView backpack, OffhandContents offhand,
            WantedItem wanted, ReadsItemTags tags) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var stack : backpack.stacks()) {
            if (wanted.matches(stack, tags.tagsOf(stack.itemId()))) {
                counts.merge(StackKey.of(stack).written(), stack.count(), Integer::sum);
            }
        }
        if (offhand != null) {
            offhand.heldInOffhand()
                    .filter(stack -> wanted.matches(stack, tags.tagsOf(stack.itemId())))
                    .ifPresent(stack -> counts.merge(StackKey.of(stack).written(), stack.count(), Integer::sum));
        }
        return counts;
    }

    /** 身上（主背包与副手）每样东西各几件（药水按种类分开）：来源做完一趟后比一比，说得出这一趟进包的是什么。 */
    public static Map<String, Integer> byItem(BackpackView backpack, OffhandContents offhand) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var stack : backpack.stacks()) {
            counts.merge(StackKey.of(stack).written(), stack.count(), Integer::sum);
        }
        if (offhand != null) {
            offhand.heldInOffhand().ifPresent(stack -> counts.merge(StackKey.of(stack).written(), stack.count(), Integer::sum));
        }
        return counts;
    }

    /** 比起先前那次清点，身上多出来的东西各几件；少了的、没变的不算。 */
    public static Map<String, Integer> gainedSince(Map<String, Integer> before, BackpackView backpack,
            OffhandContents offhand) {
        Map<String, Integer> gained = new LinkedHashMap<>();
        byItem(backpack, offhand).forEach((itemId, count) -> {
            int more = count - before.getOrDefault(itemId, 0);
            if (more > 0) gained.put(itemId, more);
        });
        return gained;
    }

    /** 身上有没有一件够格的工具：挖矿前先看它，缺了才去准备。 */
    public static boolean hasToolThatSuffices(BackpackView backpack, OffhandContents offhand,
            String blockType, ReadsToolRequirements requirements) {
        for (var stack : backpack.stacks()) {
            if (requirements.sufficient(stack.itemId(), blockType)) return true;
        }
        return offhand != null && offhand.heldInOffhand().isPresent()
                && requirements.sufficient(offhand.heldInOffhand().get().itemId(), blockType);
    }

    /** 背包里同种物品的半堆还能再并进几件，够装下这次还缺的就不用腾空格。 */
    static boolean fitsIntoPartialStacks(BackpackView backpack, ItemRequest request, int stillNeeded) {
        if (request.wanted().isTag() || stillNeeded <= 0) return false;
        String itemId = request.wanted().itemId();
        int mergeable = 0;
        for (BackpackStack stack : backpack.stacks()) {
            if (stack.itemId().equals(itemId)) mergeable += Math.max(0, stack.maxStackSize() - stack.count());
        }
        return mergeable >= stillNeeded;
    }

    /** 这一趟要的东西一件没进包时，把多进包的其他物品拼成说明句；什么都没进返回空串。 */
    static String describeOtherGains(Map<String, Integer> before, BackpackView backpack, OffhandContents offhand,
            ItemRequest request) {
        Map<String, Integer> gained = gainedSince(before, backpack, offhand);
        if (gained.isEmpty()) return "";
        List<String> parts = new ArrayList<>(gained.size());
        gained.forEach((itemId, count) -> parts.add(itemId + " " + count + " 个"));
        return "；进包的是 " + String.join("、", parts) + "，不是要的" + request.wanted().describe();
    }

}
