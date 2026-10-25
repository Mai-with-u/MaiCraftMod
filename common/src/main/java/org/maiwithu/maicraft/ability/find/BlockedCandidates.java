// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * 一次方块寻找里被视线闸挡下的候选账本：索引按距离攒配额时只能做便宜的暴露粗筛，
 * 真正的视线判定在读侧收尾；配额被看不见的候选占满时索引会提前报"扫完"，
 * 这里把看不见的位置记下、喂回索引的排除名单让它继续往外扫，"没找到"不冒充"扫完"。
 *
 * <p>只有当一轮真的扫完了该扫的范围、且这轮没有新的看不见候选可排除时，才承认扫完。
 * 纯记账，不读世界；同一份候选换一次查询（位置、目标、数量、半径变了）就清账重来，
 * 旧查询里看不见的位置在新的站位下未必还看不见。
 */
final class BlockedCandidates {

    /** 这次查询里过不了视线闸的候选位置：下一轮作为排除名单喂回索引。 */
    private final Set<BlockPos> blocked = new HashSet<>();
    /** 账本所属查询的凭据：变了就清账，不把旧查询的排除带进新查询。 */
    private BlockPos center;
    private List<String> targetIds;
    private int want = -1;
    private int radius = -1;

    /** 查询参数变了就清账：中心、目标、数量或半径任一不同（含清账用的空参）都算新的查询。 */
    void followQuery(BlockPos queryCenter, List<String> targetIds, int want, int radius) {
        boolean same = queryCenter != null && queryCenter.equals(center)
                && targetIds.equals(this.targetIds) && want == this.want && radius == this.radius;
        if (same) {
            return;
        }
        this.center = queryCenter;
        this.targetIds = List.copyOf(targetIds);
        this.want = want;
        this.radius = radius;
        blocked.clear();
    }

    /** 本轮喂回索引的排除名单。 */
    Set<BlockPos> excluded() {
        return Set.copyOf(blocked);
    }

    /**
     * 一轮收账：索引说扫完了还不算数——这轮又有新的看不见候选时，下一轮带着它们继续往外扫；
     * 找满数量、或扫完且没有新的可排除候选，才承认真的扫完。
     *
     * @param indexComplete 索引这一轮报的扫完
     * @param accepted      读侧确认的命中数
     * @param want          要找的数量
     * @param rejected      这轮过不了视线闸（或不在半径里）的索引候选，会记进排除名单
     * @return 读侧该向任务报的"扫完没有"
     */
    boolean settle(boolean indexComplete, int accepted, int want, List<BlockPos> rejected) {
        blocked.addAll(rejected);
        if (accepted >= want) {
            return true;
        }
        // 找得少不冒充扫完：还有没试过的候选就继续，只有扫完且再无新排除时才是可信的"没有了"。
        return indexComplete && rejected.isEmpty();
    }
}
