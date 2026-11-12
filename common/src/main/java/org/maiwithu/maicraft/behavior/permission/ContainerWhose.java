// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 一只容器是谁的、角色亲手开过没有：观察视图、点开容器的结果与找容器读端共用这一份事实。
 *
 * <p>五个来源取值互斥：方块挂着自然战利品标签（Lootr）时恒为 {@link Kind#NATURAL_LOOT}；
 * 否则看服务端方块归属记录是否已经问到——没问到为 {@link Kind#UNKNOWN}，问到了按记录分角色自己放的
 * （{@link Kind#SELF}）、别的玩家放的（{@link Kind#PLAYER}，附 {@code lastModifiedBy}）、
 * 没有放置记录（{@link Kind#NO_RECORD}，原版自然生成箱或装模组前就在的）。
 *
 * @param whose          容器来源五态
 * @param lastModifiedBy 只在 {@code whose == PLAYER} 时有（在线写名字，不在线写"一位不在线的玩家"）；其余为 null
 * @param openedBySelf   世界记忆里有没有角色亲手打开过这只容器的记录
 */
public record ContainerWhose(Kind whose, String lastModifiedBy, boolean openedBySelf) {

    /** 容器是谁的五种互斥事实。 */
    public enum Kind {
        /** 自然生成的战利品容器（方块挂着 lootr:containers 标签，每人独立一份）。 */
        NATURAL_LOOT,
        /** 归属记录写着是角色自己放的。 */
        SELF,
        /** 归属记录写着是别的玩家放的；是谁见 lastModifiedBy。 */
        PLAYER,
        /** 归属问到了，这一格没有放置记录（原版自然生成，或装模组前就在的）。 */
        NO_RECORD,
        /** 归属还没问到（服务器没装服务端部分，或区块刚进视野回信还没到）。 */
        UNKNOWN
    }

    public ContainerWhose {
        Objects.requireNonNull(whose, "whose");
        if (whose != Kind.PLAYER) {
            lastModifiedBy = null;
        }
    }

    /**
     * 按游戏标签、归属记录与世界记忆断定一只容器的来源。
     *
     * @param position     容器所在格（带维度才能查准区块归属）
     * @param naturalLoot  方块是否挂着自然战利品容器标签
     * @param protection   这个世界的保护与归属读端；不在世界里或未接上时传 null
     * @param openedBySelf 世界记忆里是否已有打开过它的记录
     */
    public static ContainerWhose of(WorldPosition position, boolean naturalLoot, Protection protection,
            boolean openedBySelf) {
        if (naturalLoot) {
            return new ContainerWhose(Kind.NATURAL_LOOT, null, openedBySelf);
        }
        if (protection == null || position == null || !protection.ownershipKnown(position)) {
            return new ContainerWhose(Kind.UNKNOWN, null, openedBySelf);
        }
        if (protection.selfPlaced(position)) {
            return new ContainerWhose(Kind.SELF, null, openedBySelf);
        }
        Optional<String> other = protection.placedByOther(position);
        if (other.isPresent()) {
            return new ContainerWhose(Kind.PLAYER, protection.nameOf(other.get()), openedBySelf);
        }
        return new ContainerWhose(Kind.NO_RECORD, null, openedBySelf);
    }

    /** MCP JSON 协议里的小写枚举值。 */
    public String wire() {
        return whose.name().toLowerCase(Locale.ROOT);
    }

    /** 取材与存物回报里附在容器名后面的说明：别人放的写最后由谁放下，战利品容器写明自然生成。 */
    public String labelSuffix() {
        if (whose == Kind.NATURAL_LOOT) {
            return "（自然生成的战利品容器）";
        }
        if (whose == Kind.PLAYER && lastModifiedBy != null) {
            return "（最后由 " + lastModifiedBy + " 放下）";
        }
        return "";
    }
}
