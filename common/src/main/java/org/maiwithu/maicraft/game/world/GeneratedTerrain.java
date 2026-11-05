// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 这个世界的世界生成往地上铺的实心方块：服务端 MaiCraft 握手时报的清单，按 ID 找到本机的方块。
 * 天然地形在方块标签之外也认它（火山群系的黑沙岩、机械动力按矿脉铺的石头这类不在地形标签里的），只往里加。
 * 进世界时建一份，之后不变，算路线程也直接读；服务端没装 MaiCraft、旧版服务端不报时是空的，天然地形只按标签认。
 *
 * @param blocks 世界生成铺的方块
 */
public record GeneratedTerrain(Set<Block> blocks) {
    private static final Logger LOG = LoggerFactory.getLogger(GeneratedTerrain.class);

    /** 读不到世界生成数据：一种也不认，天然地形只按标签认。 */
    public static final GeneratedTerrain NONE = new GeneratedTerrain(Set.of());

    public GeneratedTerrain {
        blocks = Set.copyOf(blocks);
    }

    /** 这种方块是不是这个世界的世界生成往地上铺的。 */
    public boolean laid(BlockState state) {
        return blocks.contains(state.getBlock());
    }

    /**
     * 按服务端报的方块 ID 建一份：本机没有的 ID 跳过；服务端没报（null）时是 {@link #NONE}。
     * 进世界时在日志里记一行世界生成认出的非原版天然地形方块，直播后好对照实例里认进来了什么。
     */
    public static GeneratedTerrain reported(List<String> blockIds) {
        if (blockIds == null) {
            LOG.info("服务端没报世界生成铺的地形方块（没装 MaiCraft 或版本旧），天然地形只按方块标签认");
            return NONE;
        }
        Set<Block> found = new LinkedHashSet<>();
        List<String> modded = new ArrayList<>();
        for (String id : blockIds) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            Optional<Block> block = key == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(key);
            if (block.isEmpty()) continue;
            found.add(block.get());
            if (!key.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) modded.add(id);
        }
        LOG.info("世界生成认出的非原版天然地形方块 {} 种（连原版共 {} 种）：{}", modded.size(), found.size(),
                String.join(", ", modded));
        return new GeneratedTerrain(found);
    }
}
