// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;

/**
 * 联动模组交来的远程武器，登记表给它包的一层：模组停用后什么都不认（战斗只用弓弩），
 * 碰到模组接口对不上也在这里收住，同样当作不认。
 */
public final class CompatRangedWeapon implements RangedWeapon {

    private final CompatModule module;
    private final RangedWeapon weapon;

    public CompatRangedWeapon(CompatModule module, RangedWeapon weapon) {
        this.module = Objects.requireNonNull(module, "module");
        this.weapon = Objects.requireNonNull(weapon, "weapon");
    }

    @Override public boolean covers(ItemStack stack) {
        return DegradedAnswer.ask(module, () -> weapon.covers(stack), false);
    }

    @Override public Optional<Shot> nextShot(ItemStack stack, Player player) {
        return DegradedAnswer.ask(module, () -> weapon.nextShot(stack, player), Optional.empty());
    }

    @Override public List<Shot> possibleShots(ItemStack stack, Player player) {
        return DegradedAnswer.ask(module, () -> weapon.possibleShots(stack, player), List.of());
    }

    @Override public boolean usesInternalAmmo() {
        return DegradedAnswer.ask(module, weapon::usesInternalAmmo, false);
    }

    @Override public boolean shotFired(ItemStack before, ItemStack after, Player player) {
        return DegradedAnswer.ask(module, () -> weapon.shotFired(before, after, player), false);
    }

}
