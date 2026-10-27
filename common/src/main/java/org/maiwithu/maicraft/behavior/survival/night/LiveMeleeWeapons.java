// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.night;

import java.util.Comparator;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.combat.WeaponCarriedReader;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 读客户端：身上的近战武器按随身物品读端认；剑、斧按注册表与标签找，武器分和打不打得过用的是同一个分。 */
public final class LiveMeleeWeapons implements ReadsMeleeWeapons {

    @Override public boolean carryingMelee(TickContext context) {
        LocalPlayer self = context == null || context.player() == null ? null : context.player().localPlayer();
        if (self == null) return false;
        return WeaponCarriedReader.read(self).stream().anyMatch(stack -> stack.count() > 0
                && (stack.gear() == WeaponChoice.Gear.AXE || stack.gear() == WeaponChoice.Gear.BLADE));
    }

    // 分高的先问；同分时每秒伤害高的先（石剑先于木剑）。
    @Override public List<String> swordsAndAxesByScore() {
        return WeaponCarriedReader.swordsAndAxes().stream()
                .sorted(Comparator.comparingInt(LiveMeleeWeapons::score).reversed()
                        .thenComparing(Comparator.comparingDouble(WeaponChoice.Carried::rate).reversed()))
                .map(WeaponChoice.Carried::item)
                .toList();
    }

    private static int score(WeaponChoice.Carried weapon) {
        return WeaponChoice.scoreOf(new WeaponChoice.Picked(weapon.item(), WeaponChoice.Weapon.MELEE, -1, weapon.rate()));
    }
}
