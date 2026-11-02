// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.List;
import org.maiwithu.maicraft.behavior.explore.ExploreDetails;
import org.maiwithu.maicraft.behavior.explore.ExploreFinding;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 钻洞的结果细节：一路看见了什么、洞口在哪、还有哪些岔路没走（和哪些方向过不去）、走了几段多远、怎么收的尾。
 *
 * @param findings   一路的发现：矿脉（带看见了几块）、岩浆湖、进过的洞口；来源分到访与远望
 * @param entrance   洞口位置；就地开始时是出发点
 * @param branches   没走的岔路，和过不去的方向（带上挡着的是什么）
 * @param legs       走了几段、失败几段、这一趟走了多少格
 * @param stopReason 怎么收的尾
 */
public record CaveDetails(List<Finding> findings, WorldPosition entrance, List<Branch> branches, Legs legs,
        ExploreDetails.StopReason stopReason) implements ResultDetails {

    public CaveDetails {
        findings = List.copyOf(findings);
        branches = List.copyOf(branches);
    }

    /**
     * 一条发现。
     *
     * @param blocks 矿脉看见了几块；别的发现为 null
     */
    public record Finding(ExploreFinding.Kind kind, String targetId, WorldPosition position, MemoryOrigin origin,
            Integer blocks) {}

    /**
     * 一个方向：没走的岔路，或者过不去的地方。
     *
     * @param compass   在角色哪个方位
     * @param distance  水平离角色几格
     * @param dy        比角色高（正）低（负）几格
     * @param blockedBy 过不去时挡着的是什么（水、岩浆、往下几格的落差）；没走的岔路为 null
     */
    public record Branch(String compass, int distance, int dy, String blockedBy) {}

    /** 走行段统计：几段、失败几段、这一趟走了多少格。 */
    public record Legs(int total, int failures, int walkedBlocks) {}
}
