// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.maid;

import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;

/**
 * 点名一只生物改设置时找她：观察编号换回那只生物，逐个问联动登记的改女仆设置的办法她是不是女仆。
 * 生产实现读场景与世界，测试用替身。
 */
public interface FindsMaid {

    /** 观察编号 e# 指的那只生物此刻是不是女仆。 */
    Found find(String seenId);

    /** 找的结果。 */
    sealed interface Found {

        /** 是女仆：交给这份办法。 */
        record Maid(MaidSettings.Maid maid) implements Found {}

        /** 编号对应的生物已经不在了。 */
        record Gone() implements Found {}

        /**
         * 不是女仆。
         *
         * @param what         它是什么：名字与实体类型
         * @param modId        它所属的模组（实体类型的命名空间）
         * @param unregistered 这个模组装了、联动却没登记上时为什么；不是这种情况为 null
         */
        record NotMaid(String what, String modId, String unregistered) implements Found {}
    }
}
