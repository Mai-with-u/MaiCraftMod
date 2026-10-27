// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

/**
 * 补胶计划：相邻两格都要同在一块胶框里；新框的两角是车身方块、在框里连着、相距不到 25 格；
 * 框里不夹车身以外的实心方块；已经盖住的不再补。
 */
class GluePlanTest {

    private static final Predicate<BlockPos> NOTHING_ELSE = cell -> false;

    @Test
    void 一块平板_一框盖全() {
        Set<BlockPos> body = box(new BlockPos(0, 64, 0), new BlockPos(4, 64, 4));
        List<GluePlan.Box> boxes = GluePlan.plan(body, List.of(), NOTHING_ELSE);
        assertEquals(1, boxes.size(), "平板两个对角都是方块，一框就够：" + boxes);
        assertCoveredByRules(body, boxes, List.of(), NOTHING_ELSE);
        assertEquals(64, boxes.getFirst().to().getY(), "角色走去的那一角取低的");
    }

    @Test
    void 已经盖住的不再补() {
        Set<BlockPos> body = box(new BlockPos(0, 64, 0), new BlockPos(2, 65, 2));
        List<GluePlan.Box> existing = List.of(new GluePlan.Box(new BlockPos(0, 64, 0), new BlockPos(2, 65, 2)));
        assertTrue(GluePlan.plan(body, existing, NOTHING_ELSE).isEmpty());
    }

    @Test
    void 只补没盖住的那一截() {
        Set<BlockPos> body = box(new BlockPos(0, 64, 0), new BlockPos(9, 64, 0));
        List<GluePlan.Box> existing = List.of(new GluePlan.Box(new BlockPos(0, 64, 0), new BlockPos(5, 64, 0)));
        List<GluePlan.Box> boxes = GluePlan.plan(body, existing, NOTHING_ELSE);
        assertCoveredByRules(body, boxes, existing, NOTHING_ELSE);
        assertEquals(1, boxes.size(), "没盖住的那一截一框补上：" + boxes);
    }

    @Test
    void 船身中间夹着外物_新框不把它框进去() {
        Set<BlockPos> body = new HashSet<>(box(new BlockPos(0, 64, 0), new BlockPos(6, 64, 6)));
        BlockPos pillar = new BlockPos(3, 64, 3);
        body.remove(pillar);
        Predicate<BlockPos> foreign = pillar::equals;
        List<GluePlan.Box> boxes = GluePlan.plan(body, List.of(), foreign);
        assertCoveredByRules(body, boxes, List.of(), foreign);
    }

    @Test
    void 很长的船_分几框_每框都不超过上限() {
        Set<BlockPos> body = box(new BlockPos(0, 64, 0), new BlockPos(59, 65, 2));
        List<GluePlan.Box> boxes = GluePlan.plan(body, List.of(), NOTHING_ELSE);
        assertTrue(boxes.size() >= 3, "60 格长要分几框：" + boxes);
        assertCoveredByRules(body, boxes, List.of(), NOTHING_ELSE);
    }

    @Test
    void 外框四角是空的_照样盖全() {
        // 一艘船：底下 9×5 的船身，上面窄一圈的 5×3 气球，再往上一层 3×1 的顶；外框的角大多是空的。
        Set<BlockPos> body = new HashSet<>(box(new BlockPos(0, 64, 0), new BlockPos(8, 64, 4)));
        body.addAll(box(new BlockPos(2, 65, 1), new BlockPos(6, 67, 3)));
        body.addAll(box(new BlockPos(3, 68, 2), new BlockPos(5, 68, 2)));
        List<GluePlan.Box> boxes = GluePlan.plan(body, List.of(), NOTHING_ELSE);
        assertCoveredByRules(body, boxes, List.of(), NOTHING_ELSE);
        assertTrue(boxes.size() <= 4, "大框优先，不该一对一对地框：" + boxes);
    }

    @Test
    void 转子挨着船身_那一对不粘也不被框住() {
        // 船底一排 x0..4；轴承在 (2,65) 朝上，转子头 (2,66)，左右两片风帆 (1,66)、(3,66)；右边一根桅杆 (4,65)、(4,66) 挨着右边的风帆。
        Set<BlockPos> hull = box(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0));
        BlockPos bearing = new BlockPos(2, 65, 0);
        BlockPos head = new BlockPos(2, 66, 0);
        Set<BlockPos> sails = Set.of(new BlockPos(1, 66, 0), new BlockPos(3, 66, 0));
        Set<BlockPos> body = new HashSet<>(hull);
        body.add(bearing);
        body.add(head);
        body.addAll(sails);
        body.add(new BlockPos(4, 65, 0));
        body.add(new BlockPos(4, 66, 0));
        BiPredicate<BlockPos, BlockPos> mayStick = GluePlan.apartFromRotors(body,
                cell -> cell.equals(bearing) ? Optional.of(Direction.UP) : Optional.empty(), sails::contains);
        assertTrue(mayStick.test(bearing, head), "轴承和转子头照粘，组装时转子才跟着船走");
        assertTrue(mayStick.test(head, new BlockPos(3, 66, 0)), "转子里面照粘");
        assertFalse(mayStick.test(new BlockPos(3, 66, 0), new BlockPos(4, 66, 0)), "风帆和桅杆不粘：转起来会把桅杆拖进转子");
        assertTrue(mayStick.test(new BlockPos(4, 65, 0), new BlockPos(4, 66, 0)), "船身里面照粘");
        assertTrue(mayStick.test(new BlockPos(2, 64, 0), bearing), "轴承和船身照粘");

        List<GluePlan.Box> boxes = GluePlan.plan(body, List.of(), NOTHING_ELSE, mayStick);
        assertCoveredByRules(body, boxes, List.of(), NOTHING_ELSE, mayStick);
    }

    @Test
    void 胶的包围盒换回框住的格() {
        AABB glue = AABB.encapsulatingFullBlocks(new BlockPos(1, 64, 2), new BlockPos(4, 66, 3));
        GluePlan.Box box = GlueInstallation.box(glue);
        assertEquals(new BlockPos(1, 64, 2), box.min());
        assertEquals(new BlockPos(4, 66, 3), box.max());
    }

    // 每一对相邻的车身方块都同在一块框里；每块新框两角是车身、相距不到 25、在框里连着、不夹外物。
    private static void assertCoveredByRules(Set<BlockPos> body, List<GluePlan.Box> added, List<GluePlan.Box> existing,
            Predicate<BlockPos> foreign) {
        assertCoveredByRules(body, added, existing, foreign, (one, other) -> true);
    }

    // 同上，另外不许粘的那几对：不补，也没有哪块新框同时框住它俩。
    private static void assertCoveredByRules(Set<BlockPos> body, List<GluePlan.Box> added, List<GluePlan.Box> existing,
            Predicate<BlockPos> foreign, BiPredicate<BlockPos, BlockPos> mayStick) {
        List<GluePlan.Box> all = new ArrayList<>(existing);
        all.addAll(added);
        for (BlockPos cell : body) {
            for (Direction direction : List.of(Direction.EAST, Direction.UP, Direction.SOUTH)) {
                BlockPos next = cell.relative(direction);
                if (!body.contains(next)) continue;
                if (!mayStick.test(cell, next)) {
                    assertFalse(added.stream().anyMatch(box -> box.contains(cell) && box.contains(next)),
                            cell.toShortString() + " 和 " + next.toShortString() + " 不许粘，却被框在一起：" + added);
                    continue;
                }
                assertTrue(all.stream().anyMatch(box -> box.contains(cell) && box.contains(next)),
                        cell.toShortString() + " 和 " + next.toShortString() + " 没在同一块胶里：" + added);
            }
        }
        for (GluePlan.Box box : added) {
            assertTrue(body.contains(box.from()) && body.contains(box.to()), "两角要是车身方块：" + box);
            assertTrue(box.from().distSqr(box.to()) < 25 * 25, "两角相距要不到 25：" + box);
            assertTrue(box.to().getY() <= box.from().getY(), "走去的那一角是低的：" + box);
            for (BlockPos cell : BlockPos.betweenClosed(box.min(), box.max())) {
                assertFalse(!body.contains(cell) && foreign.test(cell.immutable()), "框里夹着外物 " + cell.toShortString());
            }
            assertTrue(connected(body, box), "两角在框里要连着：" + box);
        }
    }

    private static boolean connected(Set<BlockPos> body, GluePlan.Box box) {
        List<BlockPos> frontier = new ArrayList<>(List.of(box.from()));
        Set<BlockPos> seen = new HashSet<>(frontier);
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeLast();
            if (current.equals(box.to())) return true;
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (body.contains(next) && box.contains(next) && seen.add(next)) frontier.add(next);
            }
        }
        return false;
    }

    private static Set<BlockPos> box(BlockPos min, BlockPos max) {
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) cells.add(cell.immutable());
        return cells;
    }
}
