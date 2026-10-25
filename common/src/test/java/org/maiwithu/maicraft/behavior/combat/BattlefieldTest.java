// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.combat.CombatSenses.Threat;
import org.maiwithu.maicraft.behavior.combat.WeaponChoice.Weapon;

/**
 * 战场快照的取景：我方处境与对面名单怎么从战斗感观折成威胁评估的输入。
 * 开打前的评估、引擎逐刻重估、打法的人数判断都从这几个纯函数取，折法只此一份。
 */
class BattlefieldTest {

    private static Threat zombie(int id, UUID uuid, double distance, boolean visible, boolean aggressive) {
        return new Threat(id, uuid, "minecraft:zombie", 0, 0, 0, distance,
                ThreatAssessment.Kind.MELEE, false, visible, aggressive);
    }

    private static Threat creeper(int id, UUID uuid, double distance, boolean armed) {
        return new Threat(id, uuid, "minecraft:creeper", 0, 0, 0, distance,
                ThreatAssessment.Kind.EXPLOSIVE, armed, true, false);
    }

    @Test
    void mySideFoldsProfileIntoAssessmentInput() {
        // 满铁甲、铁剑、带了吃的：血、甲、武器分、口粮一样不少地折过去；退路由调用方给。
        CombatSenses.CombatProfile profile = new CombatSenses.CombatProfile(20.0, 15,
                Optional.of(new WeaponChoice.Picked("minecraft:iron_sword", Weapon.MELEE)), 3, 3.0);
        ThreatAssessment.MySide side = Battlefield.mySide(profile, true);
        assertEquals(20.0, side.health());
        assertEquals(15, side.armorPoints());
        assertEquals(WeaponChoice.scoreOf(new WeaponChoice.Picked("minecraft:iron_sword", Weapon.MELEE)),
                side.weaponScore());
        assertEquals(3, side.foodCount());
        assertTrue(side.hasEscapeRoute());
    }

    @Test
    void emptyHandsScoreZero() {
        CombatSenses.CombatProfile bare = new CombatSenses.CombatProfile(20.0, 0,
                Optional.empty(), 0, 3.0);
        assertEquals(0, Battlefield.mySide(bare, false).weaponScore());
    }

    @Test
    void foesFoldWithTheSameChasingJudgment() {
        // 打过我的（伤害证据）与引信点着的算在追；只在附近游荡、亮着攻击标记但看不见的不算。
        UUID striker = UUID.randomUUID();
        UUID loiterer = UUID.randomUUID();
        List<ThreatAssessment.Foe> foes = Battlefield.assessedFoes(
                List.of(zombie(1, striker, 3, true, false),
                        creeper(2, UUID.randomUUID(), 5, true),
                        zombie(3, loiterer, 4, false, true)),
                Set.of(striker));
        assertEquals(3, foes.size());
        // 名单按威胁先后排好：点着引信的最前，其次追兵，闲逛的最后。
        assertTrue(foes.get(0).armed() && foes.get(0).chasingMe(), "引信点着的算在追，排最前");
        assertTrue(foes.get(1).chasingMe(), "打过我的算在追");
        assertFalse(foes.get(2).chasingMe(), "隔着墙游荡的不算在追");
        assertEquals(ThreatAssessment.Kind.MELEE, foes.get(2).kind());
    }

    @Test
    void visibleUnlitCreeperNearbyCountsAsChasing() {
        // 近处看得见的没点引信的苦力怕也按在追认：等它开始膨胀再反应就来不及了。
        UUID creeperUuid = UUID.randomUUID();
        List<ThreatAssessment.Foe> foes = Battlefield.assessedFoes(
                List.of(creeper(1, creeperUuid, 5, false)), Set.of());
        assertTrue(foes.get(0).chasingMe());
    }

    @Test
    void foesSortedByThreat() {
        // 点着引信的最前，其次正在追的，同档近的在前。
        UUID striker = UUID.randomUUID();
        List<ThreatAssessment.Foe> foes = Battlefield.assessedFoes(
                List.of(zombie(1, UUID.randomUUID(), 2, true, false),
                        zombie(2, striker, 6, true, false),
                        creeper(3, UUID.randomUUID(), 9, true)),
                Set.of(striker));
        assertTrue(foes.get(0).armed());
        assertTrue(foes.get(1).chasingMe());
        assertFalse(foes.get(2).chasingMe());
    }
}
