// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

/**
 * 方案轮换的次序：按排好的优先次序往下一个找，现成的直接算数，要放的身上带着才算，
 * 轮换只跳过用不了的、不改次序；一个都找不到就是没有可换的了。
 */
class PlanRotationTest {

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    /** 候选按优先次序排好：先水，蛛网次之，现成的黏液块再往后，干草垫底。 */
    private static List<LandingPlan> candidates() {
        return List.of(
                new LandingPlan(LandingAid.WATER, FEET, FEET, FEET.below(), Direction.UP, false),
                new LandingPlan(LandingAid.COBWEB, FEET, FEET, FEET.below(), Direction.UP, false),
                new LandingPlan(LandingAid.SLIME, FEET, FEET.below(), FEET.below(2), Direction.UP, true),
                new LandingPlan(LandingAid.HAY, FEET, FEET.below(), FEET.below(2), Direction.UP, false));
    }

    @Test
    void rotationSkipsAidsNotCarriedAndKeepsTheGivenOrder() {
        // 身上只带着干草：水、蛛网都跳过，现成的黏液块不需要带，再往下才轮到带着的干草。
        assertEquals(2, PlanRotation.nextIndex(candidates(), 0, aid -> aid == LandingAid.HAY));
    }

    @Test
    void rotationWalksOnFromTheCurrentPlan() {
        // 已经换到黏液块：再换就到干草；干草之后没有可用的了。
        assertEquals(3, PlanRotation.nextIndex(candidates(), 2, aid -> true));
        assertEquals(-1, PlanRotation.nextIndex(candidates(), 3, aid -> true));
    }

    @Test
    void withNothingCarriedOnlyExistingAidsAreUsable() {
        // 身上什么都没带：只有现成的黏液块能接住，其余都跳过。
        assertEquals(2, PlanRotation.nextIndex(candidates(), 0, aid -> false));
        assertEquals(-1, PlanRotation.nextIndex(candidates(), 2, aid -> false));
    }

    @Test
    void carriedWaterIsPickedAheadOfExistingLaterAids() {
        // 身上带着水桶：从第二种起找，先轮到带着的水，现成的更靠后也不提前。
        List<LandingPlan> plans = List.of(
                new LandingPlan(LandingAid.COBWEB, FEET, FEET, FEET.below(), Direction.UP, false),
                new LandingPlan(LandingAid.WATER, FEET, FEET, FEET.below(), Direction.UP, false),
                new LandingPlan(LandingAid.SLIME, FEET, FEET.below(), FEET.below(2), Direction.UP, true));
        assertEquals(1, PlanRotation.nextIndex(plans, 0, aid -> aid == LandingAid.WATER));
    }
}
