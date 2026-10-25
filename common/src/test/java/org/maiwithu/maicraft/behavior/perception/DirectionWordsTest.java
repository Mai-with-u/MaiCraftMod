// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 方位说法的词面与换算：相对朝向、东南西北、远近档位、高低差，解说与视图都从这里取词。 */
class DirectionWordsTest {

    @Test
    void relativeWordsFaceTheWayTheCharacterLooks() {
        // 脸朝北（视角角 180），东在右手边，南在身后。
        assertEquals("前方", DirectionWords.relative(0, -5, 180f));
        assertEquals("右侧", DirectionWords.relative(6, 0, 180f));
        assertEquals("左侧", DirectionWords.relative(-6, 0, 180f));
        assertEquals("后方", DirectionWords.relative(0, 5, 180f));
        assertEquals("右前方", DirectionWords.relative(3, -3, 180f));
        assertEquals("左后方", DirectionWords.relative(-3, 3, 180f));
    }

    @Test
    void zeroYawFacesSouthByGameConvention() {
        // 视角角 0 朝南：北就成了身后，西在右手边。
        assertEquals("前方", DirectionWords.relative(0, 4, 0f));
        assertEquals("后方", DirectionWords.relative(0, -4, 0f));
        assertEquals("右侧", DirectionWords.relative(-4, 0, 0f));
    }

    @Test
    void compassWordsFollowWorldAxes() {
        assertEquals("北", DirectionWords.compassOf(0, -1));
        assertEquals("东", DirectionWords.compassOf(1, 0));
        assertEquals("南", DirectionWords.compassOf(0, 1));
        assertEquals("西北", DirectionWords.compassOf(-1, -1));
    }

    @Test
    void facingWordComesFromYaw() {
        // 与 compassOf 同一套八向：视角角 180 是北，0 是南。
        String facing = DirectionWords.compassOf(
                -Math.sin(Math.toRadians(180f)), Math.cos(Math.toRadians(180f)));
        assertEquals("北", facing);
    }

    @Test
    void distanceBandsCoverNearToFar() {
        assertEquals("很近", DirectionWords.distanceWord(2));
        assertEquals("近处", DirectionWords.distanceWord(10));
        assertEquals("有点远", DirectionWords.distanceWord(30));
        assertEquals("很远", DirectionWords.distanceWord(120));
    }

    @Test
    void heightDifferenceCountsBlocks() {
        assertEquals("同高", DirectionWords.heightWord(0));
        assertEquals("高3格", DirectionWords.heightWord(3));
        assertEquals("低2格", DirectionWords.heightWord(-2));
    }

    @Test
    void bearingTripletComputesAllThreeAtOnce() {
        // 角色在 (0.5, 64, 0.5) 脸朝北，目标所在方块 (3, 66, -4)：两边按所在方块取整，距离含高低差。
        DirectionWords.Bearing bearing = DirectionWords.Bearing.byBlock(
                0.5, 64, 0.5, 180f, 3, 66, -4);
        assertEquals("右前方", bearing.direction());
        assertEquals("东北", bearing.compass());
        assertEquals(5, bearing.distance());
    }

    @Test
    void bearingFlatLeavesHeightOutOfTheDistance() {
        // 只算水平：目标 (0, -5) 正北 5 格，高低差再大也不进距离。
        DirectionWords.Bearing bearing = DirectionWords.Bearing.byBlockFlat(0.5, 0.5, 180f, 0, -5);
        assertEquals("前方", bearing.direction());
        assertEquals("北", bearing.compass());
        assertEquals(5, bearing.distance());
    }

    @Test
    void bearingByBlockCenterMeasuresFromTheBlockCenter() {
        // 目标按方块中心量（+0.5）：目标方块 (4, 64, -1) 的中心离角色 (0.5, 64, 0.5) 约 4.15 格。
        DirectionWords.Bearing center = DirectionWords.Bearing.byBlockCenter(
                0.5, 64, 0.5, 180f, 4, 64, -1);
        assertEquals("右侧", center.direction());
        assertEquals("东", center.compass());
        assertEquals(4, center.distance());
        // 只算水平的一版同一口径：目标 (2, 64, 5) 的中心在 (2.5, 5.5)。
        DirectionWords.Bearing flat = DirectionWords.Bearing.byBlockCenterFlat(0.5, 0.5, 180f, 2, 5);
        assertEquals("后方", flat.direction());
        assertEquals("南", flat.compass());
        assertEquals(5, flat.distance());
    }

    @Test
    void bearingNearnessBandsTheUnroundedDistance() {
        // 水平约 4.47 格：取整是 4，但远近档位按未取整的距离分档，落"近处"不落"很近"。
        DirectionWords.Bearing bearing = DirectionWords.Bearing.byBlockFlat(0.5, 0.5, 180f, -4, -2);
        assertEquals(4, bearing.distance());
        assertEquals("近处", bearing.nearness());
    }
}
