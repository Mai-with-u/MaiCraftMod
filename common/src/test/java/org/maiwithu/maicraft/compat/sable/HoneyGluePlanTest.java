// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

/**
 * 蜂蜜胶的补胶计划：相邻两格都要同在一块胶框里；框不夹车身以外的实心方块、每边不超过 48 格；
 * 已经被蜂蜜胶或强力胶盖住的不再补；能一框就一框。
 */
class HoneyGluePlanTest {

    private static final Predicate<BlockPos> NOTHING_ELSE = cell -> false;

    @Test
    void 一艘船_外框四角是空的也一框盖全() {
        // 底下 9×5 的船身，上面窄一圈的气球，再往上一层顶：外框的角大多是空的，蜂蜜胶照样一框。
        Set<BlockPos> body = new HashSet<>(cells(new BlockPos(0, 64, 0), new BlockPos(8, 64, 4)));
        body.addAll(cells(new BlockPos(2, 65, 1), new BlockPos(6, 67, 3)));
        body.addAll(cells(new BlockPos(3, 68, 2), new BlockPos(5, 68, 2)));
        List<HoneyGluePlan.Box> boxes = HoneyGluePlan.plan(body, List.of(), NOTHING_ELSE);
        assertEquals(List.of(new HoneyGluePlan.Box(new BlockPos(0, 64, 0), new BlockPos(8, 68, 4))), boxes);
    }

    @Test
    void 已经盖住的不再补() {
        Set<BlockPos> body = cells(new BlockPos(0, 64, 0), new BlockPos(3, 64, 3));
        List<HoneyGluePlan.Box> existing = List.of(new HoneyGluePlan.Box(new BlockPos(0, 64, 0), new BlockPos(3, 64, 3)));
        assertTrue(HoneyGluePlan.plan(body, existing, NOTHING_ELSE).isEmpty());
    }

    @Test
    void 船身里夹着外物_分开框_不把它框进去() {
        Set<BlockPos> body = new HashSet<>(cells(new BlockPos(0, 64, 0), new BlockPos(6, 64, 6)));
        BlockPos pillar = new BlockPos(3, 64, 3);
        body.remove(pillar);
        Predicate<BlockPos> foreign = pillar::equals;
        List<HoneyGluePlan.Box> boxes = HoneyGluePlan.plan(body, List.of(), foreign);
        assertCoveredByRules(body, boxes, foreign);
        assertTrue(boxes.size() > 1, "夹着外物就分开框：" + boxes);
    }

    @Test
    void 超过上限的长船_分几框_每框都不超过() {
        Set<BlockPos> body = cells(new BlockPos(0, 64, 0), new BlockPos(99, 65, 2));
        List<HoneyGluePlan.Box> boxes = HoneyGluePlan.plan(body, List.of(), NOTHING_ELSE);
        assertCoveredByRules(body, boxes, NOTHING_ELSE);
        assertTrue(boxes.size() <= 4, "100 格长分几框就够：" + boxes);
    }

    @Test
    void 胶的包围盒换回框住的格() {
        AABB glue = AABB.encapsulatingFullBlocks(new BlockPos(1, 64, 2), new BlockPos(4, 66, 3));
        assertEquals(new HoneyGluePlan.Box(new BlockPos(1, 64, 2), new BlockPos(4, 66, 3)), HoneyGlueType.box(glue));
    }

    // 每一对相邻的车身方块都同在一块框里；每块框每边不超过上限、不夹外物。
    private static void assertCoveredByRules(Set<BlockPos> body, List<HoneyGluePlan.Box> boxes, Predicate<BlockPos> foreign) {
        for (BlockPos cell : body) {
            for (Direction direction : List.of(Direction.EAST, Direction.UP, Direction.SOUTH)) {
                BlockPos next = cell.relative(direction);
                if (!body.contains(next)) continue;
                assertTrue(boxes.stream().anyMatch(box -> box.contains(cell) && box.contains(next)),
                        cell.toShortString() + " 和 " + next.toShortString() + " 没在同一块胶里：" + boxes);
            }
        }
        for (HoneyGluePlan.Box box : boxes) {
            assertTrue(box.max().getX() - box.min().getX() < HoneyGluePlan.MAX_SIZE
                    && box.max().getY() - box.min().getY() < HoneyGluePlan.MAX_SIZE
                    && box.max().getZ() - box.min().getZ() < HoneyGluePlan.MAX_SIZE, "每边不超过上限：" + box);
            for (BlockPos cell : BlockPos.betweenClosed(box.min(), box.max())) {
                assertFalse(!body.contains(cell) && foreign.test(cell.immutable()), "框里夹着外物 " + cell.toShortString());
            }
        }
    }

    private static Set<BlockPos> cells(BlockPos min, BlockPos max) {
        List<BlockPos> list = new ArrayList<>();
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) list.add(cell.immutable());
        return new HashSet<>(list);
    }
}
