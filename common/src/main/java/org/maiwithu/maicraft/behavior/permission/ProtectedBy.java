// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;

/**
 * 一格方块受不受保护、是哪一路挡的：保护判断 {@link Protection#protectedBy} 给的只读结论。
 * 拒绝时照它说清是哪一路（记住的区域、额外保护的地标），不给一句笼统的"在受保护的地方"，
 * LLM 知道是圈地挡的还是这次任务点名保护挡的。归属（谁放的）不在其中：它只做事实回报，不拦动作。
 *
 * @param route 哪一路挡的；不受保护为 {@link Route#NONE}
 * @param place 挡住它的记住的区域或地标叫什么；只有 {@link Route#IN_REMEMBERED_REGION} 与
 *              {@link Route#NEAR_PROTECTED_LANDMARK} 才有
 */
public record ProtectedBy(Route route, String place) {

    /** 不受保护。 */
    public static final ProtectedBy NONE = new ProtectedBy(Route.NONE, null);

    /** 按判断的先后，命中的是哪一路。 */
    public enum Route {
        /** 不受保护。 */
        NONE,
        /** 在记住的区域（圈起来的地盘）里。 */
        IN_REMEMBERED_REGION,
        /** 在这次任务额外保护的地标旁边。 */
        NEAR_PROTECTED_LANDMARK
    }

    public ProtectedBy {
        Objects.requireNonNull(route, "route");
    }

    /** 落在一块记住的区域里、或一个额外保护的地标旁：拒绝时带上那块区域、那个地标叫什么。 */
    static ProtectedBy inside(Route route, String place) {
        return new ProtectedBy(route, place);
    }

    public boolean protects() {
        return route != Route.NONE;
    }

    /**
     * 是哪一路挡的，说成一小句，例如"在记住的玩家地盘「河东的农场」里"；不受保护给空串。
     */
    public String describe() {
        return switch (route) {
            case NONE -> "";
            case IN_REMEMBERED_REGION -> "在记住的玩家地盘「" + place + "」里";
            case NEAR_PROTECTED_LANDMARK -> "在这次额外保护的地标「" + place + "」旁";
        };
    }
}
