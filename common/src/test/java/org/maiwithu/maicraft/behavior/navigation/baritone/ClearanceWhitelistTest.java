// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.GeneratedTerrain;
import org.junit.jupiter.api.io.TempDir;

/**
 * 寻路能清掉的地形：长在树上的原木、自然长的树叶、野生草木算；木屋的原木屋顶、地板，人放的树叶，
 * 火把、南瓜、黑曜石这些不算。实例配置只加额外的，旧版写进去的默认条目不再起作用。
 */
class ClearanceWhitelistTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 离线没有数据包：泥土的标签手动绑上。
        BuiltInRegistries.BLOCK.bindTags(Map.of(BlockTags.DIRT, List.of(
                BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.DIRT), BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.GRASS_BLOCK))));
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

    private static BlockState leaves(int distance, boolean persistent) {
        return Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, distance)
                .setValue(LeavesBlock.PERSISTENT, persistent);
    }

    private static boolean clearable(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return ClearanceWhitelist.allowsAt(world.getBlockState(pos), pos, world, ignored -> true, new HashMap<>());
    }

    @Test
    void 长在树上的原木能挖() {
        World world = new World();
        for (int y = 64; y <= 68; y++) world.put(0, y, 0, Blocks.SPRUCE_LOG.defaultBlockState());
        // 树冠：树顶两层四面贴着距离 1 的叶子，外面一圈距离 2。
        for (int y = 67; y <= 68; y++) {
            for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                world.put(side[0], y, side[1], leaves(1, false));
                world.put(side[0] * 2, y, side[1] * 2, leaves(2, false));
            }
        }
        assertTrue(clearable(world, 0, 64, 0), "树干底下那格照样算树");
        assertTrue(clearable(world, 1, 67, 0), "自然长的树叶能挖");
    }

    @Test
    void 村里木屋的原木屋顶和地板不挖() {
        // 云杉木屋：横着的原木搭成人字形屋顶，屋里竖着的原木当地板，旁边没有树叶。
        World world = new World();
        BlockState roof = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        for (int z = 95; z <= 101; z++) {
            world.put(533, 82, z, roof);
            world.put(534, 83, z, roof);
            world.put(535, 82, z, roof);
        }
        world.put(534, 77, 98, Blocks.SPRUCE_LOG.defaultBlockState());
        world.put(534, 76, 98, Blocks.DIRT.defaultBlockState());
        assertFalse(clearable(world, 534, 83, 98), "屋脊不挖");
        assertFalse(clearable(world, 533, 82, 98), "屋顶不挖");
        assertFalse(clearable(world, 534, 77, 98), "原木地板不挖");
        assertTrue(clearable(world, 534, 76, 98), "地板下面的泥土照样是地形");
    }

    @Test
    void 人放的树叶不挖() {
        World world = new World();
        world.put(0, 64, 0, leaves(7, true));
        assertFalse(clearable(world, 0, 64, 0));
    }

    @Test
    void 挨着木板的原木不是天然树不挖() {
        // 木板房子的墙角立着一根原木，顶上没有树冠：是搭出来的。
        World world = new World();
        for (int y = 64; y <= 67; y++) {
            world.put(0, y, 0, Blocks.OAK_LOG.defaultBlockState());
            world.put(1, y, 0, Blocks.OAK_PLANKS.defaultBlockState());
            world.put(0, y, 1, Blocks.OAK_PLANKS.defaultBlockState());
        }
        assertFalse(clearable(world, 0, 65, 0));
        assertFalse(clearable(world, 1, 65, 0), "木板本来就不挖");
    }

    @Test
    void 野生草木按类别认_旧名单外的花照样挖开() {
        World world = new World();
        world.put(0, 63, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        world.put(0, 64, 0, Blocks.TORCHFLOWER.defaultBlockState());
        world.put(2, 63, 0, Blocks.GRASS_BLOCK.defaultBlockState());
        world.put(2, 64, 0, Blocks.SHORT_GRASS.defaultBlockState());
        assertTrue(clearable(world, 0, 64, 0), "火把花");
        assertTrue(clearable(world, 2, 64, 0), "草");
    }

    @Test
    void 火把南瓜西瓜睡莲黑曜石苔藓地毯路上不挖() {
        World world = new World();
        world.put(0, 64, 0, Blocks.TORCH.defaultBlockState());
        world.put(2, 64, 0, Blocks.PUMPKIN.defaultBlockState());
        world.put(4, 64, 0, Blocks.MELON.defaultBlockState());
        world.put(6, 64, 0, Blocks.LILY_PAD.defaultBlockState());
        world.put(8, 64, 0, Blocks.OBSIDIAN.defaultBlockState());
        world.put(10, 64, 0, Blocks.MOSS_CARPET.defaultBlockState());
        for (int x = 0; x <= 10; x += 2) {
            assertFalse(clearable(world, x, 64, 0), world.getBlockState(new BlockPos(x, 64, 0)).toString());
        }
        assertFalse(ClearanceWhitelist.allows(Blocks.TORCH.defaultBlockState()), "只看种类时也不算");
    }

    @Test
    void 旧版写进配置的默认条目不再起作用_玩家自己加的照旧() {
        ClearanceWhitelist.Rules rules = ClearanceWhitelist.read(
                "[\"minecraft:torch\", \"minecraft:stone\", \"minecraft:obsidian\", \"minecraft:cobblestone\", \"#minecraft:wool\"]");
        assertEquals(3, rules.legacyIgnored());
        assertFalse(rules.extra(Blocks.TORCH.defaultBlockState()), "旧版默认的火把不再起作用");
        assertFalse(rules.extra(Blocks.OBSIDIAN.defaultBlockState()));
        assertTrue(rules.extra(Blocks.COBBLESTONE.defaultBlockState()), "玩家自己加的圆石照旧能挖");
        assertEquals(0, ClearanceWhitelist.read("[]").blocks().size(), "空表只是不额外加");
        assertThrows(IllegalArgumentException.class, () -> ClearanceWhitelist.read("{}"));
    }

    @Test
    void 配置写坏了按没有额外条目处理_天然方块照常挖开(@TempDir Path game) throws IOException {
        // 玩家手滑写错一个逗号：角色不能从此连土都挖不开，只是文件里额外列的不生效。
        Path file = game.resolve(ClearanceWhitelist.CONFIG);
        Files.createDirectories(file.getParent());
        World world = new World();
        world.put(0, 64, 0, Blocks.DIRT.defaultBlockState());
        try {
            Files.writeString(file, "[\"minecraft:cobblestone\",]", StandardCharsets.UTF_8);
            ClearanceWhitelist.initialize(game);
            assertTrue(clearable(world, 0, 64, 0), "泥土照常挖开");
            assertFalse(ClearanceWhitelist.allows(Blocks.COBBLESTONE.defaultBlockState()), "写坏的文件里列的不生效");

            Files.writeString(file, "[\"minecraft:cobblestone\"]", StandardCharsets.UTF_8);
            ClearanceWhitelist.initialize(game);
            assertTrue(ClearanceWhitelist.allows(Blocks.COBBLESTONE.defaultBlockState()), "改对了就生效");
        } finally {
            // 登记册是全进程一份：测完改回空表，不影响别的测试。
            Files.writeString(file, "[]", StandardCharsets.UTF_8);
            ClearanceWhitelist.initialize(game);
        }
    }

    @Test
    void 进世界换上世界生成铺的地形方块_路上照样挖开_配置条目不受影响() {
        try {
            assertFalse(ClearanceWhitelist.allows(Blocks.RAW_IRON_BLOCK.defaultBlockState()), "读不到世界生成数据时不算");
            ClearanceWhitelist.useGeneratedTerrain(new GeneratedTerrain(Set.of(Blocks.RAW_IRON_BLOCK)));
            assertTrue(ClearanceWhitelist.allows(Blocks.RAW_IRON_BLOCK.defaultBlockState()));
            World world = new World();
            world.put(0, 64, 0, Blocks.RAW_IRON_BLOCK.defaultBlockState());
            assertTrue(clearable(world, 0, 64, 0), "按格判断也挖开");
            assertFalse(ClearanceWhitelist.allows(Blocks.CUT_SANDSTONE.defaultBlockState()), "清单里没有的做出来的方块照旧不挖");
        } finally {
            // 清障规则是全进程一份：测完换回读不到，不影响别的测试。
            ClearanceWhitelist.useGeneratedTerrain(GeneratedTerrain.NONE);
        }
    }
}
