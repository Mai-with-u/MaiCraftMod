// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import org.maiwithu.maicraft.kernel.event.EventFacts;

/**
 * 挨打事件说的攻击者，字段直接铺在事件上（不另套一层）：宿主按字段认出攻击者实体，不用解析一句话。
 * 认得出是谁打的才带；摔落、溺水这类没有攻击者的伤不带。
 *
 * @param attacker         攻击者的实体类型 ID，例如 minecraft:skeleton、minecraft:player
 * @param attackerName     攻击者的干净名字（玩家写"玩家 Alex"，有名字写名字，没有写种类名；不带观察编号后缀）
 * @param attackerEntityId 攻击者的游戏实体编号
 */
public record AttackerFacts(String attacker, String attackerName, Integer attackerEntityId) implements EventFacts {

    @Override public String field() {
        return null;
    }
}
