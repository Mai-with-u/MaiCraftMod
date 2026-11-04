// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;

/**
 * 按产出分好的配方：配方表里每一条做法按做出来的物品归好，问"能做出 X 的有哪些做法"时直接查，
 * 不再每问一次就把整张配方表从头扫一遍。装了几个大模组时配方有上万条，估一次价要问几十次做法，
 * 逐条扫会让角色在原地卡好几秒（水下找吃的时因此淹死过）。
 *
 * <p>一条做法的完整样子（原料要一格格对标签）只在第一次被问到时才拼，拼好留着；按标签问时，
 * 标签下有哪些产出也只算一次。几样产出一起给出时按配方表里的先后排，与逐条扫出来的顺序一样。
 * 配方表换了由读端整份换掉，这里不管失效。标签下有哪些产出、原料按哪个标签写，也跟着这一份留着：
 * 只换标签不换配方时要等下次重分才更新；服务端重载数据包时标签和配方一起重发，实际碰不到。
 */
final class RecipesByResult {

    /**
     * 配方表里的一条做法。
     *
     * @param resultId 做出来的物品 ID
     * @param view     拼出完整做法；拼不出（读不回原料）时给空，这条不算做法
     */
    record Entry(String resultId, Supplier<Optional<WorkstationRecipe>> view) {}

    /** 拼好的一条做法，带着它在配方表里排第几。 */
    private record Placed(int order, WorkstationRecipe recipe) {}

    /** 每样产出的做法，按配方表里的先后；产出本身按第一次出现的先后。 */
    private final Map<String, List<Integer>> ordersByResult = new LinkedHashMap<>();
    private final List<Entry> table;
    /** 拼好的做法，按产出留着。 */
    private final Map<String, List<Placed>> built = new HashMap<>();
    /** 标签下有哪些产出，按标签留着。 */
    private final Map<String, List<String>> resultsByTag = new HashMap<>();

    RecipesByResult(List<Entry> table) {
        this.table = List.copyOf(table);
        for (int order = 0; order < this.table.size(); order++) {
            ordersByResult.computeIfAbsent(this.table.get(order).resultId(), id -> new ArrayList<>()).add(order);
        }
    }

    /**
     * 能做出想要的东西的全部做法，按配方表里的先后。具体物品按 ID 直接查；标签按产出挂着的标签认
     * （标签下有哪些产出第一次问时才算）；点了药水种类的按 ID 对不上，跟逐条比时一样没有做法。
     */
    List<WorkstationRecipe> producing(WantedItem wanted, ReadsItemTags tags) {
        List<String> results = wanted.isTag()
                ? resultsByTag.computeIfAbsent(wanted.tagId(), tag -> ordersByResult.keySet().stream()
                        .filter(id -> wanted.matches(id, tags.tagsOf(id)))
                        .toList())
                : ordersByResult.containsKey(wanted.itemId()) && wanted.matches(wanted.itemId(), tags.tagsOf(wanted.itemId()))
                        ? List.of(wanted.itemId()) : List.of();
        if (results.size() == 1) {
            return built(results.getFirst()).stream().map(Placed::recipe).toList();
        }
        // 几样产出一起给：按配方表里的先后合起来，跟逐条扫时一样先合成、再烧炼、最后锻造。
        List<Placed> merged = new ArrayList<>();
        for (String result : results) merged.addAll(built(result));
        merged.sort(Comparator.comparingInt(Placed::order));
        return merged.stream().map(Placed::recipe).toList();
    }

    // 一样产出的全部做法：第一次问时才逐条拼，拼不出的那条不算，拼好留着。
    private List<Placed> built(String resultId) {
        List<Placed> done = built.get(resultId);
        if (done != null) return done;
        List<Placed> placed = new ArrayList<>();
        for (int order : ordersByResult.getOrDefault(resultId, List.of())) {
            table.get(order).view().get().ifPresent(recipe -> placed.add(new Placed(order, recipe)));
        }
        List<Placed> fixed = List.copyOf(placed);
        built.put(resultId, fixed);
        return fixed;
    }
}
