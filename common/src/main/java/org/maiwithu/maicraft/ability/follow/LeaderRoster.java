// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import java.util.function.Supplier;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.combat.Battlefield;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

/**
 * 帮打的名单：领队身边帮打半径内的敌对生物，加上十秒内真打过我的；玩家不打（打不打人由 LLM 定）。
 *
 * <p>这是"跟随场景下谁算可打的"这份判断本体，从战斗引擎的接缝里立出来：半径多少、排除谁都在这里说得清，
 * 离线就能断言；战斗引擎只管照名单打。
 */
final class LeaderRoster implements Battlefield.Roster {

    /** 上下差这么多格以内算"在领队身边"：隔着半层楼的怪也管，再高再低的不管。 */
    static final double LEADER_LEVEL_GAP = 4.0;

    /** 领队此刻脚下的位置；一时不知道他在哪给 null。 */
    private final Supplier<Vec3> leader;

    LeaderRoster(Supplier<Vec3> leader) {
        this.leader = leader;
    }

    // 打不打这只：玩家一律不还手；真打过的怪不管远近都接；其余的要待在领队身边才管。
    @Override
    public boolean wanted(CombatSenses.Threat threat, boolean attacker, boolean chasing) {
        if (threat.kind() == ThreatAssessment.Kind.PLAYER) return false;
        return attacker || nearLeader(threat);
    }

    // 同样急先打领队身边的那只：帮他清场是他叫我来做的事。
    @Override
    public boolean primary(CombatSenses.Threat threat) {
        return nearLeader(threat);
    }

    // 在不在领队身边：水平距离在帮打半径内、上下差不超一层楼的高度；不知道领队在哪就都不算。
    private boolean nearLeader(CombatSenses.Threat threat) {
        Vec3 him = leader.get();
        return him != null
                && Math.hypot(threat.x() - him.x, threat.z() - him.z) <= LiveFollowView.FIGHT_RADIUS
                && Math.abs(threat.y() - him.y) <= LEADER_LEVEL_GAP;
    }
}
