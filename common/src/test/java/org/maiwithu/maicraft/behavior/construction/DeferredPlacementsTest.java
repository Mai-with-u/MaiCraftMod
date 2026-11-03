// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 放一放的格什么时候回头：旁边或下方新放了方块、人站高了一层、生物挡着的过一会儿；什么都没变不回头。 */
class DeferredPlacementsTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final BlockPos ROOF = new BlockPos(0, 67, 0);
    private static final BlockPos GROUND_FEET = new BlockPos(4, 65, 0);

    private static PlannedCell cell(BlockPos pos) {
        return PlannedCell.block(pos, Blocks.OAK_PLANKS.defaultBlockState(), Set.of());
    }

    @Test
    void 什么都没变_不回头() {
        var deferred = new DeferredPlacements();
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.NO_FACE_WORKED, GROUND_FEET, 0);
        // 别处放成了一块（远处的墙脚），人还站在同一层：屋顶这格的情况没变。
        deferred.placed(new BlockPos(10, 65, 10));
        assertTrue(deferred.changed(new BlockPos(5, 65, 1), 1000, c -> false).isEmpty(),
                "远处放成一块不算这一格的情况变了，不再把所有放不上的格轮一遍");
    }

    @Test
    void 旁边新放了一块_回头() {
        var deferred = new DeferredPlacements();
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.NO_FACE_WORKED, GROUND_FEET, 0);
        deferred.placed(ROOF.east());
        assertEquals(List.of(ROOF), deferred.changed(GROUND_FEET, 1, c -> false).stream().map(PlannedCell::pos).toList(),
                "紧挨着多了一块，就多了一面能点");
    }

    @Test
    void 下方够得着的地方新放了一块_回头() {
        var deferred = new DeferredPlacements();
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.NO_FACE_WORKED, GROUND_FEET, 0);
        deferred.placed(new BlockPos(1, 65, 0));
        assertEquals(1, deferred.changed(GROUND_FEET, 1, c -> false).size(), "垫了一级台阶，多了能站的地方");
    }

    @Test
    void 人站高了一层_回头() {
        var deferred = new DeferredPlacements();
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.NO_FACE_WORKED, GROUND_FEET, 0);
        assertTrue(deferred.changed(GROUND_FEET.east(), 1, c -> false).isEmpty(), "同一层挪两步不算");
        assertEquals(1, deferred.changed(new BlockPos(-1, 67, 0), 1, c -> false).size(), "站上墙头，看得见的面不一样了");
    }

    @Test
    void 人往下走了一层_不回头() {
        var deferred = new DeferredPlacements();
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.NO_FACE_WORKED, GROUND_FEET, 0);
        assertTrue(deferred.changed(new BlockPos(5, 64, 0), 1, c -> false).isEmpty(),
                "在高低不平的地上往下走一层看不到更多，不把放不上的格再轮一遍");
    }

    private static final String MAID = "站在这一格里的 阿缕（touhou_little_maid:maid）";

    @Test
    void 生物挡着_过一会儿回头_几次之后如实交代是谁() {
        var deferred = new DeferredPlacements();
        long now = 0;
        for (int i = 0; i < DeferredPlacements.MAX_TIMED_RETRIES; i++) {
            deferred.defer(cell(ROOF), DeferredPlacements.Reason.CREATURE_IN_THE_WAY, MAID, GROUND_FEET, now);
            assertTrue(deferred.waitingForTime(), "有生物站在那一格里，等它走开");
            assertTrue(deferred.takeStuck(c -> false).isEmpty(), "等着的格不急着垫方块");
            assertTrue(deferred.changed(GROUND_FEET, now + 1, c -> false).isEmpty(), "时候没到不回头");
            now += DeferredPlacements.CREATURE_RETRY_TICKS;
            assertEquals(1, deferred.changed(GROUND_FEET, now, c -> false).size(), "过一会儿回头再试");
        }
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.CREATURE_IN_THE_WAY, MAID, GROUND_FEET, now);
        assertFalse(deferred.waitingForTime(), "回头几次还挡着，不再干等");
        assertTrue(deferred.takeStuck(c -> false).isEmpty(), "垫方块挪不走生物，不交给垫方块那些办法");
        List<DeferredPlacements.Left> left = deferred.drain();
        assertEquals(1, left.size());
        assertEquals(DeferredPlacements.Reason.CREATURE_IN_THE_WAY, left.get(0).reason());
        assertEquals(MAID, left.get(0).creature(), "交代时带上是谁挡着");
    }

    @Test
    void 生物挡着的格_旁边新放了一块也回头() {
        var deferred = new DeferredPlacements();
        // 按时回头的次数先用完。
        for (int i = 0; i < DeferredPlacements.MAX_TIMED_RETRIES; i++) {
            deferred.defer(cell(ROOF), DeferredPlacements.Reason.CREATURE_IN_THE_WAY, MAID, GROUND_FEET, 0);
            assertEquals(1, deferred.changed(GROUND_FEET, DeferredPlacements.CREATURE_RETRY_TICKS, c -> false).size());
        }
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.CREATURE_IN_THE_WAY, MAID, GROUND_FEET, 0);
        assertFalse(deferred.waitingForTime());
        deferred.placed(ROOF.east());
        assertEquals(1, deferred.changed(GROUND_FEET, 1, c -> false).size(), "不按时回头了，情况变了照样回头再试");
    }

    @Test
    void 等别的格的格_等的格好了才回头() {
        var deferred = new DeferredPlacements();
        BlockPos hopper = new BlockPos(0, 64, 0);
        deferred.defer(cell(hopper), DeferredPlacements.Reason.WAITS_ON_OTHERS, GROUND_FEET, 0);
        deferred.placed(hopper.east());
        assertTrue(deferred.changed(GROUND_FEET, 1, c -> true).isEmpty(), "等的格还没好，旁边放了什么都不回头");
        deferred.placed(new BlockPos(9, 70, 9));
        assertEquals(1, deferred.changed(GROUND_FEET, 2, c -> false).size(), "等的格好了就回头");
    }

    @Test
    void 只剩等别的格的格_取出来交代() {
        var deferred = new DeferredPlacements();
        deferred.defer(cell(ROOF), DeferredPlacements.Reason.WAITS_ON_OTHERS, GROUND_FEET, 0);
        assertTrue(deferred.takeStuck(c -> true).isEmpty());
        assertFalse(deferred.waitingForTime());
        assertEquals(1, deferred.drain().size());
        assertTrue(deferred.isEmpty());
    }
}
