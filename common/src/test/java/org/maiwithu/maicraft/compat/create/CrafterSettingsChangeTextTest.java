// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** 机械合成器设置项完成说明测试：格位放料与清空均直接说清实物变化，不笼统报成只改标签。 */
class CrafterSettingsChangeTextTest {

    @Test
    void 放入物品时结果说明明确写清已将该物品放入该格位() {
        assertEquals(Optional.of("已将 minecraft:oak_planks 放入该格位"),
                CrafterSettings.describeChange(CrafterSettings.SLOT, CrafterSettings.EMPTY, "minecraft:oak_planks"));
    }

    @Test
    void 清空格位时结果说明明确写清已取出原物品() {
        assertEquals(Optional.of("已从该格位取出 minecraft:oak_planks（格位已清空）"),
                CrafterSettings.describeChange(CrafterSettings.SLOT, "minecraft:oak_planks", CrafterSettings.EMPTY));
    }

    @Test
    void 箭头指向设置保持默认通用完成说明() {
        assertTrue(CrafterSettings.describeChange(CrafterSettings.POINTING, "up", "right").isEmpty());
    }
}
