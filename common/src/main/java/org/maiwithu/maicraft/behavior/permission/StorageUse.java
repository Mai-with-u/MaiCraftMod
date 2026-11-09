// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;

/**
 * 一只箱子、一台终端这类存储能不能拿来取用、存放，不能时是哪一种原因：保护判断
 * {@link Protection#storageUse} 给的只读结论。跳过存储时说明里照它说清是哪一路，不在别处再判断一遍。
 * 归属（谁放的）不在其中：谁放的存储都能取用、存放，是谁放的这个事实由回报另说。
 *
 * @param kind  能用，或不能用的原因
 * @param place 挡住它的地标或记住的区域叫什么（{@link Kind#NEAR_PROTECTED_LANDMARK} 与
 *              {@link Kind#IN_REMEMBERED_REGION}）；别的为 null
 */
public record StorageUse(Kind kind, String place) {

    /** 能用：谁放的都行，只有额外保护的地标旁与记住的区域里的不行。 */
    public static final StorageUse USABLE = new StorageUse(Kind.USABLE, null);

    /** 能不能用；不能用时按判断的先后，命中的是哪一路。 */
    public enum Kind {
        /** 能用。 */
        USABLE,
        /** 在这次任务额外保护的地标旁边。 */
        NEAR_PROTECTED_LANDMARK,
        /** 在记住的区域（圈起来的地盘）里。 */
        IN_REMEMBERED_REGION
    }

    public StorageUse {
        Objects.requireNonNull(kind, "kind");
    }

    /** 被地标或记住的区域挡住的那一只：挡住它的地方叫什么。 */
    static StorageUse inside(Kind kind, String place) {
        return new StorageUse(kind, place);
    }

    public boolean usable() {
        return kind == Kind.USABLE;
    }

    /**
     * 为什么不能用，说成一小句接在说明里，例如"在记住的区域「河东的农场」里"；能用的给空串。
     */
    public String describe() {
        return switch (kind) {
            case USABLE -> "";
            case NEAR_PROTECTED_LANDMARK -> "在这次额外保护的地标「" + place + "」旁";
            case IN_REMEMBERED_REGION -> "在记住的区域「" + place + "」里";
        };
    }
}
