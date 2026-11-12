// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.loot;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.behavior.inventory.containers.LootsContainer;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 搜刮能力的结果细节：开了哪只容器、开出了什么、拿走了什么、留下了什么、有没有稀罕收获、是否因背包满而没拿完。
 */
record LootDetails(
        LootedContainer container,
        List<LootsContainer.GradedItem> opened,
        List<LootsContainer.GradedItem> taken,
        List<LootsContainer.LeftItem> left,
        List<LootsContainer.RareFind> rareFinds,
        boolean backpackFull) implements ResultDetails {

    /** 被打开的那只容器的种类、坐标与来源事实。 */
    record LootedContainer(String block, WorldPosition position, String whose, String lastModifiedBy,
            boolean openedBySelf) {
        static LootedContainer from(String block, WorldPosition position, ContainerWhose origin) {
            WorldPosition compact = new WorldPosition(position.x(), position.y(), position.z(), position.dimension());
            return new LootedContainer(block, compact, origin.wire(), origin.lastModifiedBy(), origin.openedBySelf());
        }
    }

    LootDetails {
        Objects.requireNonNull(container, "container");
        opened = List.copyOf(opened);
        taken = List.copyOf(taken);
        left = List.copyOf(left);
        rareFinds = List.copyOf(rareFinds);
    }

    static LootDetails from(LootsContainer.Report report) {
        return new LootDetails(
                LootedContainer.from(report.block(), report.position(), report.origin()),
                report.opened(),
                report.taken(),
                report.left(),
                report.rareFinds(),
                report.backpackFull());
    }
}
