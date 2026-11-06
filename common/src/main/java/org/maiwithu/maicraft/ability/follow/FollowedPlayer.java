// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.maiwithu.maicraft.kernel.event.EventFacts;

/**
 * 跟随状况事件说的那位被跟着的玩家，挂在事件的 player 字段上：宿主按 UUID 认出跟的是谁，不用解析句子。
 * 和别的玩家的肢体动作事件的 player 同一个写法；跟的不是玩家时事件上不挂它。
 *
 * @param name 玩家名
 * @param uuid 玩家的 UUID
 */
public record FollowedPlayer(String name, String uuid) implements EventFacts {

    @Override public String field() {
        return "player";
    }
}
