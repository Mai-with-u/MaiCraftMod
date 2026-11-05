// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.calc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import baritone.api.pathing.goals.Goal;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 目的地自己脚下的地板：路线（整条的或半截的）走完时它被挖掉、没垫回去就不走；身上有能垫的、后面垫回去的照走；
 * 一路往前推进、不碰它的照走。
 */
class DestinationFloorTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 站进 (gx, gy, gz) 的目标：估价按三维最大分量算，脚下那格是自己的地板。 */
    private static Goal standIn(int gx, int gy, int gz) {
        return new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return x == gx && y == gy && z == gz; }
            @Override public double heuristic(int x, int y, int z) {
                return Math.max(Math.abs(x - gx), Math.max(Math.abs(y - gy), Math.abs(z - gz))) * 3.563;
            }
            @Override public boolean isFloor(int x, int y, int z) { return x == gx && y == gy - 1 && z == gz; }
        };
    }

    // 实机营火走廊：引擎交回的整条路线挖开的格（逐步），最后一步踩着地板 (-171,99,314) 往上爬进目的地。
    private static List<DestinationFloor.Step> campfireRoute() {
        return List.of(
                new DestinationFloor.Step(List.of(new BlockPos(-171, 98, 316)), null),
                new DestinationFloor.Step(List.of(new BlockPos(-171, 99, 315), new BlockPos(-171, 98, 315)), null),
                new DestinationFloor.Step(List.of(new BlockPos(-171, 99, 314), new BlockPos(-171, 98, 314)), null),
                new DestinationFloor.Step(List.of(new BlockPos(-172, 99, 314)), new BlockPos(-172, 98, 314)),
                new DestinationFloor.Step(List.of(), new BlockPos(-171, 99, 314)));
    }

    @Test
    void 营火场景_整条路线挖穿目的地地板再踩着它进去_身上没有能垫的_不走() {
        assertTrue(DestinationFloor.leftDug(standIn(-171, 100, 314), campfireRoute(), false));
    }

    @Test
    void 挖穿地板后身上有能垫的_爬进目的地前垫回去_照走() {
        assertFalse(DestinationFloor.leftDug(standIn(-171, 100, 314), campfireRoute(), true));
    }

    @Test
    void 半截路挖穿地板就停_后面没有垫回去的一步_不走() {
        List<DestinationFloor.Step> partial = campfireRoute().subList(0, 3);
        assertTrue(DestinationFloor.leftDug(standIn(-171, 100, 314), partial, true));
    }

    @Test
    void 一路往前推进的路线照旧走() {
        // 挖开路上两格石头往目的地推进，不碰它脚下那格：照旧走。
        Goal goal = standIn(20, 64, 0);
        assertFalse(DestinationFloor.leftDug(goal, List.of(
                new DestinationFloor.Step(List.of(new BlockPos(5, 64, 0), new BlockPos(5, 65, 0)), new BlockPos(5, 63, 0))), false));
        assertFalse(DestinationFloor.leftDug(goal, List.of(), false), "一步不走的路线");
        // 目标没有要恰好站进的格（范围目标）：不管挖哪里都不算挖它的地板。
        Goal anywhereNear = new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return Math.abs(x - 20) <= 3 && y == 64; }
            @Override public double heuristic(int x, int y, int z) { return Math.abs(x - 20); }
        };
        assertFalse(DestinationFloor.leftDug(anywhereNear,
                List.of(new DestinationFloor.Step(List.of(new BlockPos(20, 63, 0)), null)), false));
    }
}
