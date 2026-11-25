// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;

/**
 * 机器多候选配方优选与备料合并纯函数测试：
 * 验证持圆石磨砾石时选圆石而非虫蚀圆石、选主产物而非钻石矿概率副产、同名多格原料备料合并为单条总量。
 */
class MachineRecipeChoiceTest {

    private static final String MILLSTONE = "create:millstone";
    private static final String GRAVEL = "minecraft:gravel";
    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String INFESTED_COBBLESTONE = "minecraft:infested_cobblestone";
    private static final String DIAMOND_ORE = "minecraft:diamond_ore";
    private static final String DIAMOND = "minecraft:diamond";

    private static ShownRecipe millRecipe(String id, String inputItem, List<ShownStack> outputs) {
        return new ShownRecipe(id, "create:milling", "研磨",
                List.of(ShownStack.item(MILLSTONE, "石磨", 1)),
                List.of(ShownIngredient.of(ShownStack.item(inputItem, inputItem, 1))),
                List.of(), outputs, null);
    }

    @Test
    void 持圆石磨砾石时优先选圆石而非排在前面的虫蚀圆石() {
        ShownRecipe infested = millRecipe("test:mill_infested", INFESTED_COBBLESTONE,
                List.of(ShownStack.item(GRAVEL, "砾石", 1)));
        ShownRecipe cobble = millRecipe("test:mill_cobble", COBBLESTONE,
                List.of(ShownStack.item(GRAVEL, "砾石", 1)));
        Map<String, Integer> carried = Map.of(COBBLESTONE, 32);

        ShownRecipe chosen = MachineRecipeChoice.choose(List.of(infested, cobble), GRAVEL, 8,
                wanted -> carried.getOrDefault(wanted.specifier(), 0));

        assertEquals("test:mill_cobble", chosen.recipeId());
    }

    @Test
    void 身上空着时仍优先选常规圆石而非虫蚀圆石与钻石矿副产() {
        ShownRecipe diamondByproduct = millRecipe("test:mill_diamond_ore", DIAMOND_ORE,
                List.of(ShownStack.item(DIAMOND, "钻石", 1),
                        new ShownStack(ShownStack.Kind.ITEM, GRAVEL, "砾石", 1, 0.25)));
        ShownRecipe infested = millRecipe("test:mill_infested", INFESTED_COBBLESTONE,
                List.of(ShownStack.item(GRAVEL, "砾石", 1)));
        ShownRecipe cobble = millRecipe("test:mill_cobble", COBBLESTONE,
                List.of(ShownStack.item(GRAVEL, "砾石", 1)));

        ShownRecipe chosen = MachineRecipeChoice.choose(
                List.of(diamondByproduct, infested, cobble), GRAVEL, 4, wanted -> 0);

        assertEquals("test:mill_cobble", chosen.recipeId());
    }

    @Test
    void 磨圆石或砾石时主产物优先于钻石矿副产() {
        ShownRecipe oreByproduct = millRecipe("test:crush_diamond_ore", DIAMOND_ORE,
                List.of(ShownStack.item(DIAMOND, "钻石", 2),
                        new ShownStack(ShownStack.Kind.ITEM, COBBLESTONE, "圆石", 1, 0.12)));
        ShownRecipe directMain = millRecipe("test:mill_stone", "minecraft:stone",
                List.of(ShownStack.item(COBBLESTONE, "圆石", 1)));

        ShownRecipe chosen = MachineRecipeChoice.choose(
                List.of(oreByproduct, directMain), COBBLESTONE, 1, wanted -> 0);

        assertEquals("test:mill_stone", chosen.recipeId());
    }

    @Test
    void 多格重复同名原料合并为单条总量并保留首次出现顺序() {
        List<FeedPlan.Entry> perSlot = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            perSlot.add(new FeedPlan.Entry("create:andesite_alloy", 1, false));
        }
        perSlot.add(new FeedPlan.Entry("minecraft:oak_planks", 1, false));
        perSlot.add(new FeedPlan.Entry("create:andesite_alloy", 2, false));
        perSlot.add(new FeedPlan.Entry("minecraft:oak_planks", 3, false));

        List<FeedPlan.Entry> consolidated = MachineIngredients.consolidateForGather(perSlot);

        assertEquals(2, consolidated.size());
        assertEquals(new FeedPlan.Entry("create:andesite_alloy", 18, false), consolidated.get(0));
        assertEquals(new FeedPlan.Entry("minecraft:oak_planks", 4, false), consolidated.get(1));
    }

    @Test
    void 多格要求同种原料时先合并需求再扣库存_不把同一份库存重复算两遍() {
        // 配方甲两格各需 1 块圆石（共需 2 块），配方乙只需 1 块花岗岩；角色仅持 1 块圆石和 1 块花岗岩，
        // 应识别出配方甲实际还缺 1 块圆石，优先选随身完全满足的配方乙。
        ShownRecipe twoCobbleSlots = new ShownRecipe("test:two_cobble", "create:milling", "研磨",
                List.of(ShownStack.item(MILLSTONE, "石磨", 1)),
                List.of(ShownIngredient.of(ShownStack.item(COBBLESTONE, "圆石", 1)),
                        ShownIngredient.of(ShownStack.item(COBBLESTONE, "圆石", 1))),
                List.of(), List.of(ShownStack.item(GRAVEL, "砾石", 1)), null);
        ShownRecipe oneGranite = millRecipe("test:one_granite", "minecraft:granite",
                List.of(ShownStack.item(GRAVEL, "砾石", 1)));
        Map<String, Integer> carried = Map.of(COBBLESTONE, 1, "minecraft:granite", 1);

        ShownRecipe chosen = MachineRecipeChoice.choose(List.of(twoCobbleSlots, oneGranite), GRAVEL, 1,
                wanted -> carried.getOrDefault(wanted.specifier(), 0));

        assertEquals("test:one_granite", chosen.recipeId());
    }
}
