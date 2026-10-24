// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/**
 * 绑定无线终端的下一步怎么点：光标、绑定格、产出格、角色侧各是什么样子，就点哪一格；
 * 点不下去的（光标被占、身上没终端、认不出格子、背包放不下）写明原因。
 */
class BindingPlanTest {

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String TERMINAL = "minecraft:torch";

    private static SlotSnapshot terminal() {
        return SlotSnapshot.of(new ItemStack(Items.TORCH));
    }

    private static BindingPlan.Input input(SlotSnapshot cursor, List<SlotSnapshot> playerSnapshots,
            Optional<SlotSnapshot> bindingInput, Optional<SlotSnapshot> bindingOutput,
            boolean cursorIsLinkedCopy, boolean outputIsLinkedCopy) {
        List<Integer> playerSlotIds = List.of(3, 4, 5);
        return new BindingPlan.Input(cursor, playerSlotIds, playerSnapshots,
                Optional.of(1), bindingInput, Optional.of(2), bindingOutput,
                TERMINAL, cursorIsLinkedCopy, outputIsLinkedCopy);
    }

    @Test
    void 空手_身上有终端_先拿起来() {
        BindingPlan.Step step = BindingPlan.next(input(SlotSnapshot.empty(),
                List.of(terminal(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.empty(), false, false));
        assertEquals(3, assertInstanceOf(BindingPlan.PickUp.class, step).slotId());
    }

    @Test
    void 手里拿着终端_放进绑定格() {
        BindingPlan.Step step = BindingPlan.next(input(terminal(),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.empty(), false, false));
        assertEquals(1, assertInstanceOf(BindingPlan.PutIn.class, step).slotId());
    }

    @Test
    void 终端在绑定格里_等副本出现() {
        BindingPlan.Step step = BindingPlan.next(input(SlotSnapshot.empty(),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.of(terminal()), Optional.empty(), false, false));
        assertInstanceOf(BindingPlan.WaitCopy.class, step);
    }

    @Test
    void 产出格出现副本_拿回来() {
        BindingPlan.Step step = BindingPlan.next(input(SlotSnapshot.empty(),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.of(terminal()), false, true));
        assertEquals(2, assertInstanceOf(BindingPlan.TakeCopy.class, step).slotId());
    }

    @Test
    void 手里拿着副本_放进空着的角色格() {
        BindingPlan.Step step = BindingPlan.next(input(terminal(),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.empty(), true, false));
        assertEquals(3, assertInstanceOf(BindingPlan.PutDown.class, step).slotId());
    }

    @Test
    void 背包放不下副本_承认放不下() {
        BindingPlan.Step step = BindingPlan.next(input(terminal(),
                List.of(terminal(), SlotSnapshot.of(new ItemStack(Items.STONE)), terminal()),
                Optional.empty(), Optional.empty(), true, false));
        assertInstanceOf(BindingPlan.Blocked.class, step);
    }

    @Test
    void 光标上挂着别的东西_不动手() {
        BindingPlan.Step step = BindingPlan.next(input(SlotSnapshot.of(new ItemStack(Items.DIRT)),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.empty(), false, false));
        assertInstanceOf(BindingPlan.Blocked.class, step);
    }

    @Test
    void 身上没终端_绑定格也是空的_如实说找不到() {
        BindingPlan.Step step = BindingPlan.next(input(SlotSnapshot.empty(),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.empty(), false, false));
        assertInstanceOf(BindingPlan.Blocked.class, step);
    }

    @Test
    void 认不出绑定格_点不下去() {
        BindingPlan.Input noSlots = new BindingPlan.Input(terminal(), List.of(3, 4, 5),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.empty(), Optional.of(2), Optional.empty(),
                TERMINAL, false, false);
        assertInstanceOf(BindingPlan.Blocked.class, BindingPlan.next(noSlots));
    }

    @Test
    void 产出格有东西但还没带上绑定位置_照旧等() {
        BindingPlan.Step step = BindingPlan.next(input(SlotSnapshot.empty(),
                List.of(SlotSnapshot.empty(), SlotSnapshot.empty(), SlotSnapshot.empty()),
                Optional.empty(), Optional.of(terminal()), false, false));
        assertInstanceOf(BindingPlan.WaitCopy.class, step);
    }
}
