// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.function.Function;

/**
 * 一格方块受不受保护、是哪一路挡的：保护判断 {@link Protection#protectedBy} 给的只读结论。
 * 拒绝时照它说清是哪一路（归属记录写着谁放的、记住的区域、额外保护的地标、推断、归属没问到），
 * 不只给一句笼统的"玩家放的或在玩家的地盘里"，LLM 和主播看得出是记录说的还是猜的。
 *
 * @param route    哪一路挡的；不受保护为 {@link Route#NONE}
 * @param placedBy 归属记录上放它的人的编号；只有 {@link Route#PLACED_BY_OTHER} 才有
 * @param place    挡住它的记住的区域或地标叫什么；只有 {@link Route#IN_REMEMBERED_REGION} 与
 *                 {@link Route#NEAR_PROTECTED_LANDMARK} 才有
 */
public record ProtectedBy(Route route, String placedBy, String place) {

    /** 不受保护。 */
    public static final ProtectedBy NONE = new ProtectedBy(Route.NONE, null, null);

    /** 按判断的先后，命中的是哪一路。 */
    public enum Route {
        /** 不受保护。 */
        NONE,
        /** 这一格所在的区块还没问过服务端归属：不知道是谁放的，按别人的算。 */
        OWNER_UNKNOWN,
        /** 服务端归属记录写着是别人放的。 */
        PLACED_BY_OTHER,
        /** 没有归属记录，在记住的区域（玩家地盘）里。 */
        IN_REMEMBERED_REGION,
        /** 在这次任务额外保护的地标旁边。 */
        NEAR_PROTECTED_LANDMARK,
        /** 没有归属记录，看着像玩家放的（门、活板门、栅栏门）。 */
        LOOKS_PLAYER_MADE
    }

    public ProtectedBy {
        Objects.requireNonNull(route, "route");
    }

    static ProtectedBy placedBy(String playerId) {
        return new ProtectedBy(Route.PLACED_BY_OTHER, playerId, null);
    }

    static ProtectedBy inside(Route route, String place) {
        return new ProtectedBy(route, null, place);
    }

    static ProtectedBy only(Route route) {
        return new ProtectedBy(route, null, null);
    }

    public boolean protects() {
        return route != Route.NONE;
    }

    /**
     * 是哪一路挡的，说成一小句，例如"服务端归属记录写着是 玩家甲 放的""在记住的玩家地盘「河东的农场」里"；不受保护给空串。
     *
     * @param names 玩家编号换成名字：在线写名字，不在线写编号
     */
    public String describe(Function<String, String> names) {
        return switch (route) {
            case NONE -> "";
            case OWNER_UNKNOWN -> "这一带的归属还没问到服务端（不知道是谁放的，按别人的算）";
            case PLACED_BY_OTHER -> "服务端归属记录写着是 " + names.apply(placedBy) + " 放的";
            case IN_REMEMBERED_REGION -> "在记住的玩家地盘「" + place + "」里";
            case NEAR_PROTECTED_LANDMARK -> "在这次额外保护的地标「" + place + "」旁";
            case LOOKS_PLAYER_MADE -> "没有归属记录，看着像玩家放的（门、活板门、栅栏门这类）";
        };
    }
}
