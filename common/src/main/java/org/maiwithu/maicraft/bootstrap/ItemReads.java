// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/**
 * 物品事实的读端：物品标签、注册表、工具资格与燃料值。这些事实写在游戏自己的数据里，
 * 与哪一刻的角色无关；新加一个读物品事实的读端，加在这里并传入所在组即可。
 */
record ItemReads(
        ReadsItemTags itemTags,
        ReadsItemRegistry itemRegistry,
        ReadsToolRequirements toolRequirements,
        FurnaceFuels furnaceFuels) {

    ItemReads {
        Objects.requireNonNull(itemTags, "itemTags");
        Objects.requireNonNull(itemRegistry, "itemRegistry");
        Objects.requireNonNull(toolRequirements, "toolRequirements");
        Objects.requireNonNull(furnaceFuels, "furnaceFuels");
    }
}
