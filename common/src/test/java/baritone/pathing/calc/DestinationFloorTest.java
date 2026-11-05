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
 * 找不到整条路线时的半截路：要挖掉目的地自己脚下的地板就不走；一路往前推进的半截路照旧走。
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

    @Test
    void 营火场景_半截路从目的地底下钻上来要挖掉它的地板_不走() {
        // 实机：走廊唯一入口是点着的营火，半截路绕到墙外往下挖、从墙底下钻到目的地正下方，路上要挖开目的地脚下那格。
        Goal goal = standIn(-171, 100, 314);
        List<BlockPos> broken = List.of(new BlockPos(-171, 99, 316), new BlockPos(-171, 98, 316),
                new BlockPos(-171, 99, 315), new BlockPos(-171, 98, 315),
                new BlockPos(-171, 99, 314), new BlockPos(-171, 98, 314));
        assertTrue(DestinationFloor.digsFloor(goal, broken));
    }

    @Test
    void 一路往前推进的半截路照旧走() {
        // 挖开路上两格石头往目的地推进，不碰它脚下那格：照旧走，下一段接着算。
        Goal goal = standIn(20, 64, 0);
        assertFalse(DestinationFloor.digsFloor(goal, List.of(new BlockPos(5, 64, 0), new BlockPos(5, 65, 0))));
        assertFalse(DestinationFloor.digsFloor(goal, List.of()), "一格不挖的半截路");
        // 目标没有要恰好站进的格（范围目标）：不管挖哪里都不算挖它的地板。
        Goal anywhereNear = new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return Math.abs(x - 20) <= 3 && y == 64; }
            @Override public double heuristic(int x, int y, int z) { return Math.abs(x - 20); }
        };
        assertFalse(DestinationFloor.digsFloor(anywhereNear, List.of(new BlockPos(20, 63, 0))));
    }
}
