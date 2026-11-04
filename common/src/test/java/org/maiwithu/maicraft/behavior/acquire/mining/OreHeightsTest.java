// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/** 长程挖矿挖到哪一层：在矿最多那层上面就挖到那层；在那层或以下、还在范围里就原地横挖；在范围以下不挖。 */
class OreHeightsTest {

    @Test
    void 几种矿最多的那一层() {
        assertEquals(16, OreHeights.of("minecraft:iron_ore").orElseThrow().peakY());
        assertEquals(16, OreHeights.of("minecraft:deepslate_iron_ore").orElseThrow().peakY(), "深层矿同一份");
        assertEquals(-16, OreHeights.of("minecraft:gold_ore").orElseThrow().peakY());
        assertEquals(-59, OreHeights.of("minecraft:diamond_ore").orElseThrow().peakY());
        assertEquals(-58, OreHeights.of("minecraft:redstone_ore").orElseThrow().peakY());
        assertEquals(0, OreHeights.of("minecraft:lapis_ore").orElseThrow().peakY());
        assertEquals(48, OreHeights.of("minecraft:copper_ore").orElseThrow().peakY());
        assertTrue(OreHeights.of("create:zinc_ore").isEmpty(), "模组矿不知道在哪层，不下探");
    }

    @Test
    void 在上面挖到那层_在范围里原地横挖_在范围以下不挖() {
        OreHeights.Band iron = OreHeights.of("minecraft:iron_ore").orElseThrow();
        assertEquals(OptionalInt.of(16), OreHeights.workingY(iron, 70));
        assertEquals(OptionalInt.of(-20), OreHeights.workingY(iron, -20), "在最多那层以下、还在范围里：原地横挖");
        OreHeights.Band copper = OreHeights.of("minecraft:copper_ore").orElseThrow();
        assertEquals(OptionalInt.empty(), OreHeights.workingY(copper, -40), "铜矿最低 -16，在它下面要往上爬，不挖");
        OreHeights.Band coal = OreHeights.of("minecraft:coal_ore").orElseThrow();
        assertEquals(OptionalInt.of(64), OreHeights.workingY(coal, 64), "煤最多在 95，人在 64 就原地横挖");
    }

    @Test
    void 主世界站在地表_先下到地表下八格再横挖() {
        OreHeights.Band coal = OreHeights.of("minecraft:coal_ore").orElseThrow();
        assertEquals(OptionalInt.of(56), OreHeights.workingY(coal, 64, OptionalInt.of(64)), "平地上挖煤先往下挖 8 格");
        assertEquals(OptionalInt.of(60), OreHeights.workingY(coal, 64, OptionalInt.of(68)), "浅坑里头顶只盖着 4 格，也先往下");
        assertEquals(OptionalInt.of(64), OreHeights.workingY(coal, 64, OptionalInt.empty()), "读不到地表照旧");
    }

    @Test
    void 山地_最多那层已在地下八格以下就挖到最多那层() {
        OreHeights.Band coal = OreHeights.of("minecraft:coal_ore").orElseThrow();
        assertEquals(OptionalInt.of(95), OreHeights.workingY(coal, 120, OptionalInt.of(120)), "高山顶上照旧挖到 95 层");
        assertEquals(OptionalInt.of(92), OreHeights.workingY(coal, 100, OptionalInt.of(100)), "95 层离地表不到 8 格，再往下");
    }

    @Test
    void 已经在地下矿带里_照旧原地横挖() {
        OreHeights.Band coal = OreHeights.of("minecraft:coal_ore").orElseThrow();
        assertEquals(OptionalInt.of(40), OreHeights.workingY(coal, 40, OptionalInt.of(70)));
        OreHeights.Band iron = OreHeights.of("minecraft:iron_ore").orElseThrow();
        assertEquals(OptionalInt.of(16), OreHeights.workingY(iron, 70, OptionalInt.of(70)), "铁矿最多那层本来就在地下深处");
    }

    @Test
    void 地表下八格低于生成范围_挖到范围底() {
        OreHeights.Band copper = OreHeights.of("minecraft:copper_ore").orElseThrow();
        assertEquals(OptionalInt.of(-16), OreHeights.workingY(copper, -14, OptionalInt.of(-12)));
    }

    @Test
    void 下界不按地表算() {
        OreHeights.Band debris = OreHeights.of("minecraft:ancient_debris").orElseThrow();
        assertEquals(OptionalInt.of(15), OreHeights.workingY(debris, 32, OptionalInt.of(34)));
        OreHeights.Band quartz = OreHeights.of("minecraft:nether_quartz_ore").orElseThrow();
        assertEquals(OptionalInt.of(70), OreHeights.workingY(quartz, 70, OptionalInt.of(72)), "各层一样多的照旧原地横挖");
    }

    @Test
    void 远古残骸_在下界挖到Y15() {
        OreHeights.Band debris = OreHeights.of("minecraft:ancient_debris").orElseThrow();
        assertEquals(OreHeights.NETHER, debris.dimension());
        assertEquals(OptionalInt.of(15), OreHeights.workingY(debris, 32), "岩浆海岸边往下挖台阶到 Y15");
        assertEquals(OptionalInt.empty(), OreHeights.workingY(debris, 5), "在 Y8 以下要往上爬，不挖");
        assertEquals("远古残骸在 Y 8 到 119 生成，Y=15 最多", debris.describe());
    }

    @Test
    void 下界金矿与石英各层一样多_范围里原地横挖_范围以上挖到范围顶() {
        for (String ore : new String[] {"minecraft:nether_gold_ore", "minecraft:nether_quartz_ore"}) {
            OreHeights.Band band = OreHeights.of(ore).orElseThrow();
            assertEquals(OreHeights.NETHER, band.dimension(), ore);
            assertTrue(band.even(), ore);
            assertEquals(OptionalInt.of(70), OreHeights.workingY(band, 70), "在范围里哪层都一样，不往下挖");
            assertEquals(OptionalInt.of(117), OreHeights.workingY(band, 125), "下界顶上往下挖到范围顶");
            assertEquals(OptionalInt.empty(), OreHeights.workingY(band, 9), "范围以下不往上爬");
            assertTrue(band.describe().endsWith("各层一样多"), band.describe());
        }
    }
}
