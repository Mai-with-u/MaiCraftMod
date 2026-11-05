// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import org.maiwithu.maicraft.kernel.event.EventFacts;

/**
 * 肢体动作事件说的那位别的玩家，挂在事件的 player 字段上：宿主按它把事件和身边的人对上，不用解析句子。
 *
 * @param name 玩家名
 * @param uuid 玩家的 UUID
 * @param id   看得见时的观察编号（e#）；看不见为 null
 */
public record NonverbalPlayer(String name, String uuid, String id) implements EventFacts {

    @Override public String field() {
        return "player";
    }
}
