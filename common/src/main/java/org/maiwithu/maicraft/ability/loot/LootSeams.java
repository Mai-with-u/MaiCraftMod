// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.loot;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.containers.LootContainerChooser;
import org.maiwithu.maicraft.behavior.inventory.containers.LootsContainer;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 搜刮能力的现场接缝：找目标位置、扫候选容器、挖盖子、点开搜刮，与真实客户端解耦以支持离线单测。
 */
final class LootSeams {

    private LootSeams() {}

    /** 点名的一只实体形式容器（例如 Lootr 战利品箱子矿车或原版宝箱矿车）。 */
    record EntityContainer(int entityId, String typeId, WorldPosition at, ContainerWhose origin, boolean lootable) {}

    /** 搜刮任务读取现场与发起动作的窄接口。 */
    interface World {
        BlockPos feet();

        Optional<WorldPosition> seen(String seenId);

        Optional<EntityContainer> entityContainer(String seenId);

        Optional<WorldPosition> landmark(String name);

        List<SceneSign> signs();

        List<LootContainerChooser.Candidate> around(BlockPos center, int radius, Set<String> protectedLandmarks);

        boolean scanComplete();

        Optional<LootContainerChooser.Candidate> containerAt(WorldPosition position, Set<String> protectedLandmarks);

        Optional<Action> digLid(BlockPos lidCell, Permissions permissions);

        LootsContainer lootBlock(LootContainerChooser.Entry entry, Permissions permissions);

        LootsContainer lootEntity(EntityContainer entity, Permissions permissions);
    }
}
