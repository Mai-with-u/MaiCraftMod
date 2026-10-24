// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.explore.ColumnSample;

/**
 * 一柱里的落点挑选（离线）：水面列不落悬空的顶格，封顶建筑里落到人所在层，露天列落地表。
 */
class ColumnLandingTest {

    /** 加载着的一柱样本：顶盖在 topY（忽略树叶）。 */
    private static ColumnSample column(int topY) {
        return new ColumnSample(true, "minecraft:plains", topY, false, true, "minecraft:grass_block");
    }

    /** 站得住的替身：只有列出的高度站得住。 */
    private static IntPredicate standableAt(int... feetYs) {
        Set<Integer> ys = new HashSet<>();
        for (int y : feetYs) ys.add(y);
        return ys::contains;
    }

    @Test
    void waterColumnGivesNoLanding() {
        // 顶盖是水面（topY=62 是水）：整柱没有干地可站，给空，解析方退回"先走到那一柱列"。
        ColumnSample water = new ColumnSample(true, "minecraft:river", 62, true, false, "minecraft:water");
        assertEquals(OptionalInt.empty(),
                ColumnLanding.feet(water, standableAt(), 64, false));
    }

    @Test
    void openColumnLandsOnTheHighestStandableSurface() {
        // 露天的"去那儿"指的是地面上：地表 70，脚在 71。
        assertEquals(OptionalInt.of(71),
                ColumnLanding.feet(column(70), standableAt(71, 40), 64, false));
    }

    @Test
    void coveredBuildingLandsOnThePlayerLayerNotTheRoof() {
        // 封顶大厅：屋顶块在 77，屋顶上的脚位 78 是露天，厅内地板脚位 74 被盖住。
        // 人就在厅里（脚 75，头顶有遮挡）：落到同层地板，不解析到屋顶。
        assertEquals(OptionalInt.of(74),
                ColumnLanding.feet(column(77), standableAt(78, 74), 75, true));
    }

    @Test
    void indoorPlayerStillLandsOutsideWhenItIsCloser() {
        // 人在屋里指着外面的地面：露天地表 65 比扫出来的浅洞 60 离脚更近，落地表，不顺着扫进洞里。
        assertEquals(OptionalInt.of(65),
                ColumnLanding.feet(column(64), standableAt(65, 60), 64, true));
    }

    @Test
    void unloadedColumnGivesNoLanding() {
        assertEquals(OptionalInt.empty(),
                ColumnLanding.feet(ColumnSample.unloaded(), standableAt(71), 64, false));
    }

    @Test
    void fullyCoveredColumnWithOpenPlayerGivesNoLanding() {
        // 人露天、整柱都被盖住（桥下、屋檐下的地下室）：不替人挑一层，给空退回柱列。
        assertTrue(ColumnLanding.feet(column(70), standableAt(60), 64, false).isEmpty());
    }
}
