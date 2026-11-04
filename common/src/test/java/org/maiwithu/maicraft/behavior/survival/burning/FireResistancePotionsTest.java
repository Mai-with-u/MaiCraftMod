// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.burning.FireResistancePotions.CarriedPotion;

/** 防火药水按药水内容里的效果认：原版的、长效的、模组的、多效果的都算，扔出去的和不带防火的不算。 */
class FireResistancePotionsTest {

    private static final String FIRE = FireResistancePotions.FIRE_RESISTANCE;

    @Test
    void vanillaAndLongFireResistancePotionsProtect() {
        // 原版防火药水与长效版：药水种类不同，内容里都是防火效果。
        assertTrue(FireResistancePotions.protects(new CarriedPotion(0, "minecraft:potion", true, List.of(FIRE))));
        assertTrue(FireResistancePotions.protects(new CarriedPotion(1, "minecraft:potion", true, List.of(FIRE))));
    }

    @Test
    void aModdedDrinkWithFireResistanceProtects() {
        // 模组的药水：物品 ID、药水种类都不是原版的，内容里带防火就认。
        assertTrue(FireResistancePotions.protects(
                new CarriedPotion(3, "somemod:magma_tonic", true, List.of("somemod:warmth", FIRE))));
    }

    @Test
    void aMultiEffectPotionWithFireResistanceProtects() {
        assertTrue(FireResistancePotions.protects(new CarriedPotion(2, "minecraft:potion", true,
                List.of("minecraft:regeneration", FIRE, "minecraft:speed"))));
    }

    @Test
    void thrownPotionsAndOtherEffectsDoNotProtect() {
        // 喷溅、滞留药水按住使用是扔出去：这次不扔，不算。
        assertFalse(FireResistancePotions.protects(new CarriedPotion(0, "minecraft:splash_potion", false, List.of(FIRE))));
        assertFalse(FireResistancePotions.protects(new CarriedPotion(0, "minecraft:lingering_potion", false, List.of(FIRE))));
        assertFalse(FireResistancePotions.protects(new CarriedPotion(0, "minecraft:potion", true,
                List.of("minecraft:regeneration"))));
        assertFalse(FireResistancePotions.protects(new CarriedPotion(0, "minecraft:potion", true, List.of())));
    }

    @Test
    void picksThePlainestProtectingPotionFirst() {
        // 只防火的那瓶先喝，带着回血的好药留着；扔的那瓶不在挑选范围里。
        CarriedPotion splash = new CarriedPotion(0, "minecraft:splash_potion", false, List.of(FIRE));
        CarriedPotion mixed = new CarriedPotion(1, "minecraft:potion", true, List.of("minecraft:regeneration", FIRE));
        CarriedPotion plain = new CarriedPotion(7, "minecraft:potion", true, List.of(FIRE));
        assertEquals(7, FireResistancePotions.pick(List.of(splash, mixed, plain)).orElseThrow().slot());
        assertEquals(1, FireResistancePotions.pick(List.of(splash, mixed)).orElseThrow().slot());
        assertTrue(FireResistancePotions.pick(List.of(splash)).isEmpty());
    }
}
