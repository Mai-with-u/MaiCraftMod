// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import org.junit.jupiter.api.BeforeAll;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.junit.jupiter.api.Test;

/**
 * 附近设施上报的离线场景：一张完整的床占两格、扫描命中两次，上报要折到床头那格只报一条，
 * 不把一张床数成两张；半张床（床头格不是床）照报，锚在命中的那一格上。
 */
class ClientNearbyBlocksSightTest {

    private static final BlockPos FOOT = new BlockPos(10, 64, 5);
    private static final Direction FACING = Direction.NORTH;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState half(BedPart part) {
        return Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.PART, part)
                .setValue(BedBlock.FACING, FACING);
    }

    /** 用一张格子到状态的表当世界：没摆的格子是空气，和真实世界读端的语义一致。 */
    private static Function<BlockPos, BlockState> world(Map<BlockPos, BlockState> states) {
        return p -> states.getOrDefault(p, Blocks.AIR.defaultBlockState());
    }

    private static List<NearbyBlocksSight.BlockSighting> report(List<BlockPos> hits,
            Map<BlockPos, BlockState> states, Predicate<BlockPos> visible) {
        return ClientNearbyBlocksSight.seenFacilities(hits, visible, world(states));
    }

    @Test
    void wholeBedReportsOnceAnchoredAtTheHead() {
        // 床头在床尾北边：两格都命中、都看得见，只报一条，位置折到床头——
        // 否则观察与记忆里一张床变两条，数出两张床。
        BlockPos head = FOOT.relative(FACING);
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(FOOT, half(BedPart.FOOT));
        world.put(head, half(BedPart.HEAD));
        List<NearbyBlocksSight.BlockSighting> seen =
                report(List.of(FOOT, head), world, p -> true);
        assertEquals(1, seen.size());
        assertEquals(WorldPosition.here(head.getX(), head.getY(), head.getZ()), seen.get(0).position());
        assertEquals("minecraft:red_bed", seen.get(0).blockType());
    }

    @Test
    void footAloneVisibleStillReportsTheBed() {
        // 只看得见床尾、床头被墙挡住：可见性按命中格判，床照报一条，位置仍是折后的床头格。
        BlockPos head = FOOT.relative(FACING);
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(FOOT, half(BedPart.FOOT));
        world.put(head, half(BedPart.HEAD));
        List<NearbyBlocksSight.BlockSighting> seen =
                report(List.of(FOOT, head), world, FOOT::equals);
        assertEquals(1, seen.size());
        assertEquals(WorldPosition.here(head.getX(), head.getY(), head.getZ()), seen.get(0).position());
    }

    @Test
    void halfBedReportsAnchoredAtTheHitCell() {
        // 命令只放了半张床，床头格是空气：这张床照报，锚在床尾那格，不悄悄丢掉。
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(FOOT, half(BedPart.FOOT));
        List<NearbyBlocksSight.BlockSighting> seen = report(List.of(FOOT), world, p -> true);
        assertEquals(1, seen.size());
        assertEquals(WorldPosition.here(FOOT.getX(), FOOT.getY(), FOOT.getZ()), seen.get(0).position());
    }

    @Test
    void plainBlocksReportEachCell() {
        // 箱子这类一格的设施不受折格影响，命中几格报几条。
        BlockPos chest = new BlockPos(0, 64, 0);
        BlockPos second = new BlockPos(3, 64, 0);
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(chest, Blocks.CHEST.defaultBlockState());
        world.put(second, Blocks.CHEST.defaultBlockState());
        List<NearbyBlocksSight.BlockSighting> seen = report(List.of(chest, second), world, p -> true);
        assertEquals(2, seen.size());
    }
}
