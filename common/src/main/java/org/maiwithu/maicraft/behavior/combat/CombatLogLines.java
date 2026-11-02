// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

/**
 * 战斗引擎写进结果与日志的几句现场话，纯函数：够不着的现场（点名战斗的问题说明与自卫的收场话共用）、
 * 近身打这一刻的判定（只在变化时记一行，实机对得上"站着不挥"卡在哪一项）。
 */
final class CombatLogLines {

    private CombatLogLines() {}

    /**
     * 够不着的现场：它在高处还是低处（差多少格）、水平离几格、看不看得见、看得见时眼睛到它还差几格，再带上为什么
     * 到此为止（已站在能走到的最近处、出手连着没打出去）。
     */
    static String outOfReachScene(Battlefield.Me me, CombatSenses.Threat threat, String why) {
        double dy = threat.y() - me.y();
        double flat = Math.hypot(threat.x() - me.x(), threat.z() - me.z());
        String where;
        if (dy > CombatGeometry.LEVEL_GAP) where = "在高处，高出 " + tenths(dy) + " 格";
        else if (dy < -CombatGeometry.LEVEL_GAP) where = "在低处，低 " + tenths(-dy) + " 格";
        else where = "差不多同一层";
        String sight = threat.visible()
                ? "看得见，眼睛到它还差 " + tenths(Math.max(0, threat.reachGap() - me.reach())) + " 格"
                : "隔着方块看不见";
        return "近战够不着：" + where + "、水平 " + tenths(flat) + " 格，" + sight + "；" + why;
    }

    /**
     * 近身打这一刻的判定：够不够得着（看得见、眼睛到它几格、交互距离几格、高差）、攻击条够不够格、往里走不走、
     * 跳劈的决定、在不在换武器、这一刻挥不挥。
     */
    static String meleeState(Battlefield.Me me, CombatSenses.Threat threat, boolean inReach, int strikeIn,
                             boolean stepIn, JumpStrike.Move jump, boolean swapping, boolean swinging) {
        return String.format("打 %s #%d：看得见 %s，眼睛到它 %.2f 格（交互距离 %.1f），高差 %.1f，够得着 %s；"
                        + "攻击条 %s，往里走 %s，跳劈 %s，换武器中 %s，这一刻挥 %s",
                threat.type(), threat.entityId(), threat.visible(), threat.reachGap(), me.reach(), threat.y() - me.y(),
                inReach, strikeIn == 0 ? "够格" : "冷却中", stepIn, jump, swapping, swinging);
    }

    private static String tenths(double value) {
        return String.format("%.1f", value);
    }
}
