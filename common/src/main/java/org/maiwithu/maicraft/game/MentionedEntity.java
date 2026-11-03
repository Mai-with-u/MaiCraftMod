// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.Objects;
import java.util.UUID;

/**
 * 文字里点到的一只实体：死亡播报里死的是谁、被谁打死，进出提示里的玩家，墓碑上写的女仆名。
 * 原版写实体名字时会在那段文字上挂"悬停显示实体"，里面有实体类型与 UUID；这里只记读出来的事实，
 * 宿主拿 UUID 认出是哪一只，不用靠名字猜。
 *
 * @param name 文字里显示的那段名字（团队前缀、命名牌名都照显示的写）；那段文字是空的、悬停里也没写名字时为 null
 * @param type 实体类型注册 ID，例如 minecraft:creeper；只从插入文本认出 UUID、没有悬停信息时为 null
 * @param uuid 这只实体的 UUID
 */
public record MentionedEntity(String name, String type, UUID uuid) {
    public MentionedEntity {
        Objects.requireNonNull(uuid, "uuid");
    }
}
