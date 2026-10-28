// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 撤离：背离追兵重心挑落点、正后方不能站就偏着找、走不通换方向；甩开只认这次的追兵与新近打过我的。 */
class RetreatTest {

    @Test
    void havenIsAwayFromTheChasers() {
        int[] haven = Retreat.haven(0, 64, 0, 5, 0, 0, cell -> cell.getY() == 64);
        assertEquals(-40, haven[0], "追兵在东，往西四十格");
        assertEquals(0, haven[2]);
    }

    @Test
    void blockedStraightBackFallsBackToTheSides() {
        // 正西那一片是湖：偏一点的方向能站就去那儿；一列里上下四格内找落脚处。
        int[] haven = Retreat.haven(0, 64, 0, 5, 0, 0, cell -> cell.getZ() != 0 && cell.getY() == 62);
        assertTrue(haven[0] < 0 && haven[2] != 0, "偏着往西找，实际 " + haven[0] + "," + haven[2]);
        assertEquals(62, haven[1]);
        assertNull(Retreat.haven(0, 64, 0, 5, 0, 0, cell -> false), "都不能站交给离开追兵的寻路");
        // 第二次走不通从下一个方向试起，不反复撞同一堵墙。
        int[] second = Retreat.haven(0, 64, 0, 5, 0, 1, cell -> cell.getY() == 64);
        assertTrue(second[2] != 0, "换了方向");
    }

    @Test
    void havenLeansTowardHomeWithinTheNinetyDegreesAwayFromTheChasers() {
        // 追兵在东、家在北边一百格：背离追兵的左右九十度里挑离家最近的——往北跑，不往正西越跑越远。
        int[] haven = Retreat.haven(0, 64, 0, 5, 0, 0, cell -> cell.getY() == 64, new double[] {0, -100});
        assertEquals(0, haven[0]);
        assertEquals(-40, haven[2], "往北四十格");
        // 家就在追兵身后（正东）：最多偏到九十度，不往追兵那边钻。
        int[] behind = Retreat.haven(0, 64, 0, 5, 0, 0, cell -> cell.getY() == 64, new double[] {100, 0});
        assertTrue(behind[0] <= 0, "不朝追兵那边跑，实际 " + behind[0] + "," + behind[2]);
        // 这条走不通：按离家远近换下一个。
        int[] next = Retreat.haven(0, 64, 0, 5, 0, 1, cell -> cell.getY() == 64, new double[] {0, -100});
        assertTrue(next[2] < 0 && next[0] < 0, "换到次近的西北方向，实际 " + next[0] + "," + next[2]);
        // 人就站在打点上：八个落点离打点一样远，照旧正背离着跑。
        int[] atAnchor = Retreat.haven(0, 64, 0, 5, 0, 0, cell -> cell.getY() == 64, new double[] {0, 0});
        assertEquals(-40, atAnchor[0]);
        assertEquals(0, atAnchor[2]);
    }

    @Test
    void escapeTakesAQuietStretchNobodyLocksOrApproaches() {
        Retreat retreat = new Retreat();
        Battlefield start = field(foe(1, 12, false));
        retreat.begin("血快见底", start);
        assertFalse(retreat.escaped(start));
        // 追兵还在四十格外晃着、这一刻没在追也没够得着：不算锁定，安静期照攒，几秒后如实收场。
        Battlefield lurking = field(lurker(1, 27));
        for (int i = 1; i < Retreat.CALM_TICKS; i++) assertFalse(retreat.escaped(lurking));
        assertTrue(retreat.escaped(lurking), "几秒没人锁定也没怪凑近：脱险");
        // 新近打过我的照样算锁定：安静期清零重数，不因为刚脱险就立刻收手。
        assertFalse(retreat.escaped(field(foe(7, 20, true))), "新近打过我的照样算");
        // 凑进危险半径的（会还手的近战怪在危险半径加余量以内）：还没脱险。
        assertFalse(retreat.escaped(field(foe(8, 5, false))), "怪凑到危险半径边上了：没脱险");
        // 连着走不通到上限才退无可退；身体真走出两格就清账。
        assertFalse(retreat.routeFailed(0, 0));
        assertFalse(retreat.routeFailed(0, 0));
        assertFalse(retreat.routeFailed(5, 0), "走出了两格，重新数");
        assertFalse(retreat.routeFailed(5, 0));
        assertTrue(retreat.routeFailed(5, 0));
    }

    @Test
    void aPursuerHiddenForAMomentIsNotShakenOff() {
        Retreat retreat = new Retreat();
        Battlefield start = field(foe(1, 4, false));
        retreat.begin("手上没有任何武器", start);
        assertFalse(retreat.escaped(start));
        // 拐过墙角看不见了：它最后露面在 4 格外，不算甩开；往它最后露面的反方向跑。
        assertFalse(retreat.escaped(field()), "一时看不见不是甩开");
        assertEquals(4, retreat.pursuersCenter()[0]);
        // 三秒没再露面，记忆放下；再攒够安静期才算脱险。
        for (int i = 0; i < Retreat.REMEMBER_TICKS + Retreat.CALM_TICKS; i++) retreat.escaped(field());
        assertTrue(retreat.escaped(field()), "几秒都没再露面、也没别人凑近才算甩开");
    }

    @Test
    void aPursuerLurkingOutOfRangeDoesNotHoldTheRetreatForever() {
        Retreat retreat = new Retreat();
        retreat.begin("血快见底", field(foe(1, 6, false)));
        // 跑出去二十多格，那只尸壳这一刻没在追（隔着坡）：它既没锁定我也够不着，
        // 安静期攒够就收场交还身体，不让撤离压住主目标。
        Battlefield lurking = field(lurker(1, 27));
        for (int i = 0; i < Retreat.CALM_TICKS; i++) retreat.escaped(lurking);
        assertTrue(retreat.escaped(lurking));
    }

    @Test
    void aRangedPursuerBeyondItsRangeIsShakenOffWithoutFleeingTheNight() {
        Retreat retreat = new Retreat();
        // 骷髅在三十格外：超出它射程（15 格）加余量，它亮着攻击标记也不算锁定，安静期照攒——
        // 不被远程怪追着跑穿两个昼夜。
        Battlefield outOfRange = field(ranged(1, 30, true));
        retreat.begin("够不着还在射我的远程怪，先撤出它的射程", outOfRange);
        for (int i = 0; i < Retreat.CALM_TICKS; i++) retreat.escaped(outOfRange);
        assertTrue(retreat.escaped(outOfRange), "甩出射程几秒没挨新箭：脱险");
        // 还在射程内锁定着：安静期清零，没脱险。
        assertFalse(retreat.escaped(field(ranged(1, 12, true))), "进了射程还在锁定：没脱险");
    }

    private static Battlefield field(Battlefield.Foe... foes) {
        Battlefield.Me me = new Battlefield.Me(0, 64, 0, 0.3, 4, 0, 3, 0, true, false, 3, 0);
        return new Battlefield(me, List.of(foes));
    }

    private static Battlefield.Foe foe(int id, double distance, boolean attacker) {
        CombatSenses.Threat threat = new CombatSenses.Threat(id, UUID.randomUUID(), "minecraft:zombie", distance, 64, 0,
                distance, ThreatAssessment.Kind.MELEE, false, true, true);
        return new Battlefield.Foe(threat, true, attacker, true, false);
    }

    /** 一只在附近晃、既没追也没锁定我的怪（撤离收场不该被它拖着）。 */
    private static Battlefield.Foe lurker(int id, double distance) {
        CombatSenses.Threat threat = new CombatSenses.Threat(id, UUID.randomUUID(), "minecraft:zombie", distance, 64, 0,
                distance, ThreatAssessment.Kind.MELEE, false, false, false);
        return new Battlefield.Foe(threat, false, false, false, false);
    }

    /** 一只远程怪：aggressive 按它亮没亮攻击标记给。 */
    private static Battlefield.Foe ranged(int id, double distance, boolean aggressive) {
        CombatSenses.Threat threat = new CombatSenses.Threat(id, UUID.randomUUID(), "minecraft:skeleton",
                distance, 64, 0, distance, ThreatAssessment.Kind.RANGED, false, true, aggressive);
        return new Battlefield.Foe(threat, aggressive, false, true, false);
    }
}
