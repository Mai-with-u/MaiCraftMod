// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.defense;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import org.maiwithu.maicraft.behavior.combat.ArrowsSpent;
import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.combat.BattleMoves;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 自卫这一场打了什么：打掉了谁、拿什么打、挥中几下、射了几箭、箭少了几支。收场的事件和结果里交代，和点名战斗的结果一样。
 * 实机 10-14 自卫射掉一只骷髅、少了三支箭，事件里只有"自卫结束，位移 1 格"，看不出打了谁、用的什么。
 */
final class DefenseTally {

    /** 出过手、还没见倒下的：实体编号 → 种类。 */
    private final Map<Integer, String> engaged = new LinkedHashMap<>();
    /** 见到倒下的：种类 → 只数。 */
    private final Map<String, Integer> defeated = new LinkedHashMap<>();
    /** 这一场新见到倒下的、还没交给任务记成变化的种类，按先后。 */
    private final List<String> unrecordedDefeats = new ArrayList<>();
    private String weapon = "空手";
    private int hits;
    private int shots;
    /** 箭少了几支：按背包里箭的只数算，和射了几箭各记各的。 */
    private final ArrowsSpent arrows = new ArrowsSpent();

    /** 每刻记一笔：这一刻挥中、射出就记下对着谁和手上拿的什么；出过手的逐个看倒没倒。 */
    void note(TickContext context, Battle.Moment moment, CombatSenses senses, BattleMoves moves) {
        if (moment.struck() || moment.fired()) {
            weapon = moves.mainHand(context);
            if (moment.targetId() >= 0 && !engaged.containsKey(moment.targetId())) {
                Entity target = senses.entityById(context, moment.targetId());
                engaged.put(moment.targetId(), target == null ? "实体 " + moment.targetId()
                        : EntityType.getKey(target.getType()).toString());
            }
        }
        if (moment.struck()) hits++;
        if (moment.fired()) shots++;
        // 箭少了几支按背包数：取消拉弓时被原版松手放出去的箭不会报"射出"，只有背包知道。
        arrows.note(moves.arrowsCarried(context));
        for (Iterator<Map.Entry<Integer, String>> it = engaged.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, String> entry = it.next();
            Entity entity = senses.entityById(context, entry.getKey());
            // 走远了、没了：没见它倒下，不算打掉。
            if (entity == null) {
                it.remove();
            } else if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
                defeated.merge(entry.getValue(), 1, Integer::sum);
                unrecordedDefeats.add(entry.getValue());
                it.remove();
            }
        }
    }

    /** 打掉的，一种一条："minecraft:husk ×2"。 */
    List<String> defeated() {
        List<String> lines = new ArrayList<>();
        defeated.forEach((type, count) -> lines.add(type + " ×" + count));
        return lines;
    }

    String weapon() {
        return weapon;
    }

    int hits() {
        return hits;
    }

    int shots() {
        return shots;
    }

    /** 打完后的那几刻（捡东西、走回打点）接着记箭数：最后一箭的扣减服务器要隔一两刻才同步到背包。 */
    void noteArrows(TickContext context, BattleMoves moves) {
        arrows.note(moves.arrowsCarried(context));
    }

    /** 箭净少了几支；读不到背包为 null。 */
    Integer arrowsSpent() {
        return arrows.spentOrNull();
    }

    /** 离开背包的箭合计（射出去的，不管确认没确认）。 */
    int arrowsTaken() {
        return arrows.taken();
    }

    /** 捡回的箭合计。 */
    int arrowsPickedUp() {
        return arrows.pickedUp();
    }

    /** 上次问过之后新见到倒下的种类（一只一条）：任务拿去记成变化，问过就清。 */
    List<String> takeNewDefeats() {
        List<String> fresh = List.copyOf(unrecordedDefeats);
        unrecordedDefeats.clear();
        return fresh;
    }

    /** 收场事件里的一句；一下没出手、箭也没少（只躲开、只撤离）为空。 */
    String describe() {
        Integer spent = arrows.spentOrNull();
        if (hits == 0 && shots == 0 && (spent == null || spent == 0)) return "";
        String who = defeated.isEmpty() ? "没见打倒哪只" : "打掉了 " + String.join("、", defeated());
        String how = "拿 " + weapon + (hits > 0 ? " 挥中 " + hits + " 下" : "") + (shots > 0 ? " 射了 " + shots + " 箭" : "");
        // 背包的账和射的数对不上时把背包的数也说出来：离开背包几支、捡回几支分开说，读事件的一方一眼能对上
        // （"射了 5 箭"和"净少 3 支"并不矛盾，中间有 2 支捡回来了）。
        if (spent != null && (arrows.taken() != shots || arrows.pickedUp() > 0)) {
            how += "，箭少了 " + arrows.taken() + " 支" + (arrows.pickedUp() > 0 ? "、捡回 " + arrows.pickedUp() + " 支" : "");
        }
        return who + "（" + how + "）；";
    }
}
