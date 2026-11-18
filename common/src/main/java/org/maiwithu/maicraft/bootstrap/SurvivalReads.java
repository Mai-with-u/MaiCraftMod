// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 生存需求判断处境要读的几路：食物数值、亮度、保护判断与垫块的许可——
 * 饿了挑吃的、太暗看要不要插火把、岩浆里垫不垫落脚，都从这一组读；
 * 读不到当刻现场时各需求按不急处理。新加一路生存需求读的处境，加在这里并传入所在组即可。
 */
record SurvivalReads(
        ReadsFoodValues foods,
        ReadsLightLevels lightLevels,
        /** 保护判断：换气挖开头顶、插火把挑位置都问这个世界的保护判断。 */
        Supplier<Protection> protection,
        /** 岩浆里垫不垫落脚看主目标许可的方块档位；手上没有目标时按默认许可。 */
        Supplier<Permissions.BlockChanges> mainBlockChanges) {

    SurvivalReads {
        Objects.requireNonNull(foods, "foods");
        Objects.requireNonNull(lightLevels, "lightLevels");
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(mainBlockChanges, "mainBlockChanges");
    }
}
