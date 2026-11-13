// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;

/**
 * 原版斧头去皮映射的只读提取：直接读取 {@link AxeItem} 维护的去皮方块表（含全部主世界原木与木头、
 * 下界绯红与诡异菌柄菌核、竹块，以及往该表登记的模组木料），翻成去皮后物品 ID 到未去皮物品 ID 的映射。
 */
final class VanillaStrippables extends AxeItem {

    private VanillaStrippables() {
        super(Tiers.WOOD, new Properties());
    }

    /** 去皮后的方块物品 ID → 对应的未去皮原木、木头、菌柄、菌核或竹块物品 ID。 */
    static Map<String, String> resultToRaw() {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Map.Entry<Block, Block> entry : STRIPPABLES.entrySet()) {
            Item raw = entry.getKey().asItem();
            Item stripped = entry.getValue().asItem();
            if (raw == Items.AIR || stripped == Items.AIR) continue;
            String rawId = BuiltInRegistries.ITEM.getKey(raw).toString();
            String strippedId = BuiltInRegistries.ITEM.getKey(stripped).toString();
            pairs.putIfAbsent(strippedId, rawId);
        }
        return Map.copyOf(pairs);
    }
}
