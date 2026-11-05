// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.calc;

import org.maiwithu.maicraft.behavior.navigation.calc.HeuristicCosts;

/**
 * 各系数下目前最好的部分路线节点。部分路线按"目标估价 + 从起点走到这里的代价 ÷ 系数"比较，
 * 系数越大，越容忍代价高却确实在靠近目标的一步。
 * 往下挖一层台阶要先挖开前方三格再落下去，往上挖楼梯也要挖开头顶与前方三格，单步代价都是估价里
 * 一格升降收益（三刻上下）的十几倍到上百倍——徒手挖石质方块时一步四五百刻起步，最深色石头近千刻。
 * 小系数认不出这是进展；末尾两个足够大的系数专门负责让这样的路线成得了像样分段，
 * 其余目标在更小的系数上早就有了像样分段，走不到这两档，行为不变。
 *
 * <p>像样分段要离起点多远按目的地远近定：一般 5 格；目的地本来就在跟前时跟着缩，见 {@link #segmentThreshold}。
 * 换下起点的节点估价必定比起点小，所以交出去的半截路终点总比起点离目的地近，不会领着人来回重算。
 */
final class PartialPathRanking {

    /**
     * 部分路线的分档系数，从小到大排列；末两位必须盖住挖方单步代价对一格升降估价的倍数：
     * 次末位盖住带镐挖方下行（约 100 刻 ÷ 3.4 刻），末位还要盖住徒手挖石质楼梯上行
     * （一步三格最多近千刻 ÷ 一格上升约 3.2 刻，约 290 倍）。
     */
    static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10, 50, 300};

    /** 部分路线走出超过这么多格才算像样分段：太近的分段不值得中途接管。 */
    static final double MIN_DIST_PATH = 5;
    /** 目的地本来就在跟前时，像样分段只要走出起点到目的地估算距离的这么多。 */
    static final double NEAR_GOAL_SHARE = 0.8;

    private final int startX;
    private final int startY;
    private final int startZ;
    private final double minimumImprovement;
    /** 这次算路像样分段离起点的门槛（格数的平方）。 */
    private final double segmentDistanceSquared;
    private final double[] bestMetric = new double[COEFFICIENTS.length];
    private final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];
    private boolean hasSegmentPastStart;

    PartialPathRanking(int startX, int startY, int startZ, PathNode startNode, double minimumImprovement) {
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.minimumImprovement = minimumImprovement;
        double threshold = segmentThreshold(startNode.estimatedCostToGoal);
        this.segmentDistanceSquared = threshold * threshold;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            bestMetric[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
    }

    /**
     * 新展开的节点进各系数排位；任一系数因它走出离起点超过门槛的分段时报一次。
     * 搜索据此判别"还在朝目标推进"与"烧满失败预算也没有像样分段"。
     */
    boolean consider(PathNode neighbor) {
        boolean qualifies = false;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double metric = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
            if (bestMetric[i] - metric > minimumImprovement) {
                bestMetric[i] = metric;
                bestSoFar[i] = neighbor;
                if (pastStart(neighbor)) {
                    qualifies = true;
                }
            }
        }
        hasSegmentPastStart |= qualifies;
        return qualifies;
    }

    /** 到现在为止有没有像样分段：一直没有时搜索只消耗失败预算，到点按没算出来交回。 */
    boolean hasSegmentPastStart() {
        return hasSegmentPastStart;
    }

    /** 某一系数下目前最好的节点。 */
    PathNode nodeByCoefficient(int index) {
        return bestSoFar[index];
    }

    /** 头一个离起点超过门槛的系数档的节点；没有像样分段时为 null。 */
    PathNode firstNodePastStart() {
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (pastStart(bestSoFar[i])) {
                return bestSoFar[i];
            }
        }
        return null;
    }

    /**
     * 像样分段要离起点超过几格：一般 5 格；目的地本来就在跟前时取起点到目的地估算距离的 0.8 倍，两者取小。
     * 目的地离起点 5 格多、墙外离目的地最近的那格离起点正好 5 格时，死门槛永远交不出半截路，
     * 头一次算路 6 秒一到就报没有路线；给了半截路就先走过去，走路时接着算下一段，那一段的算路时间长，能找到要挖好几格的整条路。
     * 估算距离是起点的目标估价按疾跑一格的乐观成本折成的格数，6.25 格以上的目标门槛照旧 5 格；
     * 估价不是正数的目标（离开某处一类）照旧 5 格。
     */
    static double segmentThreshold(double startEstimate) {
        if (!(startEstimate > 0)) {
            return MIN_DIST_PATH;
        }
        return Math.min(MIN_DIST_PATH, startEstimate / HeuristicCosts.COST_HEURISTIC * NEAR_GOAL_SHARE);
    }

    /** 这个节点离起点够不够远，能不能当像样分段的终点。 */
    boolean pastStart(PathNode node) {
        return distanceFromStartSquared(node) > segmentDistanceSquared;
    }

    double distanceFromStartSquared(PathNode node) {
        int xDiff = node.x - startX;
        int yDiff = node.y - startY;
        int zDiff = node.z - startZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
    }
}
