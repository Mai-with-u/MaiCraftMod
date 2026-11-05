// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.PumpkinBlock;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.maiwithu.maicraft.game.world.GeneratedTerrain;

/**
 * 地图上自然生成的方块。路上挡着时能挖开的（{@link #wildAt}）：天然地形（土石沙砾冰雪、矿石，含模组的地层石头；
 * 下界的岩浆块、萤石、巨型菌的菌盖）、长在树上的原木、自然长的树叶、野生的草木。缺材料时能挖的（{@link #grown}）
 * 在这之外还有黑曜石、南瓜、西瓜、睡莲：它们也是地图上长的，但路上碰到不随手挖。
 * 机器零件、机壳、房子的木板砖块玻璃、平台、刷石机的圆石这些搭出来的一律不拆，不管是自己搭的还是地图上本来就有的；
 * 缺料先从 AE、箱子拿，再去挖天然的。
 *
 * <p>地形按方块标签认，再加上这个世界的世界生成往地上铺的实心方块（服务端报的清单，只往里加）；
 * 草木按方块类别认（模组的花草多半继承原版的草木类别），都不写方块 ID 名单。
 */
public final class NaturalBlocks {

    /** 各模组登记矿石用的公共标签。 */
    private static final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));

    /**
     * 野生草木的类别：花、草、蕨、灌木、蘑菇、藤蔓、海草海带、甘蔗、仙人掌、竹子、紫颂、巨型蘑菇、垂根、
     * 发光地衣、孢子花、红树根、大型垂滴叶。模组的花草多半继承前几类，照样认得出来。
     */
    private static final List<Class<?>> PLANTS = List.of(BushBlock.class, GrowingPlantBlock.class,
            SugarCaneBlock.class, CactusBlock.class, BambooStalkBlock.class, BambooSaplingBlock.class,
            ChorusPlantBlock.class, ChorusFlowerBlock.class, HugeMushroomBlock.class, VineBlock.class,
            MultifaceBlock.class, HangingRootsBlock.class, SporeBlossomBlock.class, MangroveRootsBlock.class,
            BigDripleafBlock.class, BigDripleafStemBlock.class);

    /**
     * 草木里种的东西不算野生：庄稼（小麦、胡萝卜、火把花这类）、南瓜西瓜的茎、地狱疣、可可豆、瓶子草的苗。
     * 睡莲是野生的，但人常拿它铺水面的路，路上不挖，只在缺材料时采。
     */
    private static final List<Class<?>> PLANTED = List.of(CropBlock.class, StemBlock.class, AttachedStemBlock.class,
            NetherWartBlock.class, CocoaBlock.class, PitcherCropBlock.class, WaterlilyBlock.class);

    private NaturalBlocks() {}

    /**
     * 这一格是不是地图上自然长出来的、缺材料时能挖的。
     *
     * @param generated 这个世界的世界生成铺的地形方块；读不到时传 {@link GeneratedTerrain#NONE}
     */
    public static boolean grown(BlockState state, BlockPos pos, BlockGetter level, Predicate<BlockPos> loaded,
            Map<BlockPos, Boolean> trunks, GeneratedTerrain generated) {
        if (wildAt(state, pos, level, loaded, trunks, generated)) return true;
        // 缺材料时还能采、路上不随手挖的天然方块：挨着传送门的黑曜石是门框，不算（拆了门就没了）。
        if (state.is(Blocks.OBSIDIAN)) return !touchesPortal(pos, level, loaded);
        return state.getBlock() instanceof PumpkinBlock || state.is(Blocks.MELON)
                || state.getBlock() instanceof WaterlilyBlock;
    }

    /**
     * 这一格是不是地图上自然长的、路上挡着能挖开的：地形；自然长的树叶；长在天然树上的原木；
     * 野生草木，但长在耕地上的是人种的，不挖。
     * 村里木屋的原木地板、原木屋顶，玩家用原木搭的房子、人放的树叶都不算，路上不挖、看不见也不挖开。
     *
     * @param world  读这一格周围的方块（算路在后台线程上读它自己那份）
     * @param loaded 哪些格读得到
     * @param trunks 一次算路里查过的树干（键是树干顶），同一棵树不重复数叶子
     * @param generated 这个世界的世界生成铺的地形方块
     */
    public static boolean wildAt(BlockState state, BlockPos pos, BlockGetter world, Predicate<BlockPos> loaded,
            Map<BlockPos, Boolean> trunks, GeneratedTerrain generated) {
        if (terrain(state, generated)) return true;
        // 树叶、原木按长没长在树上认，模组的也算：实机在 BOP 冷杉林里缺原木，冷杉树叶挖不开，一千多个站位全被树叶挡住。
        if (state.getBlock() instanceof LeavesBlock) {
            return !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
        }
        if (NaturalTrees.isLog(state)) return NaturalTrees.partOfNaturalTree(pos, world, loaded, trunks);
        // 长在耕地上的是人种的，模组的庄稼也一样：草木类别认得出、种在哪认得出，田里的不踩不挖。
        return wildPlant(state) && !onFarmland(state, pos, world, loaded);
    }

    /**
     * 天然地形，只看方块种类：土石沙砾、陶瓦、下界岩与菌岩、末地石、冰雪、矿石、滴水石锥；下界的岩浆块（Y27–36 成团）、
     * 萤石（挂在洞顶）、巨型菌的菌盖（疣块、菌光体）。模组的地层石头（机械动力的辉绿岩、赭金岩这类）不在原版石头标签里，
     * 但会登记进"幽匿块能长上去""洞穴里长苔藓、长滴水石"这几个地形生成用的标签，照样认得出来。
     * 标签之外，这个世界的世界生成往地上铺的实心方块也算（火山群系的黑沙岩这类哪个地形标签都没登记的）；
     * 神殿里切制、錾制的沙岩世界生成不铺，照旧不算。基岩这类挖不动的也会算进来，挖不挖得动由硬度另外拦。
     */
    public static boolean terrain(BlockState state, GeneratedTerrain generated) {
        return generated.laid(state) || state.is(ORES) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.NYLIUM) || state.is(BlockTags.SCULK_REPLACEABLE)
                || state.is(BlockTags.DRIPSTONE_REPLACEABLE) || state.is(BlockTags.MOSS_REPLACEABLE)
                || state.is(BlockTags.LUSH_GROUND_REPLACEABLE) || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE)
                || state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.SHROOMLIGHT)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.GLOWSTONE)
                || state.getBlock() instanceof PointedDripstoneBlock;
    }

    /** 野生草木，只看方块种类：草木类别里的、不是种的、也不是树苗（树苗多半是人种下等它长的）。 */
    public static boolean wildPlant(BlockState state) {
        return !state.is(BlockTags.SAPLINGS) && plantType(state.getBlock().getClass());
    }

    // 方块类别算不算野生草木：继承草木类别之一，又不是庄稼、茎这类种的东西。
    static boolean plantType(Class<?> type) {
        if (PLANTED.stream().anyMatch(planted -> planted.isAssignableFrom(type))) return false;
        return PLANTS.stream().anyMatch(plant -> plant.isAssignableFrom(type));
    }

    // 草木下面是不是耕地：两格高的草木看下半截底下；底下那格没加载时拿不准，按种在田里算，不挖。
    private static boolean onFarmland(BlockState state, BlockPos pos, BlockGetter world, Predicate<BlockPos> loaded) {
        BlockPos base = pos;
        if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            base = pos.below();
        }
        BlockPos ground = base.below();
        if (!loaded.test(ground)) return true;
        return world.getBlockState(ground).getBlock() instanceof FarmBlock;
    }

    /** 这一格六面有没有挨着传送门方块（下界门、末地门）：挨着的黑曜石是门框。 */
    public static boolean touchesPortal(BlockPos pos, BlockGetter world, Predicate<BlockPos> loaded) {
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (!loaded.test(next)) continue;
            BlockState near = world.getBlockState(next);
            if (near.is(Blocks.NETHER_PORTAL) || near.is(Blocks.END_PORTAL)) return true;
        }
        return false;
    }
}
