// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * 按游戏自己的食物组件与配方表读物品的数值：能不能吃、补多少、带不带效果、以及是不是能烤熟的生食材，
 * 全都从注册表和当前世界的烧炼/烟熏配方里直接读，不硬编码模组物品名单。
 *
 * <p>查不到的物品 ID（写错、模组没装）如实回答不是食物，不猜。
 */
public final class ClientFoodValues implements ReadsFoodValues {

    private final LocalPlayer player;
    /** 当前配方表里能经烟熏或烧炼变成更高营养无害食物的生食材集合；首次用到时扫描并缓存于本实例。 */
    private Set<Item> cookableRawItems;

    public ClientFoodValues(LocalPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public Optional<FoodValue> of(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return Optional.empty();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty()) return Optional.empty();
        FoodProperties food = new ItemStack(item.get()).get(DataComponents.FOOD);
        if (food == null) return Optional.empty();
        return Optional.of(new FoodValue(itemId, food.nutrition(), food.saturation(),
                food.eatSeconds(), food.canAlwaysEat(), readEffects(food), isRawCookable(item.get())));
    }

    // 把效果列表读成注册 ID 加益害；组件上的效果是"可能获得"，如实列出。
    private List<FoodEffect> readEffects(FoodProperties food) {
        List<FoodEffect> effects = new ArrayList<>();
        for (var possible : food.effects()) {
            MobEffectInstance instance = possible.effect();
            String name = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value()).toString();
            effects.add(new FoodEffect(name, instance.getEffect().value().getCategory() != MobEffectCategory.HARMFUL));
        }
        return List.copyOf(effects);
    }

    // 判断物品是不是待烹饪生食材：依赖当前世界服务端同步的烟熏与烧炼配方，天然覆盖原版与第三方模组生肉生鱼。
    private boolean isRawCookable(Item item) {
        if (cookableRawItems == null) {
            cookableRawItems = scanCookableRawItems(player.level());
        }
        return cookableRawItems.contains(item);
    }

    private static Set<Item> scanCookableRawItems(Level level) {
        if (level == null) return Set.of();
        RecipeManager manager = level.getRecipeManager();
        HolderLookup.Provider registries = level.registryAccess();
        Set<Item> rawItems = new HashSet<>();
        collectCookable(manager.getAllRecipesFor(RecipeType.SMOKING), registries, rawItems);
        collectCookable(manager.getAllRecipesFor(RecipeType.SMELTING), registries, rawItems);
        return Set.copyOf(rawItems);
    }

    private static void collectCookable(Collection<? extends RecipeHolder<? extends AbstractCookingRecipe>> recipes,
            HolderLookup.Provider registries, Set<Item> rawItems) {
        for (RecipeHolder<? extends AbstractCookingRecipe> holder : recipes) {
            AbstractCookingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(registries);
            if (result == null || result.isEmpty()) continue;
            List<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.size() != 1) continue;
            for (ItemStack input : ingredients.getFirst().getItems()) {
                if (upgradesToBetterFood(input, result)) {
                    rawItems.add(input.getItem());
                }
            }
        }
    }

    /**
     * 一条烹饪配方是否把一种可食用生食材升级成了更营养、无害的熟食：
     * 原料本身是食物，产出也是无有害效果的食物且营养值严格更高。
     */
    static boolean upgradesToBetterFood(ItemStack input, ItemStack output) {
        if (input == null || input.isEmpty() || output == null || output.isEmpty()) return false;
        FoodProperties inputFood = input.get(DataComponents.FOOD);
        FoodProperties outputFood = output.get(DataComponents.FOOD);
        if (inputFood == null || outputFood == null) return false;
        if (outputFood.nutrition() <= inputFood.nutrition()) return false;
        return !hasHarmfulEffect(outputFood);
    }

    private static boolean hasHarmfulEffect(FoodProperties food) {
        for (var possible : food.effects()) {
            if (possible.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }
}
