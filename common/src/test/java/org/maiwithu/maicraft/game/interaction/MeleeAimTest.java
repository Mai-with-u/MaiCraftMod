// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 近战瞄点：提前量按它相对我的移动水平提 3 刻、最多 1 格，上下提 0.75 刻、最多 0.5 格；
 * 最近的那一点被方块挡着就改瞄露出来的部位，够得着又没挡的才算。
 */
class MeleeAimTest {

    /** 站在三格高石柱顶上的尸壳：身体框 0.6 宽、1.95 高，脚在 y=66；我站在南边 2.4 格外，眼睛 65.62。 */
    private static final AABB HUSK_ON_PILLAR = new AABB(-0.3, 66, -0.3, 0.3, 67.95, 0.3);
    private static final Vec3 EYE = new Vec3(0, 65.62, 2.4);

    @Test
    void nearestPointIsAimedWhenNothingBlocksIt() {
        Vec3 point = MeleeAim.visiblePoint(EYE, HUSK_ON_PILLAR, 3.0, p -> true);
        assertEquals(66.16, point.y, EPS, "没挡着就瞄离眼睛最近的脚边那一点（往里收 0.16）");
    }

    @Test
    void pointHiddenByTheLedgeMovesUpToTheExposedBody() {
        // 实机：尸壳立在柱顶，眼睛到它脚边那一点的射线擦过柱子顶面，准星打到的是方块，一直等不到出手。
        // 像玩家那样改瞄它露出来的身体中间：够得着（眼睛到那点 2.6 格）又没被挡。
        Vec3 point = MeleeAim.visiblePoint(EYE, HUSK_ON_PILLAR, 3.0, p -> p.y >= 66.5);
        assertEquals((66 + 67.95) / 2, point.y, EPS, "抬到身体中间");
        assertTrue(EYE.distanceTo(point) <= 3.0, "挑的点在交互距离内");
    }

    @Test
    void outOfReachAlternativesFallBackToTheNearestPoint() {
        // 柱子再高一格：露出来的部位都在交互距离外——还回最近那点，由出手那边如实报准星打到了方块。
        AABB higher = HUSK_ON_PILLAR.move(0, 1, 0);
        Vec3 point = MeleeAim.visiblePoint(EYE, higher, 3.0, p -> p.y >= 67.5);
        assertEquals(67.16, point.y, EPS);
    }

    private static final double EPS = 1.0e-9;

    @Test
    void strafingPlayerIsLedThreeTicksAhead() {
        // 3 格外疾跑横移每刻 0.28 格、我站着：身体框往他走的方向挪 0.84 格。
        Vec3 lead = MeleeAim.lead(new Vec3(0, 0, 0.28), Vec3.ZERO);
        assertEquals(0.84, lead.z, EPS);
        assertEquals(0, lead.x, EPS);
        assertEquals(0, lead.y, EPS);
    }

    @Test
    void horizontalLeadIsCappedAtOneBlock() {
        // 喝了速度药水横移每刻 0.4 格：3 刻是 1.2 格，按 1 格封顶，方向不变。
        Vec3 lead = MeleeAim.lead(new Vec3(0.4, 0, 0), Vec3.ZERO);
        assertEquals(1.0, lead.x, EPS);
        Vec3 diagonal = MeleeAim.lead(new Vec3(0.3, 0, 0.3), Vec3.ZERO);
        assertEquals(1.0, Math.hypot(diagonal.x, diagonal.z), EPS, "斜着走也按水平合起来封顶");
        assertEquals(diagonal.x, diagonal.z, EPS);
    }

    @Test
    void movingTogetherNeedsNoLead() {
        // 我和他同向同速横移：镜头不落后，不提前。
        Vec3 lead = MeleeAim.lead(new Vec3(0.28, 0, 0), new Vec3(0.28, 0, 0));
        assertEquals(0, lead.length(), EPS);
        // 他站着、我横移：镜头一样落后，往反方向提。
        assertEquals(-0.84, MeleeAim.lead(Vec3.ZERO, new Vec3(0.28, 0, 0)).x, EPS);
    }

    @Test
    void verticalLeadIsShortAndCapped() {
        // 他跳起每刻往上 0.42 格：上下按 0.75 刻提 0.315 格。
        assertEquals(0.315, MeleeAim.lead(new Vec3(0, 0.42, 0), Vec3.ZERO).y, EPS);
        // 再快也不超过 0.5 格。
        assertEquals(0.5, MeleeAim.lead(new Vec3(0, 2.0, 0), Vec3.ZERO).y, EPS);
        assertEquals(-0.5, MeleeAim.lead(new Vec3(0, -2.0, 0), Vec3.ZERO).y, EPS);
    }
}
