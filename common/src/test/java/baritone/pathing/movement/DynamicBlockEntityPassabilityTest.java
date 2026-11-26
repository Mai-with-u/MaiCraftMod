// SPDX-License-Identifier: GPL-3.0-only
package baritone.pathing.movement;

import baritone.pathing.precompute.Ternary;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台寻路拿不到方块实体时，带方块实体的动态碰撞方块（如模组管道与伪装墙）不得因回退返回空形状而被当成空气放行，
 * 同时原版无碰撞方块实体（告示牌、旗帜）仍可正常穿过。
 */
class DynamicBlockEntityPassabilityTest {

    /**
     * 模拟末影接口管道（ConduitBundleBlock）这类依赖方块实体计算碰撞箱的模组方块状态：
     * 离线视图 getBlockEntity 为 null 时回退返回 Shapes.empty()，真实存在方块实体时才返回实心碰撞箱；
     * 同时 isPathfindable(LAND) 为 true，完整复现落入位置碰撞判定的路径。
     */
    private static final class ConduitLikeBlockState extends BlockState {
        ConduitLikeBlockState() {
            super(Blocks.MOVING_PISTON, new Reference2ObjectArrayMap<>(), MapCodec.unit(Blocks.MOVING_PISTON.defaultBlockState()));
        }

        @Override
        public boolean hasBlockEntity() {
            return true;
        }

        @Override
        public boolean isPathfindable(PathComputationType type) {
            return true;
        }

        @Override
        public VoxelShape getCollisionShape(BlockGetter level, BlockPos pos, CollisionContext context) {
            return level.getBlockEntity(pos) == null ? Shapes.empty() : Shapes.block();
        }
    }

    /** 模拟 Baritone 离线世界视图：getBlockEntity 恒返回 null，与后台寻路线程行为一致。 */
    private static final class OfflineBlockGetter implements BlockGetter {
        private final BlockState state;

        OfflineBlockGetter(BlockState state) {
            this.state = state;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 带方块实体的动态碰撞块在离线视图下不得误判为空气() {
        BlockState conduit = new ConduitLikeBlockState();
        BlockPos pos = new BlockPos(10, 60, -4);
        BlockGetter offline = new OfflineBlockGetter(conduit);

        // 先确认测试方块状态确实在无方块实体时回退为空碰撞箱（复现管道/伪装墙的根因形态）。
        assertTrue(conduit.hasBlockEntity());
        assertTrue(conduit.getCollisionShape(offline, pos).isEmpty());

        // 寻路状态预判走 MAYBE，落入位置碰撞检查时必须保守判为不可穿过，不得当成空气放行。
        assertEquals(Ternary.MAYBE, MovementHelper.fullyPassableBlockState(conduit));
        assertFalse(MovementHelper.canWalkThroughCollision(offline, pos.getX(), pos.getY(), pos.getZ(), conduit),
                "缺少方块实体的动态碰撞方块不可穿过");
        assertFalse(BlockPassability.canWalkThrough(offline, pos),
                "导航共用的通行判定同样拦截缺少方块实体的动态碰撞块");

        // 原版 MovingPistonBlock 同样在 getBlockEntity 为 null 时返回 Shapes.empty()，一样必须判不可穿过。
        BlockState movingPiston = Blocks.MOVING_PISTON.defaultBlockState();
        BlockGetter pistonOffline = new OfflineBlockGetter(movingPiston);
        assertTrue(movingPiston.getCollisionShape(pistonOffline, pos).isEmpty());
        assertFalse(MovementHelper.canWalkThroughCollision(pistonOffline, pos.getX(), pos.getY(), pos.getZ(), movingPiston));
        assertFalse(BlockPassability.canWalkThrough(pistonOffline, pos));
    }

    @Test
    void 原版告示牌和旗帜在离线视图下依然可正常穿过_墙挂悬挂牌横杆仍挡路() {
        BlockPos pos = new BlockPos(0, 64, 0);
        for (BlockState passable : new BlockState[]{
                Blocks.OAK_SIGN.defaultBlockState(),
                Blocks.OAK_WALL_SIGN.defaultBlockState(),
                Blocks.OAK_HANGING_SIGN.defaultBlockState(),
                Blocks.WHITE_BANNER.defaultBlockState(),
                Blocks.WHITE_WALL_BANNER.defaultBlockState()
        }) {
            BlockGetter offline = new OfflineBlockGetter(passable);
            assertEquals(Ternary.MAYBE, MovementHelper.fullyPassableBlockState(passable));
            assertTrue(MovementHelper.canWalkThroughCollision(offline, pos.getX(), pos.getY(), pos.getZ(), passable),
                    passable + " 在离线视图下应可穿过");
            assertTrue(BlockPassability.canWalkThrough(offline, pos),
                    passable + " 在导航通行判断中应可穿过");
        }

        // 墙挂悬挂告示牌顶部有一根实心横杆，仍按真实碰撞箱判不可穿过。
        BlockState wallHangingSign = Blocks.OAK_WALL_HANGING_SIGN.defaultBlockState();
        BlockGetter wallHangingOffline = new OfflineBlockGetter(wallHangingSign);
        assertFalse(MovementHelper.canWalkThroughCollision(wallHangingOffline, 0, 64, 0, wallHangingSign),
                "墙挂悬挂告示牌有顶部横杆碰撞箱，不应放行");

        // 末地折跃门带方块实体且碰撞箱为空，离线缺方块实体时保守判不可穿过。
        BlockState endGateway = Blocks.END_GATEWAY.defaultBlockState();
        assertFalse(MovementHelper.canWalkThroughCollision(new OfflineBlockGetter(endGateway), 0, 64, 0, endGateway));
    }

    @Test
    void 原版全部无碰撞且非危险方块实体均已被放行白名单覆盖() {
        BlockPos pos = BlockPos.ZERO;
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            if (!state.hasBlockEntity()) continue;
            if (block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY || block == Blocks.MOVING_PISTON) continue;
            BlockGetter offline = new OfflineBlockGetter(state);
            if (state.getCollisionShape(offline, pos).isEmpty()) {
                assertTrue(BlockPassability.isKnownPassableBlockEntity(block),
                        "发现未登记的原版无碰撞方块实体：" + BuiltInRegistries.BLOCK.getKey(block));
            }
        }
    }
}
