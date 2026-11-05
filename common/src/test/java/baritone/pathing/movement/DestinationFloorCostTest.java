// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.calc.StandingDry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 算路时不挖的目的地地板：只有要恰好站进某一格的目标才有（出行 radius=0，外面套了"站在干处"也认得出），
 * 就是那一格正下方；走近一圈、柱列这类目标没有。
 */
class DestinationFloorCostTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void exactGoalFloorIsTheCellBelow() {
        BlockPos stand = new BlockPos(-176, 100, 314);
        assertEquals(stand.below(), CalculationContext.destinationFloor(NavGoal.exact(stand)));
        assertEquals(stand.below(), CalculationContext.destinationFloor(new StandingDry(NavGoal.exact(stand))),
                "出行给的站在干处目标照样认得出要站进的那一格");
    }

    @Test
    void goalsWithoutAStandingCellHaveNoFloor() {
        assertNull(CalculationContext.destinationFloor(null), "没有目标");
        assertNull(CalculationContext.destinationFloor(NavGoal.near(new BlockPos(0, 64, 0), 2)), "走近一圈");
        assertNull(CalculationContext.destinationFloor(NavGoal.column(0, 0)), "柱列");
    }
}
