// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.UUID;

import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ModRangedWeapons;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;

/**
 * 自卫与捡回遗物要读的几路：战斗感观、走到、远程武器、捡掉落物、记过的地点与自家人——
 * 被打时退开举盾打回去、打完捡回这一仗掉出来的东西、撤离往记过的"家"那一侧跑，都从这一组读。
 * 新加一路自卫要读的事实，加在这里并传入所在组即可。
 */
record SurvivalBattle(
        CombatSenses senses,
        WalkTo walks,
        ModRangedWeapons ranged,
        /** 捡掉落物：打完把掉出来的东西和射出去的箭捡回来（用回去捡尸体的同一套）。 */
        PicksUpDrops lostDrops,
        /** 按名字读记过的地点：撤离往记过的"家"那一侧跑。 */
        Supplier<ReadsRememberedPlaces> places,
        /** 自家人：自家人打角色只发事件、不停工。 */
        Predicate<UUID> trusted) {

    SurvivalBattle {
        Objects.requireNonNull(senses, "senses");
        Objects.requireNonNull(walks, "walks");
        Objects.requireNonNull(ranged, "ranged");
        Objects.requireNonNull(lostDrops, "lostDrops");
        Objects.requireNonNull(places, "places");
        Objects.requireNonNull(trusted, "trusted");
    }
}
