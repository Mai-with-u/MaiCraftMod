// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 身上哪瓶药水喝了能防火：按药水内容里带不带防火效果认，不按药水种类的 ID 认。
 * 原版的防火药水、长效版，模组做的药水、带好几种效果其中有防火的，只要是能喝的药水瓶都算；
 * 喷溅药水、滞留药水按住使用是扔出去，不是喝，不算。纯函数，离线可测。
 */
public final class FireResistancePotions {

    /** 防火效果的 ID。 */
    public static final String FIRE_RESISTANCE = "minecraft:fire_resistance";

    private FireResistancePotions() {}

    /**
     * 身上的一格药水。
     *
     * @param slot      在背包第几格（0~35，快捷栏是 0~8）
     * @param itemId    物品 ID
     * @param drinkable 按住使用是不是喝下去（喷溅、滞留药水是扔出去）
     * @param effects   药水内容里带的效果 ID：药水种类自带的加上额外写进去的
     */
    public record CarriedPotion(int slot, String itemId, boolean drinkable, List<String> effects) {
        public CarriedPotion {
            Objects.requireNonNull(itemId, "itemId");
            effects = List.copyOf(effects);
        }
    }

    /** 这一格喝下去能不能防火：能喝，效果里有防火。 */
    public static boolean protects(CarriedPotion potion) {
        return potion.drinkable() && potion.effects().contains(FIRE_RESISTANCE);
    }

    /**
     * 挑一瓶来喝：能防火的里面先挑效果少的——只防火的那瓶先喝，带着别的效果的好药留到后面；一样多时按格子先后。
     * 一瓶都没有时给空。
     */
    public static Optional<CarriedPotion> pick(List<CarriedPotion> carried) {
        return carried.stream()
                .filter(FireResistancePotions::protects)
                .min(Comparator.comparingInt((CarriedPotion potion) -> potion.effects().size())
                        .thenComparingInt(CarriedPotion::slot));
    }
}
