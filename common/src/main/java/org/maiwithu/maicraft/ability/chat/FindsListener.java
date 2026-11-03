// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.maiwithu.maicraft.ability.chat.spi.TalkTo;

/**
 * 点名一只生物说话时找它的听法：观察编号换回那只生物，逐个问联动登记的说话办法它会不会听。
 * 生产实现读场景与世界，测试用替身。
 */
public interface FindsListener {

    /** 什么联动都没接的一份：谁都不会听人说话。 */
    FindsListener NONE = seenId -> new Found.Deaf(seenId, null, null);

    /** 观察编号 e# 指的那只生物此刻会不会听角色说话。 */
    Found find(String seenId);

    /** 找的结果。 */
    sealed interface Found {

        /** 它会听：交给这份听法。 */
        record Listening(TalkTo.Listener listener) implements Found {}

        /** 编号对应的生物已经不在了（走远、死了、被收起来）。 */
        record Gone() implements Found {}

        /**
         * 它不会听人说话。
         *
         * @param what         它是什么：名字与实体类型
         * @param modId        它所属的模组（实体类型的命名空间）；原版生物为 minecraft
         * @param unregistered 这个模组装了、联动却没登记上时为什么；不是这种情况为 null
         */
        record Deaf(String what, String modId, String unregistered) implements Found {}
    }
}
