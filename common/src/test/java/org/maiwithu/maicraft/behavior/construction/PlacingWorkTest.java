// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 砌筑挑格与失败记忆的判断：挑格先挑站着就够得着的（最近几格都没有才回退按距离挑）；
 * 够得着按可点面中心到眼睛的直线距离算；走不过去的记忆只有新方块落在起点或点击面旁边才作废。
 */
class PlacingWorkTest {

    private static BlockState STONE;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STONE = Blocks.STONE.defaultBlockState();
    }

    /** 世界替身：一张方块表，没写的格都是空气。 */
    private static final class WorldStub implements ReadsBlocks {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>();

        @Override public boolean loaded(BlockPos pos) { return !unloaded.contains(pos); }
        @Override public BlockState state(BlockPos pos) { return cells.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }

    private static PlannedCell cellAt(BlockPos pos) {
        return PlannedCell.block(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("minecraft:stone")).defaultBlockState(), Set.of());
    }

    // ===== 挑格预筛 =====

    @Test
    void 最近几格里有够得着的就挑它() {
        List<PlannedCell> tier = List.of(cellAt(new BlockPos(0, 64, 0)), cellAt(new BlockPos(1, 64, 0)),
                cellAt(new BlockPos(2, 64, 0)));
        Set<BlockPos> reachable = Set.of(new BlockPos(2, 64, 0));
        assertEquals(new BlockPos(2, 64, 0),
                PlacingWork.pickNearestReachable(tier, cell -> reachable.contains(cell.pos())).pos(),
                "前两格不够得着，挑第三格");
    }

    @Test
    void 最近的格够得着就挑最近的() {
        List<PlannedCell> tier = List.of(cellAt(new BlockPos(0, 64, 0)), cellAt(new BlockPos(1, 64, 0)));
        Set<BlockPos> reachable = Set.of(new BlockPos(0, 64, 0));
        assertEquals(new BlockPos(0, 64, 0),
                PlacingWork.pickNearestReachable(tier, cell -> reachable.contains(cell.pos())).pos());
    }

    @Test
    void 最近几格都不够得着_回退挑最近的() {
        List<PlannedCell> tier = List.of(cellAt(new BlockPos(0, 64, 0)), cellAt(new BlockPos(1, 64, 0)));
        assertEquals(new BlockPos(0, 64, 0), PlacingWork.pickNearestReachable(tier, cell -> false).pos());
    }

    @Test
    void 预筛只看离人最近的几格() {
        List<PlannedCell> tier = new ArrayList<>();
        for (int i = 0; i < 10; i++) tier.add(cellAt(new BlockPos(i, 64, 0)));
        Set<BlockPos> reachable = Set.of(new BlockPos(9, 64, 0));
        assertEquals(new BlockPos(0, 64, 0),
                PlacingWork.pickNearestReachable(tier, cell -> reachable.contains(cell.pos())).pos(),
                "够得着的格在预筛窗口之外，不为它挑，照距离挑最近");
    }

    // ===== 够得着的面 =====

    @Test
    void 脚边有支撑面的格算够得着() {
        WorldStub world = new WorldStub();
        world.cells.put(new BlockPos(3, 63, 0), STONE);
        assertTrue(PlacingWork.reachableFromHere(world, cellAt(new BlockPos(3, 64, 0)),
                new BlockPos(3, 64, 0), new BlockPos(0, 64, 0)), "点它下面支撑块的上面那一面，面中心在三格多");
    }

    @Test
    void 面中心超出交互距离算不够得着() {
        WorldStub world = new WorldStub();
        world.cells.put(new BlockPos(5, 63, 0), STONE);
        assertFalse(PlacingWork.reachableFromHere(world, cellAt(new BlockPos(5, 64, 0)),
                new BlockPos(5, 64, 0), new BlockPos(0, 64, 0)), "面中心在五格开外");
    }

    @Test
    void 旁边没有能贴的面算不够得着() {
        WorldStub world = new WorldStub();
        assertFalse(PlacingWork.reachableFromHere(world, cellAt(new BlockPos(3, 64, 0)),
                new BlockPos(3, 64, 0), new BlockPos(0, 64, 0)), "下面和四周都是空气，没有能点的面");
    }

    // ===== 走不过去的记忆作废 =====

    @Test
    void 新方块旁边的记忆作废_远处的保留() {
        PlacingWork.FailedApproach failed = new PlacingWork.FailedApproach(
                new BlockPos(0, 64, 0), new BlockPos(5, 64, 1), Direction.NORTH);
        assertTrue(PlacingWork.invalidatedBy(new BlockPos(5, 64, 3), failed), "点击面旁边两格有新方块");
        assertTrue(PlacingWork.invalidatedBy(new BlockPos(1, 64, 0), failed), "起点旁边有新方块");
        assertTrue(PlacingWork.invalidatedBy(new BlockPos(0, 64, 1), failed), "正好压在触及范围边界上算受影响");
        assertFalse(PlacingWork.invalidatedBy(new BlockPos(0, 64, 8), failed), "离起点和点击面都远，记忆照旧成立");
    }

    @Test
    void 站位够得着的近格优先顺着当前视线扫过_不在四格内前后折返甩头() {
        BlockPos feet = new BlockPos(0, 64, 0);
        // 身后一格（0,64,1）虽然离得比眼前三格（0,64,-3）和右侧两格（2,64,0）更近，但全都在四格内站着够得着：
        // 角色面朝北（0,0,-1）时先放眼前的（0,64,-3），接着顺势扫右侧（2,64,0），最后才转身放身后（0,64,1）。
        PlannedCell behind = cellAt(new BlockPos(0, 64, 1));
        PlannedCell side = cellAt(new BlockPos(2, 64, 0));
        PlannedCell ahead = cellAt(new BlockPos(0, 64, -3));
        List<PlannedCell> remaining = new ArrayList<>(List.of(behind, side, ahead));

        PlannedCell first = PlacingWork.pickNearestReachable(remaining, cell -> true, feet, new Vec3(0, 0, -1));
        assertEquals(ahead.pos(), first.pos(), "面朝北时优先放眼前的够得着格，不为差一格距离猛转 180 度");
        remaining.remove(first);

        PlannedCell second = PlacingWork.pickNearestReachable(remaining, cell -> true, feet, new Vec3(0, 0, -1));
        assertEquals(side.pos(), second.pos(), "放完正前方后顺着侧面扫，仍排在正后方之前");
    }
}
