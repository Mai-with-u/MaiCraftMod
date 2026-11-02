// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;

import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StackKey;

/**
 * 从客户端那份酿造表拼出能酿的每一步。酿造表不交出清单，只能挨个问"这样材料放进这瓶会变成什么"：
 * 拿注册表里每样表认得的酿造材料，对普通、喷溅、滞留三种药水瓶乘每种药水挨个问一遍，变了的就是一步。
 * 表是进世界时按启用的特性建的（模组登记的酿造配方也在里面），换了世界换一份，这里跟着重拼一次。
 */
public final class ClientBrewingReads implements ReadsBrewing {

    /** 瓶位收的三种药水瓶。 */
    private static final List<Item> BOTTLES = List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);

    private final Supplier<PlayerContext> context;
    /** 上回拼的是哪一份表：同一份不重拼，换了世界（表换了一份）再拼。 */
    private PotionBrewing readFrom;
    private List<BrewStep> steps = List.of();

    public ClientBrewingReads(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public List<BrewStep> steps() {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) return List.of();
        PotionBrewing brewing = level.potionBrewing();
        if (brewing != readFrom) {
            steps = read(brewing);
            readFrom = brewing;
        }
        return steps;
    }

    // 每样认得的材料对每种瓶子里的每种药水问一遍：游戏说能酿、酿出来的和放进去的不一样，就记成一步。
    private static List<BrewStep> read(PotionBrewing brewing) {
        List<ItemStack> ingredients = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty() && brewing.isIngredient(stack)) ingredients.add(stack);
        }
        List<Holder.Reference<Potion>> potions = BuiltInRegistries.POTION.holders().toList();
        List<BrewStep> steps = new ArrayList<>();
        for (Item bottle : BOTTLES) {
            for (Holder<Potion> potion : potions) {
                ItemStack input = PotionContents.createItemStack(bottle, potion);
                StackKey from = StackKey.parse(ClientBackpackView.keyOf(input));
                for (ItemStack ingredient : ingredients) {
                    if (!brewing.hasMix(input, ingredient)) continue;
                    ItemStack output = brewing.mix(ingredient, input.copy());
                    if (output.isEmpty()) continue;
                    StackKey to = StackKey.parse(ClientBackpackView.keyOf(output));
                    if (to.equals(from)) continue;
                    steps.add(new BrewStep(from, BuiltInRegistries.ITEM.getKey(ingredient.getItem()).toString(), to));
                }
            }
        }
        return List.copyOf(steps);
    }
}
