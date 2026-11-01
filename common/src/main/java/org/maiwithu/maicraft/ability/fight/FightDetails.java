// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 战斗的结果细节。
 *
 * @param weapon      这场实际拿着打的那件
 * @param hits        确认打中的下数
 * @param shots       游戏确认了放箭的箭数（弓、弩、联动远程各算一发）
 * @param arrowsSpent 这一仗箭净少了几支：背包里箭的只数开打与打着时的差，捡回的已抵掉；读不到背包为 null
 * @param arrowsPickedUp 这一仗捡回几支箭（射出去插在近处又捡起来的）
 * @param reachIssue  点名目标里走不到跟前、也射不到的
 * @param retreat     撤离收场时为什么撤；没撤为空
 */
public record FightDetails(List<String> defeated, List<String> lostTrack, boolean fled, String threatVerdict,
                    String weapon, int hits, int shots, Integer arrowsSpent, int arrowsPickedUp,
                    List<String> reachIssue, String retreat,
                    List<String> lootGained, DragonDetails dragon) implements ResultDetails {
    public FightDetails {
        defeated = List.copyOf(defeated);
        lostTrack = List.copyOf(lostTrack);
        reachIssue = List.copyOf(reachIssue);
        lootGained = List.copyOf(lootGained);
    }

    /**
     * 打龙这一节。
     *
     * @param crystalsDestroyed     确认是我射掉的水晶数
     * @param crystalsLeft          没打掉的水晶：在哪、为什么
     * @param crystalsGoneOtherwise 不是我射掉却没了的水晶数
     * @param health                最后看到的龙血量与上限
     * @param defeat                龙死的证据（有几样写几样）
     * @param exitPortal            出口门的坐标与亮没亮
     * @param experience            这一仗得到的经验
     * @param rests                 退开回血了几次
     * @param dodges                躲开龙息、冲撞、火球各几次
     */
    public record DragonDetails(int crystalsDestroyed, List<String> crystalsLeft, int crystalsGoneOtherwise, String health,
                         List<String> defeat, String exitPortal, String experience, int rests, Dodges dodges) {
        public DragonDetails {
            crystalsLeft = List.copyOf(crystalsLeft);
            defeat = List.copyOf(defeat);
        }
    }

    /** 躲开龙息、冲撞、火球各几次。 */
    public record Dodges(int breath, int charge, int fireball) {}
}
