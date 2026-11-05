// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.function.Function;

/**
 * 一只箱子、一台终端这类存储能不能拿来取用、存放，不能时是哪一种原因、记在谁名下：保护判断
 * {@link Protection#storageUse} 给的只读结论。跳过别人的存储时说明里照它说清是谁的，不在别处再判断一遍。
 *
 * @param kind     能用，或不能用的原因
 * @param placedBy 归属记录上放它的人的编号；只有 {@link Kind#PLACED_BY_OTHER} 才有，别的为 null
 * @param place    挡住它的地标或记住的区域叫什么；只有 {@link Kind#NEAR_PROTECTED_LANDMARK} 与
 *                 {@link Kind#IN_REMEMBERED_REGION} 才有，别的为 null
 */
public record StorageUse(Kind kind, String placedBy, String place) {

    /** 能用：角色自己或自家人放的，或野外、村庄、遗迹里不属于任何玩家的。 */
    public static final StorageUse USABLE = new StorageUse(Kind.USABLE, null, null);

    /** 能不能用；不能用时按判断的先后，命中的是哪一路。 */
    public enum Kind {
        /** 能用。 */
        USABLE,
        /** 在这次任务额外保护的地标旁边。 */
        NEAR_PROTECTED_LANDMARK,
        /** 归属还没问到服务端：不知道是谁的，按别人的算。 */
        OWNER_UNKNOWN,
        /** 服务端归属记录写着是别人放的（不是角色自己，也不是自家人）。 */
        PLACED_BY_OTHER,
        /** 没有归属记录，但在记住的区域里：那是有人的地盘。 */
        IN_REMEMBERED_REGION,
        /** 没有归属记录、不在记住的区域里，看着像玩家放的。 */
        LOOKS_PLAYER_MADE
    }

    public StorageUse {
        Objects.requireNonNull(kind, "kind");
    }

    /** 记在别人名下的那一只：放它的人的编号。 */
    static StorageUse placedBy(String playerId) {
        return new StorageUse(Kind.PLACED_BY_OTHER, playerId, null);
    }

    /** 被地标或记住的区域挡住的那一只：挡住它的地方叫什么。 */
    static StorageUse inside(Kind kind, String place) {
        return new StorageUse(kind, null, place);
    }

    public boolean usable() {
        return kind == Kind.USABLE;
    }

    /**
     * 饿到危险线、身上和能用的箱子里都没口粮时，能不能从这只拿吃的：别人放的、归属没问到的、记住的区域里的、
     * 看着像玩家放的都能拿；这次任务额外保护的地标旁不行，那是调用方点名保护的，饿了也不越过。
     */
    public boolean usableWhenStarving() {
        return kind != Kind.NEAR_PROTECTED_LANDMARK;
    }

    /**
     * 为什么不能用，说成一小句接在只数后面，例如"记在 bob 名下""不知道是谁的（按别人的算）"；能用的给空串。
     *
     * @param names 玩家编号换成名字：在线写名字，不在线写编号
     */
    public String describe(Function<String, String> names) {
        return switch (kind) {
            case USABLE -> "";
            case NEAR_PROTECTED_LANDMARK -> "在这次额外保护的地标「" + place + "」旁";
            case OWNER_UNKNOWN -> "不知道是谁的（按别人的算）";
            case PLACED_BY_OTHER -> "记在 " + names.apply(placedBy) + " 名下";
            case IN_REMEMBERED_REGION -> "在记住的区域「" + place + "」里";
            case LOOKS_PLAYER_MADE -> "看着像玩家放的（没有归属记录）";
        };
    }
}
