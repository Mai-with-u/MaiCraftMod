// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

/**
 * 跟丢目标的原因：决定怎么找和最后找不到时怎么向调用方说明。
 */
enum LostReason {
    /** 走出视野、传送走了。 */
    OUT_OF_SIGHT("走远了，没找到"),
    /** 他死了。 */
    DIED("他死了，没回来"),
    /** 他下线了。 */
    OFFLINE("他下线了，没回来"),
    /** 跟着过了传送门，那边没看见他。 */
    AFTER_PORTAL("跟着过了传送门，那边没找到他"),
    /** 他进了跟不过去的门。 */
    PORTAL_FAILED("他进了传送门，没跟过去");

    final String ending;

    LostReason(String ending) {
        this.ending = ending;
    }
}
