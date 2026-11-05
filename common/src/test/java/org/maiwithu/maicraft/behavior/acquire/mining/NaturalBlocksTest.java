// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import baritone.utils.ToolSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.GeneratedTerrain;

/**
 * 缺材料时只挖地图上自然长的：石头、矿、树上的原木、野生草木算；圆石墙、木板、刷石机里的圆石这些搭出来的不算。
 * 草木按方块类别认，种的庄稼、树苗、田里的不算野生。
 */
class NaturalBlocksTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 离线没有数据包：地形、矿石、树苗的标签手动绑上几样。
        BuiltInRegistries.BLOCK.bindTags(Map.of(
                BlockTags.BASE_STONE_OVERWORLD, List.of(holder(Blocks.STONE)),
                BlockTags.DIRT, List.of(holder(Blocks.DIRT), holder(Blocks.GRASS_BLOCK)),
                TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores")),
                List.of(holder(Blocks.IRON_ORE)),
                BlockTags.SAPLINGS, List.of(holder(Blocks.OAK_SAPLING), holder(Blocks.AZALEA))));
    }

    private static Holder<Block> holder(Block block) {
        return BuiltInRegistries.BLOCK.wrapAsHolder(block);
    }

    /** 模组的花：继承原版的草木类别，不在任何名单里。只拿它的类别来认，不用造出这个方块。 */
    abstract static class ModFlower extends BushBlock {
        ModFlower(BlockBehaviour.Properties properties) {
            super(properties);
        }
    }

    /** 模组的庄稼：继承原版的庄稼类别。 */
    static class ModCrop extends CropBlock {
        ModCrop(BlockBehaviour.Properties properties) {
            super(properties);
        }
    }

    /** 替身世界：没摆的格子都是空气。 */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void put(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static boolean grown(World world, BlockState state, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        world.put(x, y, z, state);
        return NaturalBlocks.grown(state, pos, world, ignored -> true, new HashMap<>(), GeneratedTerrain.NONE);
    }

    @Test
    void 天然地形和矿能挖() {
        World world = new World();
        assertTrue(grown(world, Blocks.STONE.defaultBlockState(), 0, 64, 0));
        assertTrue(grown(world, Blocks.IRON_ORE.defaultBlockState(), 1, 64, 0));
        assertTrue(grown(world, Blocks.DIRT.defaultBlockState(), 2, 64, 0));
    }

    @Test
    void 下界的岩浆块和萤石是地形() {
        // 往下挖台阶穿过岩浆块层照常挖，不当成搭出来的拐开；要萤石粉能挖洞顶的萤石。
        World world = new World();
        assertTrue(grown(world, Blocks.MAGMA_BLOCK.defaultBlockState(), 0, 30, 0));
        assertTrue(grown(world, Blocks.GLOWSTONE.defaultBlockState(), 1, 100, 0));
    }

    @Test
    void 搭出来的不挖() {
        World world = new World();
        assertFalse(grown(world, Blocks.COBBLESTONE.defaultBlockState(), 0, 64, 0), "刷石机、墙里的圆石");
        assertFalse(grown(world, Blocks.OAK_PLANKS.defaultBlockState(), 1, 64, 0), "房子的木板");
        assertFalse(grown(world, Blocks.SMOOTH_STONE.defaultBlockState(), 2, 64, 0), "平台的平滑石");
        assertFalse(grown(world, Blocks.GLASS.defaultBlockState(), 3, 64, 0));
    }

    @Test
    void 名单外的原木长在树上也算() {
        // 模组的原木（这里拿去皮原木代替，名单里没有它）顶上有树冠就算树。
        World world = new World();
        BlockState log = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        for (int y = 64; y <= 67; y++) world.put(0, y, 0, log);
        BlockState leaf = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1)
                .setValue(LeavesBlock.PERSISTENT, false);
        for (int y = 66; y <= 67; y++) {
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}}) {
                world.put(side[0], y, side[1], leaf);
            }
        }
        assertTrue(NaturalBlocks.grown(log, new BlockPos(0, 64, 0), world, ignored -> true, new HashMap<>(), GeneratedTerrain.NONE));
    }

    @Test
    void 草木按类别认_模组的花也认得_种的不算() {
        assertTrue(NaturalBlocks.plantType(ModFlower.class), "继承草木类别的模组花");
        assertFalse(NaturalBlocks.plantType(ModCrop.class), "继承庄稼类别的模组庄稼");
        assertTrue(NaturalBlocks.wildPlant(Blocks.TORCHFLOWER.defaultBlockState()), "旧名单里没有的火把花");
        assertTrue(NaturalBlocks.wildPlant(Blocks.SHORT_GRASS.defaultBlockState()));
        assertTrue(NaturalBlocks.wildPlant(Blocks.GLOW_LICHEN.defaultBlockState()));
        assertFalse(NaturalBlocks.wildPlant(Blocks.WHEAT.defaultBlockState()), "小麦");
        assertFalse(NaturalBlocks.wildPlant(Blocks.TORCHFLOWER_CROP.defaultBlockState()), "火把花的苗");
        assertFalse(NaturalBlocks.wildPlant(Blocks.MELON_STEM.defaultBlockState()), "西瓜茎");
        assertFalse(NaturalBlocks.wildPlant(Blocks.NETHER_WART.defaultBlockState()), "地狱疣");
        assertFalse(NaturalBlocks.wildPlant(Blocks.COCOA.defaultBlockState()), "可可豆");
        assertFalse(NaturalBlocks.wildPlant(Blocks.OAK_SAPLING.defaultBlockState()), "树苗");
        assertFalse(NaturalBlocks.wildPlant(Blocks.AZALEA.defaultBlockState()), "杜鹃花丛在树苗标签里");
        assertFalse(NaturalBlocks.wildPlant(Blocks.TORCH.defaultBlockState()), "火把");
        assertFalse(NaturalBlocks.wildPlant(Blocks.MOSS_CARPET.defaultBlockState()), "苔藓地毯");
    }

    @Test
    void 田里种的草木不算野生_两格高的看下半截底下() {
        World world = new World();
        BlockPos onGrass = new BlockPos(0, 64, 0);
        world.put(0, 63, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        world.put(0, 64, 0, Blocks.SWEET_BERRY_BUSH.defaultBlockState());
        assertTrue(NaturalBlocks.wildAt(Blocks.SWEET_BERRY_BUSH.defaultBlockState(), onGrass, world, ignored -> true,
                new HashMap<>(), GeneratedTerrain.NONE), "草地上的甜浆果丛是野生的");

        BlockPos onFarmland = new BlockPos(2, 64, 0);
        world.put(2, 63, 0, Blocks.FARMLAND.defaultBlockState());
        world.put(2, 64, 0, Blocks.SWEET_BERRY_BUSH.defaultBlockState());
        assertFalse(NaturalBlocks.wildAt(Blocks.SWEET_BERRY_BUSH.defaultBlockState(), onFarmland, world,
                ignored -> true, new HashMap<>(), GeneratedTerrain.NONE), "耕地上的是人种的");

        BlockState lower = Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
        world.put(4, 63, 0, Blocks.FARMLAND.defaultBlockState());
        world.put(4, 64, 0, lower);
        world.put(4, 65, 0, upper);
        assertFalse(NaturalBlocks.wildAt(upper, new BlockPos(4, 65, 0), world, ignored -> true, new HashMap<>(), GeneratedTerrain.NONE),
                "两格高的上半截也看下半截底下是不是耕地");
        assertFalse(NaturalBlocks.wildAt(Blocks.SWEET_BERRY_BUSH.defaultBlockState(), new BlockPos(6, 64, 0), world,
                pos -> pos.getY() != 63, new HashMap<>(), GeneratedTerrain.NONE), "底下那格没加载时拿不准，不挖");
    }

    @Test
    void 黑曜石南瓜西瓜睡莲缺料时照样能采_挨着传送门的黑曜石不采() {
        World world = new World();
        assertTrue(grown(world, Blocks.OBSIDIAN.defaultBlockState(), 0, 64, 0));
        assertTrue(grown(world, Blocks.PUMPKIN.defaultBlockState(), 2, 64, 0));
        assertTrue(grown(world, Blocks.MELON.defaultBlockState(), 4, 64, 0));
        assertTrue(grown(world, Blocks.LILY_PAD.defaultBlockState(), 6, 64, 0));
        world.put(10, 65, 0, Blocks.NETHER_PORTAL.defaultBlockState());
        assertFalse(grown(world, Blocks.OBSIDIAN.defaultBlockState(), 10, 64, 0), "门框");
        assertFalse(grown(world, Blocks.CARVED_PUMPKIN.defaultBlockState(), 12, 64, 0), "雕刻过的南瓜是人做的");
    }

    @Test
    void 世界生成铺的方块也算天然地形_只往标签认的里加() {
        // 粗铁块是矿脉里铺的、哪个地形标签都没登记；服务端报了它，就和标签认出的一样算天然。
        BlockState rawIron = Blocks.RAW_IRON_BLOCK.defaultBlockState();
        GeneratedTerrain generated = new GeneratedTerrain(Set.of(Blocks.RAW_IRON_BLOCK, Blocks.STONE));
        assertFalse(NaturalBlocks.terrain(rawIron, GeneratedTerrain.NONE), "读不到世界生成数据时只按标签认");
        assertTrue(NaturalBlocks.terrain(rawIron, generated));
        // 只往里加：清单里没有的石头、泥土照样按标签算；世界生成不铺的切制沙岩照旧不算。
        assertTrue(NaturalBlocks.terrain(Blocks.DIRT.defaultBlockState(), generated));
        assertTrue(NaturalBlocks.terrain(Blocks.STONE.defaultBlockState(), GeneratedTerrain.NONE));
        assertFalse(NaturalBlocks.terrain(Blocks.CUT_SANDSTONE.defaultBlockState(), generated));
        assertTrue(NaturalBlocks.grown(rawIron, new BlockPos(0, 64, 0), new World(), ignored -> true, new HashMap<>(),
                generated), "缺材料时也能挖");
    }

    @Test
    void 基岩进了清单算天然_照旧挖不动() {
        // 地表规则会把基岩收进来：天然不等于挖得动，寻路按挖速算挖它的代价，挖不动就是走不通。
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        GeneratedTerrain generated = new GeneratedTerrain(Set.of(Blocks.BEDROCK));
        assertTrue(NaturalBlocks.terrain(bedrock, generated));
        assertTrue(ToolSet.calculateSpeedVsBlock(new ItemStack(Items.NETHERITE_PICKAXE), bedrock) < 0,
                "下界合金镐也挖不动");
        assertTrue(ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, bedrock) < 0, "空手挖不动");
    }
}
