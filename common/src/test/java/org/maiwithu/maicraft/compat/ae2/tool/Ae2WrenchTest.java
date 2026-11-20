// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;

/**
 * AE2 石英扳手拆解与放置转正规则离线测试：
 * 验证陨石箱子排除、线缆格多目标拆解先后顺序（面部件 → 中心线缆 → 伪装板）、
 * 普通设备顺时针旋转与样板供应器推送面切换 BFS，以及网络工具显式排除在旋转工具之外。
 */
class Ae2WrenchTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 线缆格拆解优先拆面部件再拆中心线缆最后清伪装板() {
        Ae2ToolReads.CableTarget facade = new Ae2ToolReads.CableTarget(
                Ae2ToolReads.TargetKind.FACADE, Direction.NORTH, new AABB(0, 0, 0, 1, 1, 0.1));
        Ae2ToolReads.CableTarget center = new Ae2ToolReads.CableTarget(
                Ae2ToolReads.TargetKind.CABLE, null, new AABB(0.3, 0.3, 0.3, 0.7, 0.7, 0.7));
        Ae2ToolReads.CableTarget part = new Ae2ToolReads.CableTarget(
                Ae2ToolReads.TargetKind.PART, Direction.EAST, new AABB(0.8, 0.2, 0.2, 1.0, 0.8, 0.8));

        assertEquals(part, Ae2Wrench.pickNextCableTarget(List.of(facade, center, part)).orElseThrow());
        assertEquals(center, Ae2Wrench.pickNextCableTarget(List.of(facade, center)).orElseThrow());
        assertEquals(facade, Ae2Wrench.pickNextCableTarget(List.of(facade)).orElseThrow());
        assertTrue(Ae2Wrench.pickNextCableTarget(List.of()).isEmpty());
    }

    @Test
    void 陨石箱子显式列入扳手拆解排除名单() {
        assertTrue(Ae2Wrench.EXCLUDED_BLOCKS.contains("ae2:sky_stone_chest"));
        assertTrue(Ae2Wrench.EXCLUDED_BLOCKS.contains("ae2:smooth_sky_stone_chest"));
        assertFalse(Ae2Wrench.isExcludedBlock(Blocks.IRON_BLOCK.defaultBlockState()));
        assertTrue(Ae2Wrench.isExcludedBlock(null));
    }

    @Test
    void 扳手拆解对不可拆方块或无工具时返回空() {
        Ae2Compat compat = ToolTestFakes.compat();
        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public boolean canDisassembleBlock(BlockState state) {
                return state.is(Blocks.IRON_BLOCK);
            }
        };
        Ae2Wrench wrench = new Ae2Wrench(compat, reads, ToolTestFakes.services(() -> null, Map.of(), List.of()));
        assertTrue(wrench.toolFor(Blocks.STONE.defaultBlockState()).isEmpty());
        assertTrue(wrench.toolFor(Blocks.IRON_BLOCK.defaultBlockState()).isEmpty(), "角色不在世界里无随身工具应为空");
    }

    @Test
    void 石英扳手转正规则宽度优先求出最少旋转面序列() {
        BlockState north = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH);
        // 模拟 IOrientationStrategy：绕点击面轴顺时针转 90 度。
        BiFunction<BlockState, Direction, Optional<BlockState>> rotator = (state, face) -> {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            if (facing.getAxis() == face.getAxis()) return Optional.empty();
            return Optional.of(state.setValue(BlockStateProperties.FACING, facing.getClockWise(face.getAxis())));
        };

        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public Optional<BlockState> rotateBlock(BlockState state, Direction clickedFace) {
                return rotator.apply(state, clickedFace);
            }
        };
        Ae2WrenchPlacementRules rules = new Ae2WrenchPlacementRules(ToolTestFakes.compat(), reads,
                Optional.of(Items.STICK));

        PlacementRules.Turns turns = rules.turnsTo(north,
                state -> state.getValue(BlockStateProperties.FACING) == Direction.DOWN).orElseThrow();
        assertEquals(Items.STICK, turns.tool());
        assertFalse(turns.faces().isEmpty());
        BlockState current = north;
        for (Direction face : turns.faces()) {
            current = rotator.apply(current, face).orElseThrow();
        }
        assertEquals(Direction.DOWN, current.getValue(BlockStateProperties.FACING));
        assertTrue(rules.turnsTo(north, state -> false).isEmpty());
    }

    @Test
    void 样板供应器推送方向切换规则可从全向切至任意单向() {
        // 借用原版 FACING 模拟样板供应器各向，测试点击对面切出目标方向。
        BlockState start = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        BiFunction<BlockState, Direction, Optional<BlockState>> pushRotator = (state, clicked) -> {
            Direction current = state.getValue(BlockStateProperties.FACING);
            Direction next = current == clicked.getOpposite() ? clicked : clicked.getOpposite();
            return Optional.of(state.setValue(BlockStateProperties.FACING, next));
        };
        List<Direction> faces = Ae2WrenchPlacementRules.findTurns(start,
                state -> state.getValue(BlockStateProperties.FACING) == Direction.WEST, pushRotator).orElseThrow();
        assertEquals(1, faces.size());
        assertEquals(Direction.EAST, faces.getFirst());
    }

    @Test
    void 旋转工具判定显式排除网络工具() {
        Ae2ToolReads reads = new Ae2ToolReads() {
            @Override public boolean canRotateTool(ItemStack stack) {
                return stack.is(Items.STICK);
            }
        };
        Ae2WrenchPlacementRules rules = new Ae2WrenchPlacementRules(ToolTestFakes.compat(), reads);
        assertTrue(rules.canRotateWith(new ItemStack(Items.STICK)));
        assertFalse(rules.canRotateWith(new ItemStack(Items.IRON_INGOT)));
        assertFalse(rules.canRotateWith(ItemStack.EMPTY));
    }

    @Test
    void 只带下界石英扳手时转正规则返回下界扳手且绝不选网络工具() {
        Function<String, Optional<Item>> lookup = id -> switch (id) {
            case Ae2Wrench.CERTUS_WRENCH -> Optional.of(Items.QUARTZ);
            case Ae2Wrench.NETHER_WRENCH -> Optional.of(Items.BLAZE_ROD);
            case Ae2Wrench.NETWORK_TOOL -> Optional.of(Items.COMPASS);
            default -> Optional.empty();
        };

        // 只携带下界石英扳手时，必须返回下界石英扳手，不能硬返回赛特斯石英扳手导致跳过转正。
        assertEquals(Optional.of(Items.BLAZE_ROD),
                Ae2WrenchPlacementRules.resolveQuartzWrench(Set.of(Ae2Wrench.NETHER_WRENCH), lookup));
        // 同时携带两种或只带赛特斯时，优先返回赛特斯石英扳手。
        assertEquals(Optional.of(Items.QUARTZ),
                Ae2WrenchPlacementRules.resolveQuartzWrench(
                        Set.of(Ae2Wrench.CERTUS_WRENCH, Ae2Wrench.NETHER_WRENCH), lookup));
        // 只带网络工具或身上无扳手时，绝不返回网络工具，回退默认石英扳手。
        assertEquals(Optional.of(Items.QUARTZ),
                Ae2WrenchPlacementRules.resolveQuartzWrench(Set.of(Ae2Wrench.NETWORK_TOOL), lookup));
        assertEquals(1, new Ae2Tools(ToolTestFakes.compat(), Ae2ToolReads.NONE).placementRules(() -> null).size());
    }
}
