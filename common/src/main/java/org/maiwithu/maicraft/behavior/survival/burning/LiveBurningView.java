// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.survival.burning.FireResistancePotions.CarriedPotion;
import org.maiwithu.maicraft.game.player.ClientBackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从真实角色读着火处境：泡没泡在岩浆里、脚格头格是不是火、身上着没着火、防火效果还剩多久、身上有没有防火药水；
 * 逐刻记身体待过的火源，按着火时长属性、血和黄心估这把火够不够烧死。找水、找出火的落脚只在用得上时找。
 */
public final class LiveBurningView implements BurningNeed.View {

    /** 主背包（含快捷栏）的格数：喝药只从这里挑。 */
    private static final int MAIN_SLOTS = 36;

    /** 身体待过的火源：跟着这个角色走，每刻记一次。 */
    private final BurnEstimate.Exposures exposures = new BurnEstimate.Exposures();

    @Override
    public BurningNeed.Reading read(TickContext context) {
        PlayerContext player = context.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        Level level = player == null ? null : player.level();
        // 不在世界里、旁观、创造这类火伤不着的：没有着火处境。
        if (self == null || level == null || self.isSpectator() || self.getAbilities().invulnerable) return null;
        BlockPos feet = self.blockPosition();
        LevelCells cells = new LevelCells(level);
        boolean inLava = self.isInLava();
        boolean inFire = cells.fire(feet) || cells.fire(feet.above());
        boolean onFire = self.isOnFire();
        long tick = player.clientTick();
        exposures.observe(tick, inLava, inFire, onFire);
        if (!inLava && !inFire && !onFire) {
            return new BurningNeed.Reading(feet, false, resistanceTicks(self), false, null, false, BurnEstimate.NONE,
                    null, level.dimensionType().ultraWarm(), null);
        }
        int resistance = resistanceTicks(self);
        BurnEstimate burn = BurnEstimate.of(exposures.exposures(), tick, self.getAttributeValue(Attributes.BURNING_TIME),
                self.getHealth(), self.getAbsorptionAmount());
        BlockPos fireStep = inFire ? BurningSteps.outOfFire(cells, feet).orElse(null) : null;
        // 在陆地上着火才找水：泡在岩浆里先出岩浆，有防火效果时火伤不着。
        BurningSteps.Water water = onFire && !inLava && resistance < BurningNeed.RESISTANCE_LEFT_TICKS
                ? BurningSteps.nearestWater(cells, feet, BurningNeed.WATER_STEPS).orElse(null) : null;
        CarriedPotion potion = FireResistancePotions.pick(carriedPotions(self)).orElse(null);
        return new BurningNeed.Reading(feet, inLava, resistance, inFire, fireStep, onFire, burn, water,
                level.dimensionType().ultraWarm(), potion);
    }

    // 防火效果还剩几刻：没有是 0，无限时长按最大算。
    private static int resistanceTicks(LocalPlayer self) {
        MobEffectInstance effect = self.getEffect(MobEffects.FIRE_RESISTANCE);
        if (effect == null) return 0;
        return effect.isInfiniteDuration() ? Integer.MAX_VALUE : effect.getDuration();
    }

    // 主背包里带药水内容的每一格：能不能喝看按住使用是不是喝的动作，效果照药水内容读。
    private static List<CarriedPotion> carriedPotions(LocalPlayer self) {
        List<CarriedPotion> potions = new ArrayList<>();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            ItemStack stack = self.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            List<String> effects = ClientBackpackView.potionEffectsOf(stack);
            if (effects.isEmpty()) continue;
            potions.add(new CarriedPotion(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getUseAnimation() == UseAnim.DRINK, effects));
        }
        return potions;
    }
}
