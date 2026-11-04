// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;

/**
 * 按产出分好的配方：按物品、按标签查得到的做法与逐条扫一样，顺序也一样；同一样产出的做法只拼一次，
 * 标签下有哪些产出只算一次，估一次价问几十遍也不再把整张表从头扫。
 */
class RecipesByResultTest {

    /** 拼过哪些做法：数一下同一条做法拼了几次。 */
    private final List<String> assembled = new ArrayList<>();
    /** 问过哪些物品的标签。 */
    private final List<String> taggedAsked = new ArrayList<>();

    private final ReadsItemTags tags = itemId -> {
        taggedAsked.add(itemId);
        return Map.of(
                "minecraft:oak_planks", Set.of("minecraft:planks"),
                "minecraft:birch_planks", Set.of("minecraft:planks"),
                "minecraft:bread", Set.of("maicraft:foods/edible"),
                "minecraft:cooked_beef", Set.of("maicraft:foods/edible")).getOrDefault(itemId, Set.of());
    };

    private RecipesByResult.Entry entry(String id, WorkstationRecipe.Kind kind, String result) {
        return new RecipesByResult.Entry(result, () -> {
            assembled.add(id);
            return Optional.of(new WorkstationRecipe(id, kind, WantedItem.ofItem(result), 1,
                    List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:stick"), 1))));
        });
    }

    // 配方表的先后：先合成，再烧炼；同一样产出的做法散在表里不同的地方。
    private RecipesByResult table() {
        return new RecipesByResult(List.of(
                entry("oak_planks", WorkstationRecipe.Kind.CRAFTING, "minecraft:oak_planks"),
                entry("bread", WorkstationRecipe.Kind.CRAFTING, "minecraft:bread"),
                entry("birch_planks", WorkstationRecipe.Kind.CRAFTING, "minecraft:birch_planks"),
                entry("beef_furnace", WorkstationRecipe.Kind.SMELTING, "minecraft:cooked_beef"),
                entry("beef_smoker", WorkstationRecipe.Kind.SMOKING, "minecraft:cooked_beef"),
                new RecipesByResult.Entry("minecraft:netherite_sword", Optional::empty)));
    }

    private static List<String> ids(List<WorkstationRecipe> recipes) {
        return recipes.stream().map(WorkstationRecipe::id).toList();
    }

    @Test
    void 按物品查只拼这样产出的做法_问几遍都只拼一次() {
        RecipesByResult byResult = table();

        assertEquals(List.of("beef_furnace", "beef_smoker"), ids(byResult.producing(WantedItem.ofItem("minecraft:cooked_beef"), tags)));
        assertEquals(List.of("beef_furnace", "beef_smoker"), ids(byResult.producing(WantedItem.ofItem("minecraft:cooked_beef"), tags)));

        assertEquals(List.of("beef_furnace", "beef_smoker"), assembled, "只拼熟牛肉的两条，第二遍沿用");
    }

    @Test
    void 按标签查按配方表的先后合起来_标签下有哪些产出只算一次() {
        RecipesByResult byResult = table();

        assertEquals(List.of("bread", "beef_furnace", "beef_smoker"),
                ids(byResult.producing(WantedItem.ofTag("maicraft:foods/edible"), tags)));
        assertEquals(List.of("oak_planks", "birch_planks"), ids(byResult.producing(WantedItem.ofTag("minecraft:planks"), tags)));
        int asked = taggedAsked.size();
        assertEquals(List.of("bread", "beef_furnace", "beef_smoker"),
                ids(byResult.producing(WantedItem.ofTag("maicraft:foods/edible"), tags)));

        assertEquals(asked, taggedAsked.size(), "第二次问同一个标签不再逐样产出问标签");
        assertEquals(5, assembled.size(), "每条做法只拼一次");
    }

    @Test
    void 没有这样产出_拼不出的做法_点了药水种类的都没有做法() {
        RecipesByResult byResult = table();

        assertTrue(byResult.producing(WantedItem.ofItem("minecraft:diamond"), tags).isEmpty());
        assertTrue(byResult.producing(WantedItem.ofItem("minecraft:netherite_sword"), tags).isEmpty(), "读不回原料的那条不算做法");
        assertTrue(byResult.producing(WantedItem.ofPotion("minecraft:bread", "minecraft:healing"), tags).isEmpty(),
                "配方产出不带药水种类，跟逐条比时一样对不上");
    }
}
