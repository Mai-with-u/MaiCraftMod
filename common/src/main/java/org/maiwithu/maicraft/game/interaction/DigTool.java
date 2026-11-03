// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.Objects;

/**
 * 目标里点名的挖方块工具：这次挖方块只用点名的这一件，或者空手挖。
 *
 * <p>和许可一样是整件事的公共说明，所有能力都自动获得；不写时照旧按每格代价自动挑。
 * 点名的工具只在真正挖方块时兑现：快捷栏里的选中它，主背包里的换到主手；身上没有就直接说没有，
 * 不换成别的工具，也不因为挖不到掉落、工具被预留而改主意。
 */
public sealed interface DigTool {

    /** 点名用某一件物品挖；itemId 是带命名空间的物品 ID，例如 {@code minecraft:stone_pickaxe}。 */
    record Item(String itemId) implements DigTool {
        public Item {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** 点名空手挖：把手里的东西收起来，腾空主手再挖。 */
    record Hand() implements DigTool {}
}
