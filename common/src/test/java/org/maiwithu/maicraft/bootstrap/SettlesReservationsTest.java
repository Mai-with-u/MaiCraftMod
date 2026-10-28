// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/** 下令的目标结束时给预留结账：动了几件扣几件、变化上记 from_reserved；按顺序做事的整个目标不再结一遍。 */
class SettlesReservationsTest {

    private final Map<String, Integer> carried = new HashMap<>();
    private final ReservedItems reserved = new ReservedItems(new FakeTags(), () -> Map.copyOf(carried));
    private final SettlesReservations store = new SettlesReservations(new InMemoryGoalRunStore(), reserved);

    private static Goal leaf() {
        return new Goal("maicraft:build", null, null, ParamValues.EMPTY, null, List.of(), null);
    }

    @Test
    void 下令的目标动了预留的_扣掉并在变化上记清() {
        // 预留木板 40、身上 45：盖小屋放了 30 块，其中 25 块是预留的。
        reserved.set(List.of(new ReservedItems.Reservation("minecraft:oak_planks", 40)));
        carried.put("minecraft:oak_planks", 15);
        TaskResult result = TaskResult.builder(TaskResult.Status.DONE, "盖好了")
                .change(Change.of(Change.Kind.BLOCK_PLACED, "minecraft:oak_planks", 30)).build();

        TaskResult settled = store.settling(new GoalRun(1, leaf()), result);

        assertEquals(25, settled.changes().getFirst().fromReserved());
        assertEquals(15, reserved.keep("minecraft:oak_planks"));
    }

    @Test
    void 没动预留的原样交回() {
        reserved.set(List.of(new ReservedItems.Reservation("minecraft:oak_planks", 10)));
        carried.put("minecraft:oak_planks", 20);
        TaskResult result = TaskResult.builder(TaskResult.Status.DONE, "用了两块")
                .change(Change.of(Change.Kind.ITEM_CONSUMED, "minecraft:oak_planks", 2)).build();
        assertSame(result, store.settling(new GoalRun(2, leaf()), result));
    }

    @Test
    void 按顺序做事的整个目标不再结一遍() {
        reserved.set(List.of(new ReservedItems.Reservation("minecraft:oak_planks", 40)));
        carried.put("minecraft:oak_planks", 0);
        Goal sequence = new Goal("maicraft:sequence", null, null, ParamValues.EMPTY, null, List.of(leaf()), null);
        TaskResult result = TaskResult.builder(TaskResult.Status.DONE, "做完了")
                .change(Change.of(Change.Kind.BLOCK_PLACED, "minecraft:oak_planks", 30)).build();

        assertSame(result, store.settling(new GoalRun(3, sequence), result));
        assertEquals(40, reserved.keep("minecraft:oak_planks"));
    }

    @Test
    void 读不到身上件数时不扣() {
        ReservedItems blind = new ReservedItems(new FakeTags(), () -> null);
        blind.set(List.of(new ReservedItems.Reservation("minecraft:oak_planks", 40)));
        TaskResult result = TaskResult.builder(TaskResult.Status.DONE, "盖好了")
                .change(Change.of(Change.Kind.BLOCK_PLACED, "minecraft:oak_planks", 30)).build();
        assertSame(result, new SettlesReservations(new InMemoryGoalRunStore(), blind).settling(new GoalRun(4, leaf()), result));
        assertEquals(40, blind.keep("minecraft:oak_planks"));
    }
}
