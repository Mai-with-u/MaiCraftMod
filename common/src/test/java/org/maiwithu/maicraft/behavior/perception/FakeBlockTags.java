// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.maiwithu.maicraft.game.world.ReadsBlockTags;

/** 方块标签的替身：测试里按需要摆标签与方块，不碰游戏注册表。vanilla() 预先摆好原版与两个加载器通用标签的真实成员。 */
public final class FakeBlockTags implements ReadsBlockTags {

    private final Map<String, Set<String>> members = new LinkedHashMap<>();
    /** 注册表里有的方块类型：挂上标签的方块也是注册表里的方块。 */
    private final Set<String> blocks = new LinkedHashSet<>();

    /** 原版 1.21.1 与通用标签里和设施有关的成员：箱子、木桶、潜影盒、铁砧、工作台、熔炉。 */
    public static FakeBlockTags vanilla() {
        return new FakeBlockTags()
                .tag("c:chests", "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest")
                .tag("c:chests/ender", "minecraft:ender_chest")
                .tag("c:barrels", "minecraft:barrel")
                .tag("minecraft:shulker_boxes", "minecraft:shulker_box", "minecraft:red_shulker_box",
                        "minecraft:light_blue_shulker_box")
                .tag("minecraft:anvil", "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil")
                .tag("c:player_workstations/crafting_tables", "minecraft:crafting_table")
                .tag("c:player_workstations/furnaces", "minecraft:furnace");
    }

    /** 给一个标签加几种方块；这些方块同时算注册表里有的方块。 */
    public FakeBlockTags tag(String tag, String... blockTypes) {
        Set<String> tagged = members.computeIfAbsent(tag, ignored -> new LinkedHashSet<>());
        for (String blockType : blockTypes) {
            tagged.add(blockType);
        }
        this.blocks.addAll(List.of(blockTypes));
        return this;
    }

    /** 往注册表里摆几种不挂标签的方块：床这类没有方块标签的种类要这样摆。 */
    public FakeBlockTags block(String... blockTypes) {
        blocks.addAll(List.of(blockTypes));
        return this;
    }

    @Override public Set<String> tagsOf(String blockType) {
        Set<String> tags = new LinkedHashSet<>();
        members.forEach((tag, blocks) -> {
            if (blocks.contains(blockType)) tags.add(tag);
        });
        return tags;
    }

    @Override public Set<String> blocksIn(String tag) {
        return Set.copyOf(members.getOrDefault(tag, Set.of()));
    }

    @Override public Set<String> allBlockTypes() {
        return Set.copyOf(blocks);
    }
}
