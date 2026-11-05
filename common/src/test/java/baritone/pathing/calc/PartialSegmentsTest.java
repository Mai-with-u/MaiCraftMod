// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.calc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import baritone.api.pathing.goals.Goal;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 找不到整条路线时的半截路：要挖掉目的地自己脚下的地板就不走；终点比起点离目的地还远的半截路，搜索本来就不给；
 * 一路往前推进的半截路照旧走。
 */
class PartialSegmentsTest {

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
        assertTrue(PartialSegments.digsFloor(goal, broken));
    }

    @Test
    void 一路往前推进的半截路照旧走() {
        // 挖开路上两格石头往目的地推进，不碰它脚下那格：照旧走，下一段接着算。
        Goal goal = standIn(20, 64, 0);
        assertFalse(PartialSegments.digsFloor(goal, List.of(new BlockPos(5, 64, 0), new BlockPos(5, 65, 0))));
        assertFalse(PartialSegments.digsFloor(goal, List.of()), "一格不挖的半截路");
        // 目标没有要恰好站进的格（范围目标）：不管挖哪里都不算挖它的地板。
        Goal anywhereNear = new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return Math.abs(x - 20) <= 3 && y == 64; }
            @Override public double heuristic(int x, int y, int z) { return Math.abs(x - 20); }
        };
        assertFalse(PartialSegments.digsFloor(anywhereNear, List.of(new BlockPos(20, 63, 0))));
    }

    @Test
    void 离起点5格以外没有比起点近的节点_搜索不给半截路() {
        // 起点紧挨目的地，四周能去的地方都比起点远：排位里留着起点自己，交回的是没有路线，不会领着人越走越远。
        Goal goal = standIn(2, 64, 0);
        PathNode start = node(goal, 0, 64, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 64, 0, start, 0.01);
        for (int z = 6; z <= 12; z++) {
            ranking.consider(node(goal, 0, 64, z, z * 4.6));
            ranking.consider(node(goal, -z, 64, 0, z * 4.6));
        }
        assertNull(ranking.firstNodePastStart());
        assertFalse(ranking.hasSegmentPastStart());
    }

    private static PathNode node(Goal goal, int x, int y, int z, double cost) {
        PathNode node = new PathNode(x, y, z, goal);
        node.cost = cost;
        return node;
    }
}
