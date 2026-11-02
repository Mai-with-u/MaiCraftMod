// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 手上准备的生产实现：把要用的东西分刻换到主手，像真人一样先做界面搬运再选中快捷栏。
 *
 * <p>标签（# 开头）认身上任意一件挂着这个标签的东西，主手上已经是就不换。
 * 换东西交给换手读端（背包界面与副手交换的原生操作），它分刻推进、每步等游戏确认；
 * 空手就选中一个空着的快捷栏格。身上没有就如实说没有，去拿是任务找拿到物品引擎的事。
 */
final class LiveHandPreparation implements UseSeams.PreparesHand {

    /** 背包里盔甲格的格号：穿在身上的不是拿在手里的东西。 */
    private static final int FIRST_ARMOR_SLOT = 36;
    private static final int LAST_ARMOR_SLOT = 39;

    private final ClientMovesToMainhand toMainhand;
    private final Supplier<PlayerContext> context;

    LiveHandPreparation(ClientMovesToMainhand toMainhand, Supplier<PlayerContext> context) {
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public UseSeams.HandPlan hold(String item) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.WRONG_TIME,
                    "这一刻还掌握不到角色，备不了手", null));
        }
        LocalPlayer player = current.localPlayer();
        if (item == null) {
            return emptyHand(player, toMainhand);
        }
        if (matches(player.getMainHandItem(), item)) {
            return new UseSeams.HandPlan.Ready();
        }
        // 点名了药水种类：换到主手的是写着这种药水的那一格，几瓶长得一样也不拿错。
        if (StackKey.parse(item).potion() != null) {
            OptionalInt slot = carriedSlot(player.getInventory(), item);
            if (slot.isEmpty()) return new UseSeams.HandPlan.NotCarried();
            return toMainhand.actionToMainhandFrom(slot.getAsInt(), "把 " + item + " 换到主手")
                    .<UseSeams.HandPlan>map(UseSeams.HandPlan.Move::new)
                    .orElseGet(() -> new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.STUCK,
                            item + " 在身上却换不到主手", null)));
        }
        Optional<String> carried = carriedOutsideArmor(player.getInventory(), item);
        if (carried.isEmpty()) {
            return wornOnly(player.getInventory(), item)
                    ? new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.NEED_ITEM,
                            item + " 穿在身上，不是拿在手里的东西", "先脱下来或另找一件"))
                    : new UseSeams.HandPlan.NotCarried();
        }
        return toMainhand.actionToMainhand(carried.get())
                .<UseSeams.HandPlan>map(UseSeams.HandPlan.Move::new)
                .orElseGet(() -> new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.STUCK,
                        carried.get() + " 在身上却换不到主手", null)));
    }

    // 要空手：主手已空就成了；不然交给换手读端腾空手（切到空快捷栏格，没有就把主手的东西收进背包），
    // 背包也满了才按腾不出交代。腾空手的办法全仓只有换手读端这一份。
    private static UseSeams.HandPlan emptyHand(LocalPlayer player, ClientMovesToMainhand toMainhand) {
        if (player.getMainHandItem().isEmpty()) {
            return new UseSeams.HandPlan.Ready();
        }
        return toMainhand.emptyMainhand()
                .<UseSeams.HandPlan>map(UseSeams.HandPlan.Move::new)
                .orElseGet(() -> new UseSeams.HandPlan.Cannot(Problem.of(Problem.Kind.INVENTORY_FULL,
                        "腾不出空手：快捷栏和背包都没有空格", "先存起或丢掉一件东西再试")));
    }

    // 身上（主背包、快捷栏、副手）第一件对得上的东西的物品 ID；盔甲格不算。
    private static Optional<String> carriedOutsideArmor(Inventory inventory, String item) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot >= FIRST_ARMOR_SLOT && slot <= LAST_ARMOR_SLOT) continue;
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack, item)) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return Optional.empty();
    }

    // 主背包与快捷栏（0–35）里第一格对得上的；副手的不在这里换，按身上没有处理，交给去拿。
    private static OptionalInt carriedSlot(Inventory inventory, String item) {
        for (int slot = 0; slot < FIRST_ARMOR_SLOT; slot++) {
            if (matches(inventory.getItem(slot), item)) return OptionalInt.of(slot);
        }
        return OptionalInt.empty();
    }

    private static boolean wornOnly(Inventory inventory, String item) {
        for (int slot = FIRST_ARMOR_SLOT; slot <= LAST_ARMOR_SLOT; slot++) {
            if (matches(inventory.getItem(slot), item)) return true;
        }
        return false;
    }

    // 物品 ID 按注册名比；标签按物品自己挂没挂这个标签比，模组加进标签的物品也认；
    // 点名了药水种类的按这一格写着的种类比，和拿东西认药水同一个规矩。
    private static boolean matches(ItemStack stack, String item) {
        if (stack.isEmpty()) return false;
        String spec = item.toLowerCase(Locale.ROOT);
        if (spec.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(spec.substring(1));
            return tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId));
        }
        if (StackKey.parse(spec).potion() != null) {
            return WantedItem.ofItem(spec).matches(ClientBackpackView.keyOf(stack), Set.of());
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(spec);
    }
}
