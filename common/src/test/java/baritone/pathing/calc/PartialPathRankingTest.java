// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.api.pathing.goals.Goal;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.maiwithu.maicraft.behavior.navigation.calc.HeuristicCosts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 部分路线的各系数排位：往下挖一层台阶（单步代价约百刻）在次末位大系数下要算成进展，
 * 徒手挖石质楼梯往上爬（单步四五百刻起步）在末位系数下要算成进展，
 * 让搜索承认这些像样分段；水平走路在原有小系数下照旧算进展；其余目标不受新增档影响。
 * 像样分段的门槛一般 5 格，目的地本来就在跟前时跟着缩；交出去的终点总比起点离目的地近。
 */
class PartialPathRankingTest {

    /** 目标只看高度：估价按每格约 3.4 刻的下降收益算，与 {@code NavGoal.YLevel} 同一量级。 */
    private static Goal descendTo(int level) {
        double creditPerBlock = 3.42;
        return new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return y == level; }
            @Override public double heuristic(int x, int y, int z) {
                return creditPerBlock * Math.max(0, y - level);
            }
        };
    }

    /** 目标是一个固定格子：估价随三维距离缩短而减小。 */
    private static Goal exact(int gx, int gy, int gz) {
        return new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return x == gx && y == gy && z == gz; }
            @Override public double heuristic(int x, int y, int z) {
                return Math.max(Math.abs(x - gx), Math.max(Math.abs(y - gy), Math.abs(z - gz))) * 3.563;
            }
        };
    }

    /** 要恰好站进的一格：估价和出行的精确目标同一份（八向水平距离加升降代价）。 */
    private static Goal standAt(int gx, int gy, int gz) {
        BlockPos goal = new BlockPos(gx, gy, gz);
        return new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return x == gx && y == gy && z == gz; }
            @Override public double heuristic(int x, int y, int z) {
                return HeuristicCosts.pointBound(goal, new BlockPos(x, y, z));
            }
        };
    }

    private static PathNode node(Goal goal, int x, int y, int z, double cost) {
        PathNode node = new PathNode(x, y, z, goal);
        node.cost = cost;
        return node;
    }

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 系数从小到大且末位足以承认挖方下行() {
        double[] coefficients = PartialPathRanking.COEFFICIENTS;
        for (int i = 1; i < coefficients.length; i++) {
            assertTrue(coefficients[i - 1] < coefficients[i], "系数必须从小到大");
        }
        // 挖一层台阶的最坏单步代价约 100 刻（三格硬岩加工具罚分），估价一格下降约 3.4 刻；
        // 次末位系数必须让这一步的指标仍能改善，搜索才承认往下挖是进展。
        assertTrue(100.0 / coefficients[coefficients.length - 2] < 3.42, "次末位系数太小，挖方下行永远成不了像样分段");
    }

    @Test
    void 末位足以承认徒手挖石质楼梯上行() {
        double[] coefficients = PartialPathRanking.COEFFICIENTS;
        // 徒手挖深色石头楼梯上行：一步挖三格，每格约 300 刻加罚分，单步近千刻；
        // 估价一格上升约 3.2 刻。末位系数必须让这一步的指标仍能改善，
        // 不然坑底往台地上行的目标全被「没有像样分段」卡死。
        assertTrue(920.0 / coefficients[coefficients.length - 1] < 3.16, "末位系数太小，徒手挖石质楼梯上行永远成不了像样分段");
    }

    @Test
    void 逐层往下挖在旧系数上不成进展_在末位系数上算() {
        Goal goal = descendTo(80);
        PathNode start = node(goal, 0, 97, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 97, 0, start, 0.01);

        // 挖一层台阶：估价少 3.42，代价多 100。指标变化 = -3.42 + 100/系数。
        PathNode oneLevelDown = node(goal, 0, 96, 0, 100);
        ranking.consider(oneLevelDown);
        // 次次末位系数 10：-3.42 + 10 > 0，不改善，排位里仍是起点。
        assertSame(start, ranking.nodeByCoefficient(PartialPathRanking.COEFFICIENTS.length - 3), "小系数不该承认单步百刻的下挖");
        // 末位系数 50：-3.42 + 2 < 0，改善，排位里换成这一格。
        assertSame(oneLevelDown, ranking.nodeByCoefficient(PartialPathRanking.COEFFICIENTS.length - 1), "末位系数要承认挖一层台阶");
        // 才下挖 1 格，还没到像样分段的 5 格线。
        assertFalse(ranking.hasSegmentPastStart());
    }

    @Test
    void 下挖够深就有了像样分段_部分路线取到下挖节点() {
        Goal goal = descendTo(80);
        PathNode start = node(goal, 0, 97, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 97, 0, start, 0.01);

        PathNode last = start;
        for (int depth = 1; depth <= 6; depth++) {
            last = node(goal, 0, 97 - depth, 0, depth * 100.0);
            ranking.consider(last);
        }
        assertTrue(ranking.hasSegmentPastStart(), "连续下挖 6 格应被认成像样分段");
        assertEquals(97 - 6, ranking.firstNodePastStart().y, "部分路线取到下挖到的那格");
    }

    /** 目标只看高度：估价按每格约 3.2 刻的上升收益算，与目标估价里一格上升的成本同一量级。 */
    private static Goal ascendTo(int level) {
        double creditPerBlock = 3.16;
        return new Goal() {
            @Override public boolean isInGoal(int x, int y, int z) { return y == level; }
            @Override public double heuristic(int x, int y, int z) {
                return creditPerBlock * Math.max(0, level - y);
            }
        };
    }

    @Test
    void 徒手挖楼梯上行在次末位系数上不成进展_在末位系数上算() {
        Goal goal = ascendTo(180);
        PathNode start = node(goal, 0, 90, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 90, 0, start, 0.01);

        // 徒手挖石质楼梯上行一格：挖开头顶与前方三格（每格约 150 刻加罚分）再跳一步，单步约 464 刻。
        // 指标变化 = -3.16 + 464/系数。
        PathNode oneLevelUp = node(goal, 0, 91, 0, 464);
        ranking.consider(oneLevelUp);
        // 次末位系数 50：-3.16 + 9.3 > 0，不改善——只盖得住带镐挖方的档认不出徒手挖楼梯。
        assertSame(start, ranking.nodeByCoefficient(PartialPathRanking.COEFFICIENTS.length - 2),
                "次末位系数不该承认单步四百多刻的徒手挖楼梯上行");
        // 末位系数 300：-3.16 + 1.5 < 0，改善，排位里换成这一格。
        assertSame(oneLevelUp, ranking.nodeByCoefficient(PartialPathRanking.COEFFICIENTS.length - 1),
                "末位系数要承认徒手挖楼梯上行");
        // 才上爬 1 格，还没到像样分段的 5 格线。
        assertFalse(ranking.hasSegmentPastStart());
    }

    @Test
    void 徒手挖楼梯上爬够高就有了像样分段_部分路线取到上爬节点() {
        Goal goal = ascendTo(180);
        PathNode start = node(goal, 0, 90, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 90, 0, start, 0.01);

        // 逐层挖楼梯上爬，每层单步代价 464 刻：末位系数上每层指标仍改善 3.16 - 464/300 ≈ 1.6。
        PathNode last = start;
        for (int height = 1; height <= 6; height++) {
            last = node(goal, 0, 90 + height, 0, height * 464.0);
            ranking.consider(last);
        }
        assertTrue(ranking.hasSegmentPastStart(), "连续上爬 6 格应被认成像样分段");
        assertEquals(90 + 6, ranking.firstNodePastStart().y, "部分路线取到上爬到的那格");
    }

    @Test
    void 水平走远在原有小系数下照旧算进展() {
        Goal goal = exact(-30, 97, 60);
        PathNode start = node(goal, 0, 97, 60, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 97, 60, start, 0.01);

        // 朝目标水平走 6 格：估价少约 21，代价约 21；最小系数 1.5 上指标 -21 + 14 < 0，改善。
        PathNode walked = node(goal, -6, 97, 60, 21.4);
        assertTrue(ranking.consider(walked), "水平接近目标在最小系数下就该被认成进展");
        assertSame(walked, ranking.firstNodePastStart(), "部分路线仍取头一个够远的系数档");
    }

    @Test
    void 原地徘徊与远离不产生像样分段() {
        Goal goal = descendTo(80);
        PathNode start = node(goal, 0, 97, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 97, 0, start, 0.01);

        // 横向走动：估价不变，任何系数上指标都变差，排位停在起点。
        ranking.consider(node(goal, 6, 97, 0, 21.4));
        ranking.consider(node(goal, -6, 97, 0, 21.4));
        assertFalse(ranking.hasSegmentPastStart(), "没有接近目标的节点不该算像样分段");
        assertNull(ranking.firstNodePastStart(), "没有够远的节点时取不到部分路线");
    }

    @Test
    void 离起点5格以外没有比起点近的节点_搜索不给半截路() {
        // 起点紧挨目的地，四周能去的地方都比起点远：排位里留着起点自己，交回的是没有路线，不会领着人越走越远。
        Goal goal = exact(2, 64, 0);
        PathNode start = node(goal, 0, 64, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 64, 0, start, 0.01);
        for (int z = 6; z <= 12; z++) {
            ranking.consider(node(goal, 0, 64, z, z * 4.6));
            ranking.consider(node(goal, -z, 64, 0, z * 4.6));
        }
        assertNull(ranking.firstNodePastStart());
        assertFalse(ranking.hasSegmentPastStart());
    }

    @Test
    void 目的地在墙后5格多_墙外最近那格离起点正好5格也交得出半截路() {
        // 封闭走廊：目的地在墙后，从完整地面出发；墙外离目的地最近的那格 (-176,100,316) 离起点正好 5 格，
        // 死门槛下永远交不出半截路，头一次算路 6 秒一到就报没有路线。门槛跟着目的地缩以后，先走到那一格再接着算。
        Goal goal = standAt(-176, 100, 314);
        PathNode start = node(goal, -171, 100, 316, 0);
        assertTrue(PartialPathRanking.segmentThreshold(start.estimatedCostToGoal) < PartialPathRanking.MIN_DIST_PATH,
                "目的地离起点 5 格多，门槛要比 5 格小");
        PartialPathRanking ranking = new PartialPathRanking(-171, 100, 316, start, 0.01);

        // 沿墙外往西走，每格按走路 4.633 刻算。
        for (int k = 1; k <= 6; k++) {
            ranking.consider(node(goal, -171 - k, 100, 316, k * 4.633));
        }

        assertTrue(ranking.hasSegmentPastStart(), "该交得出半截路");
        PathNode end = ranking.firstNodePastStart();
        assertEquals(-176, end.x, "半截路走到墙外离目的地最近的那一格");
        assertEquals(316, end.z);
        assertEquals(25.0, ranking.distanceFromStartSquared(end), "这一格离起点正好 5 格，死门槛下不算");
    }

    @Test
    void 目的地远时门槛照旧5格() {
        double sprintBlock = HeuristicCosts.COST_HEURISTIC;
        assertEquals(5.0, PartialPathRanking.segmentThreshold(30 * sprintBlock), 1e-9);
        assertEquals(5.0, PartialPathRanking.segmentThreshold(6.25 * sprintBlock), 1e-9, "6.25 格起门槛就是 5 格");
        assertEquals(4.0, PartialPathRanking.segmentThreshold(5 * sprintBlock), 1e-9, "5 格远的目的地门槛 4 格");
        assertEquals(5.0, PartialPathRanking.segmentThreshold(-20), 1e-9, "估价是负数的离开类目标照旧 5 格");
        assertEquals(5.0, PartialPathRanking.segmentThreshold(Double.NaN), 1e-9);

        // 目的地在 30 格外：朝它走正好 5 格的不算像样分段，走出 6 格的算，和原来一样。
        Goal goal = standAt(30, 64, 0);
        PathNode start = node(goal, 0, 64, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 64, 0, start, 0.01);
        ranking.consider(node(goal, 5, 64, 0, 5 * 4.633));
        assertFalse(ranking.hasSegmentPastStart(), "离起点正好 5 格不算");
        ranking.consider(node(goal, 6, 64, 0, 6 * 4.633));
        assertTrue(ranking.hasSegmentPastStart(), "离起点 6 格算");
        assertEquals(6, ranking.firstNodePastStart().x);
    }

    @Test
    void 目的地就在跟前时_比起点还远的节点再远也不给半截路() {
        // 目的地在 3 格外的墙后，门槛缩到 2.4 格：往反方向、往两侧走远的节点估价都比起点大，进不了排位，
        // 交回的是没有路线，不会领着人越走越远、来回重算。
        Goal goal = standAt(3, 64, 0);
        PathNode start = node(goal, 0, 64, 0, 0);
        PartialPathRanking ranking = new PartialPathRanking(0, 64, 0, start, 0.01);
        for (int d = 3; d <= 10; d++) {
            ranking.consider(node(goal, -d, 64, 0, d * 4.633));
            ranking.consider(node(goal, 0, 64, d, d * 4.633));
            ranking.consider(node(goal, -d, 64, -d, d * 6.55));
        }
        assertFalse(ranking.hasSegmentPastStart(), "比起点远的节点不给半截路");
        assertNull(ranking.firstNodePastStart());

        // 绕到墙侧、比起点离目的地近的一格（离起点约 2.8 格）：给，终点估价比起点小。
        ranking.consider(node(goal, 2, 64, 2, 2 * 6.55));
        PathNode end = ranking.firstNodePastStart();
        assertTrue(end != null && end.estimatedCostToGoal < start.estimatedCostToGoal, "半截路终点要比起点离目的地近");
    }
}
