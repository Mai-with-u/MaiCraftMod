// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.world.ReadsBlockTags;

/**
 * 方块归属上报降噪的通用材质分级判断（纯函数）：
 * 区分草方块、泥土、沙子、沙砾、原石、圆石与模组散装落沙这类低价值天然地形，
 * 以及容器、机械、红石、矿块、耕地、木构/石砖/装饰块这类需要 1 格即精确上报的高价值方块。
 * 全部依据原版方块标签、通用公共标签与方块基类特征识别，不硬编码任何第三方模组方块 ID。
 */
public final class LowValueTerrainBlocks {

    /** 同一任务中破坏低价值天然地形达到该格数时合并汇总上报 1 条；不足该格数（≤3 格）静默豁免。 */
    public static final int REPORT_THRESHOLD = 4;

    private static final TagKey<Block> C_ORES = tag("c", "ores");
    private static final TagKey<Block> C_DIRTS = tag("c", "dirts");
    private static final TagKey<Block> C_SANDS = tag("c", "sands");
    private static final TagKey<Block> C_GRAVELS = tag("c", "gravels");
    private static final TagKey<Block> C_STONES = tag("c", "stones");
    private static final TagKey<Block> C_COBBLESTONES = tag("c", "cobblestones");

    private static final Set<String> LOW_VALUE_TAG_NAMES = Set.of(
            "minecraft:dirt",
            "minecraft:sand",
            "minecraft:base_stone_overworld",
            "minecraft:base_stone_nether",
            "minecraft:snow",
            "c:dirts",
            "c:sands",
            "c:gravels",
            "c:stones",
            "c:cobblestones");

    private static final Set<String> HIGH_VALUE_TAG_NAMES = Set.of(
            "c:ores",
            "c:chests",
            "c:barrels",
            "minecraft:shulker_boxes",
            "minecraft:doors",
            "minecraft:rails",
            "minecraft:planks",
            "minecraft:logs",
            "minecraft:stone_bricks",
            "minecraft:coal_ores",
            "minecraft:iron_ores",
            "minecraft:copper_ores",
            "minecraft:gold_ores",
            "minecraft:redstone_ores",
            "minecraft:emerald_ores",
            "minecraft:lapis_ores",
            "minecraft:diamond_ores");

    /** 离线未绑定数据包标签时的原版低价值散装地形兜底集合（仅含原版方块，绝不写任何模组方块 ID）。 */
    private static final Set<String> VANILLA_LOW_VALUE_IDS = Set.of(
            "minecraft:grass_block",
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:podzol",
            "minecraft:rooted_dirt",
            "minecraft:mud",
            "minecraft:mycelium",
            "minecraft:moss_block",
            "minecraft:sand",
            "minecraft:red_sand",
            "minecraft:gravel",
            "minecraft:stone",
            "minecraft:deepslate",
            "minecraft:granite",
            "minecraft:diorite",
            "minecraft:andesite",
            "minecraft:tuff",
            "minecraft:netherrack",
            "minecraft:basalt",
            "minecraft:blackstone",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:end_stone",
            "minecraft:clay",
            "minecraft:snow_block",
            "minecraft:snow");

    private LowValueTerrainBlocks() {}

    /**
     * 判断方块状态是否属于低价值天然散装地形：
     * 容器/机械/红石/门/铁轨、耕地、矿块一律返回假（维持 1 格精确上报）；
     * 命中泥土/沙子/基础岩石/雪标签、原版散装地形或天然重力散沙类时返回真。
     */
    public static boolean isLowValueTerrain(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (isHighValueState(state)) return false;
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.SNOW)
                || state.is(C_DIRTS) || state.is(C_SANDS) || state.is(C_GRAVELS)
                || state.is(C_STONES) || state.is(C_COBBLESTONES)) {
            return true;
        }
        if (isLooseFallingTerrain(state.getBlock())) return true;
        return isVanillaBulkTerrain(state);
    }

    /**
     * 实机按方块注册 ID 判断：从方块注册表取出方块状态，通吃原版与模组标签及 {@link FallingBlock} 特征。
     * 仅在游戏已引导完成（方块注册表已就绪）的环境调用。
     */
    public static boolean isLowValueTerrainLive(String blockId) {
        if (blockId == null || blockId.isBlank()) return false;
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
            return isLowValueTerrain(BuiltInRegistries.BLOCK.get(id).defaultBlockState());
        }
        return VANILLA_LOW_VALUE_IDS.contains(blockId);
    }

    /**
     * 按方块注册 ID 与只读标签接缝判断（不触碰全局方块注册表，适合离线单测与纯标签环境）：
     * 命中高价值标签一律返回假；命中低价值地形标签或原版散装地形 ID 返回真。
     */
    public static boolean isLowValueTerrain(String blockId, ReadsBlockTags tags) {
        if (blockId == null || blockId.isBlank()) return false;
        if (tags != null) {
            Set<String> blockTags = tags.tagsOf(blockId);
            for (String tagName : blockTags) {
                if (HIGH_VALUE_TAG_NAMES.contains(tagName)) return false;
            }
            for (String tagName : blockTags) {
                if (LOW_VALUE_TAG_NAMES.contains(tagName)) return true;
            }
        }
        return VANILLA_LOW_VALUE_IDS.contains(blockId);
    }

    // 容器、机械（方块实体）、红石信号源、门/铁轨、耕地与各类矿块一律属于高价值方块，1 格即精确上报。
    private static boolean isHighValueState(BlockState state) {
        if (FunctionalBlocks.functional(state)) return true;
        if (state.getBlock() instanceof FarmBlock) return true;
        return state.is(C_ORES)
                || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.EMERALD_ORES)
                || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.DIAMOND_ORES);
    }

    // 天然散装重力方块（原版沙砾与各类模组黑沙、火山灰等）：排除铁砧、龙蛋、可疑沙砾与人工合成的混凝土粉末。
    static boolean isLooseFallingTerrain(Block block) {
        return block != null && isLooseFallingTerrainType(block.getClass());
    }

    /** 仅按方块类层次判断是否为天然散装重力方块（供离线单测验证模组子类识别，无需实例化未注册方块）。 */
    static boolean isLooseFallingTerrainType(Class<?> type) {
        return type != null
                && FallingBlock.class.isAssignableFrom(type)
                && !AnvilBlock.class.isAssignableFrom(type)
                && !DragonEggBlock.class.isAssignableFrom(type)
                && !BrushableBlock.class.isAssignableFrom(type)
                && !ConcretePowderBlock.class.isAssignableFrom(type);
    }

    // 原版散装地形兜底：即使在未绑定数据包的离线环境，草方块、泥土、沙砾、圆石等原版散装地形也能准确分级。
    private static boolean isVanillaBulkTerrain(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MUD)
                || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE)
                || state.is(Blocks.TUFF) || state.is(Blocks.NETHERRACK) || state.is(Blocks.BASALT)
                || state.is(Blocks.BLACKSTONE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.END_STONE)
                || state.is(Blocks.CLAY) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SNOW);
    }

    private static TagKey<Block> tag(String namespace, String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
