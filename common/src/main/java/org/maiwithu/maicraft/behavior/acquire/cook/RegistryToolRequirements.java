// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 工具要求读端的注册表实现：挖掉一种方块需要什么工具、手上这件够不够格，
 * 都按游戏自己的规则从真实的方块与物品注册表读。
 *
 * <p>需要工具的方块按挖掘分组标签（可镐挖、可斧挖……）找到那一类工具（镐、斧……）。这一类每把都够格的
 * （石头、煤矿）给整类的物品标签说法，引擎挑最省的一把去弄；有不够格的（残骸、黑曜石要钻石镐，钻石矿要铁镐）
 * 给够格的那几把里档位最低的一把——按耐久从低到高排（石、铁、钻石、下界合金），够不够格问游戏，不维护等级表。
 * 不这样的话缺镐时会去做一把木镐，下去挖了照样不掉。
 * 方块或物品 ID 在注册表里查不到时按"要求不明"处理：toolRequired 给空、sufficient 给假，
 * 宁可先去弄一把镐也不冒充够格。
 */
public final class RegistryToolRequirements implements ReadsToolRequirements {

    /** 一类工具里的一把：注册 ID、耐久、挖这种方块够不够格。 */
    record ToolCandidate(String itemId, int durability, boolean sufficient) {}

    @Override
    public Optional<String> toolRequired(String blockTypeId) {
        BlockState state = blockState(blockTypeId);
        if (state == null || !state.requiresCorrectToolForDrops()) return Optional.empty();
        TagKey<Item> kind = toolKind(state);
        // 要求正确工具却不在任何挖掘分组里：游戏自己的数据对不上，按要求不明处理。
        if (kind == null) return Optional.empty();
        // 这一类的每一把都问一遍游戏：拿它挖这种方块掉不掉东西。
        List<ToolCandidate> candidates = new ArrayList<>();
        for (Holder<Item> tool : BuiltInRegistries.ITEM.getTagOrEmpty(kind)) {
            ItemStack stack = new ItemStack(tool);
            candidates.add(new ToolCandidate(BuiltInRegistries.ITEM.getKey(tool.value()).toString(),
                    stack.getMaxDamage(), stack.isCorrectToolForDrops(state)));
        }
        return Optional.of(lowestSufficient("#" + kind.location(), candidates));
    }

    /**
     * 缺工具时去弄哪一把：每把都够格（或问不出来）给整类标签，由引擎挑最省的；有不够格的给够格里耐久最低的那把。
     * 金镐耐久最低，但它只够挖木镐也挖得动的方块，在"有不够格的"那种情况里不会被挑上。
     */
    static String lowestSufficient(String kindTag, List<ToolCandidate> candidates) {
        List<ToolCandidate> sufficient = candidates.stream().filter(ToolCandidate::sufficient).toList();
        if (sufficient.isEmpty() || sufficient.size() == candidates.size()) return kindTag;
        return sufficient.stream().min(Comparator.comparingInt(ToolCandidate::durability)).orElseThrow().itemId();
    }

    @Override
    public boolean sufficient(String toolItemId, String blockTypeId) {
        BlockState state = blockState(blockTypeId);
        if (state == null) return false;
        Item item = item(toolItemId);
        if (item == null) return false;
        // 与挖掘结算同一套规则：等级不够的镐挖石头不掉落，这里给的结论与游戏判的一致。
        return new ItemStack(item).isCorrectToolForDrops(state);
    }

    // 挖这种方块用哪一类工具：按方块的挖掘分组标签找对应的物品标签；不在任何分组里为 null。
    private static TagKey<Item> toolKind(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return ItemTags.PICKAXES;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return ItemTags.AXES;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return ItemTags.SHOVELS;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return ItemTags.HOES;
        return null;
    }

    // 按注册 ID 查方块；查不到或写法不合法时给 null，由调用方按"要求不明"处理。
    private static BlockState blockState(String blockTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(blockTypeId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    }

    // 按注册 ID 查物品；查不到或写法不合法时给 null。
    private static Item item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        return BuiltInRegistries.ITEM.get(id);
    }
}
