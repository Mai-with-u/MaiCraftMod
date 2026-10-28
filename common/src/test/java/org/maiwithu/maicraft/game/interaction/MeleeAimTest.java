// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** 近战瞄点的提前量：按它相对我的移动水平提 3 刻、最多 1 格，上下提 0.75 刻、最多 0.5 格。 */
class MeleeAimTest {

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
