// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

/**
 * 帮打名单：领队身边的敌对生物与打过我的在名单上；玩家不打；离得太远、差得太高的不管；不知道领队在哪只认打过我
 * 的。
 */
class LeaderRosterTest {

    /** 领队站在 (100, 64, 100)。 */
    private static final Vec3 LEADER = new Vec3(100, 64, 100);

    /** 领队水平偏 dx、上下偏 dy 处的一只（或玩家）威胁。 */
    private static CombatSenses.Threat at(double dx, double dy, ThreatAssessment.Kind kind) {
        return new CombatSenses.Threat(1, UUID.randomUUID(),
                kind == ThreatAssessment.Kind.PLAYER ? "minecraft:player" : "minecraft:zombie",
                LEADER.x + dx, LEADER.y + dy, LEADER.z, Math.hypot(dx, dy), kind, false, true, true);
    }

    @Test
    void nearHostileIsWantedAndPrimary() {
        LeaderRoster roster = new LeaderRoster(() -> LEADER);
        CombatSenses.Threat zombie = at(4, 1, ThreatAssessment.Kind.MELEE);
        assertTrue(roster.wanted(zombie, false, false), "领队身边的敌对生物要打");
        assertTrue(roster.primary(zombie), "同样急先打领队身边那只");
    }

    @Test
    void playerIsNeverWanted() {
        LeaderRoster roster = new LeaderRoster(() -> LEADER);
        CombatSenses.Threat player = at(2, 0, ThreatAssessment.Kind.PLAYER);
        assertFalse(roster.wanted(player, true, true), "玩家贴脸打我也不自动还手");
    }

    @Test
    void attackerCountsEvenFarAway() {
        LeaderRoster roster = new LeaderRoster(() -> LEADER);
        CombatSenses.Threat far = at(30, 0, ThreatAssessment.Kind.MELEE);
        assertTrue(roster.wanted(far, true, false), "十秒内真打过我的，再远也接");
        assertFalse(roster.primary(far), "不在这只身上抢先出手");
    }

    @Test
    void farOrHighMobIsIgnored() {
        LeaderRoster roster = new LeaderRoster(() -> LEADER);
        assertFalse(roster.wanted(at(30, 0, ThreatAssessment.Kind.MELEE), false, false), "离领队太远的不管");
        assertFalse(roster.wanted(at(3, 6, ThreatAssessment.Kind.MELEE), false, false), "上下差太多的不管");
        assertFalse(roster.primary(at(30, 0, ThreatAssessment.Kind.MELEE)));
    }

    @Test
    void withoutLeaderOnlyAttackersCount() {
        LeaderRoster roster = new LeaderRoster(() -> null);
        CombatSenses.Threat mob = at(3, 0, ThreatAssessment.Kind.MELEE);
        assertFalse(roster.wanted(mob, false, false), "不知道领队在哪就都算不在身边");
        assertTrue(roster.wanted(mob, true, false), "打过我的照样接");
    }
}
