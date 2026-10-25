// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/**
 * 绑定全链的离线复演：一个替身界面按 AE2 访问点菜单自己的结算方式行事——放进绑定格的可绑定物品
 * 在服务端被收下的同一刻就地结成产出格里的绑定副本，绑定格随即又空回去——逐步问绑定计划下一步点哪、
 * 按点击后的现场判生效没有，断言「拿起 → 放进 → 副本出现 → 取回 → 放回背包」整条链走得完。
 * 第七轮实机里绑定全程无报文、第六轮里放进绑定格"点了一直没动静"，复演说明断点就在
 * "放进"那一下的生效判定：AE2 即时结算后绑定格空回去，只看绑定格会把结成的生效误判成没动静。
 */
class WirelessBindingChainTest {

    /** 计划按物品 ID 认终端：替身用火把当终端，ID 取替身自己的注册 ID；启动后才能问注册表。 */
    private static String terminalId() {
        return BuiltInRegistries.ITEM.getKey(Items.TORCH).toString();
    }
    /** 角色侧替身格号：界面上角色那 36 格里取前四格用。 */
    private static final List<Integer> PLAYER_SLOTS = List.of(9, 10, 11, 12);
    private static final int INPUT_SLOT = 1;
    private static final int OUTPUT_SLOT = 2;

    @BeforeAll static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // 替身界面此刻的样子：光标、角色侧四格、绑定格、产出格；产出格上是不是绑定副本由结算时记下的标记回答。
    private static final class FakeMenu {
        SlotSnapshot cursor = SlotSnapshot.empty();
        final List<SlotSnapshot> playerSlots;
        SlotSnapshot input = SlotSnapshot.empty();
        SlotSnapshot output = SlotSnapshot.empty();
        boolean outputIsLinkedCopy;

        FakeMenu(ItemStack terminal) {
            // 角色侧要能逐格改：放回背包、拿起都写这一列。
            playerSlots = new ArrayList<>(List.of(SlotSnapshot.of(terminal), SlotSnapshot.empty(),
                    SlotSnapshot.empty(), SlotSnapshot.empty()));
        }

        BindingPlan.Input planInput() {
            // 角色侧每格上是不是绑定好的副本，替身按物品认：结算写上的另一种物品就是副本。
            List<Boolean> linked = new ArrayList<>();
            for (SlotSnapshot snapshot : playerSlots) linked.add(isLinkedCopy(snapshot));
            return new BindingPlan.Input(cursor, PLAYER_SLOTS, playerSlots, linked,
                    Optional.of(INPUT_SLOT), Optional.of(input),
                    Optional.of(OUTPUT_SLOT), Optional.of(output),
                    terminalId(), isLinkedCopy(cursor), outputIsLinkedCopy);
        }

        // 绑定副本在替身里是另一种物品：只有服务端结算写上的东西才认得出"绑好了"。
        private static boolean isLinkedCopy(SlotSnapshot snapshot) {
            return !snapshot.isEmpty() && snapshot.stack().is(Items.GLOWSTONE_DUST);
        }

        /**
         * 服务端结算这一下点击，按 AE2 访问点菜单自己的方式：
         * 可绑定物品进了绑定格的同一刻就地结算——写上绑定位置、产出副本、绑定格空回去。
         * 服务端不收（返回 false）时现场一动不动。
         */
        boolean settle(int slotId) {
            if (slotId == INPUT_SLOT && !cursor.isEmpty() && BindingPlan.itemId(cursor).equals(terminalId())) {
                cursor = SlotSnapshot.empty();
                input = SlotSnapshot.empty();
                output = SlotSnapshot.of(new ItemStack(Items.GLOWSTONE_DUST));
                outputIsLinkedCopy = true;
                return true;
            }
            return false;
        }
    }

    /** 一下点击的起止：点之前那格与光标各是什么样子，点完之后各自变成什么。 */
    private static boolean clickAndJudge(FakeMenu menu, int slotId, SlotSnapshot slotBefore,
            SlotSnapshot cursorBefore) {
        boolean settledToCopy = menu.outputIsLinkedCopy;
        SlotSnapshot slotAfter = slotAt(menu, slotId);
        SlotSnapshot cursorAfter = menu.cursor;
        return WirelessBinding.clickApplied(slotBefore, cursorBefore, slotAfter, cursorAfter, settledToCopy);
    }

    private static SlotSnapshot slotAt(FakeMenu menu, int slotId) {
        if (slotId == INPUT_SLOT) return menu.input;
        if (slotId == OUTPUT_SLOT) return menu.output;
        return menu.playerSlots.get(PLAYER_SLOTS.indexOf(slotId));
    }

    private static void putIntoSlot(FakeMenu menu, int slotId, SlotSnapshot snapshot) {
        if (slotId == INPUT_SLOT) menu.input = snapshot;
        else if (slotId == OUTPUT_SLOT) {
            menu.output = snapshot;
            menu.outputIsLinkedCopy = FakeMenu.isLinkedCopy(snapshot);
        } else menu.playerSlots.set(PLAYER_SLOTS.indexOf(slotId), snapshot);
    }

    @Test
    void 绑定全链走得完_放进即结算的那一下也算生效() {
        FakeMenu menu = new FakeMenu(new ItemStack(Items.TORCH));

        // 拿起：计划让从背包那格拿起终端，点完那格空了、光标上是它。
        BindingPlan.Step step = BindingPlan.next(menu.planInput());
        BindingPlan.PickUp pickUp = assertInstanceOf(BindingPlan.PickUp.class, step);
        SlotSnapshot before = slotAt(menu, pickUp.slotId());
        SlotSnapshot cursorBefore = menu.cursor;
        putIntoSlot(menu, pickUp.slotId(), SlotSnapshot.empty());
        menu.cursor = before;
        assertTrue(clickAndJudge(menu, pickUp.slotId(), before, cursorBefore), "拿起生效了");

        // 放进：AE2 当刻结算，绑定格空回去、副本出现在产出格；这一下必须判成生效，链才走得下去。
        step = BindingPlan.next(menu.planInput());
        BindingPlan.PutIn putIn = assertInstanceOf(BindingPlan.PutIn.class, step);
        before = slotAt(menu, putIn.slotId());
        cursorBefore = menu.cursor;
        assertTrue(menu.settle(putIn.slotId()), "替身按 AE2 的方式结算了这一下");
        assertTrue(clickAndJudge(menu, putIn.slotId(), before, cursorBefore),
                "放进即被结算成产出格副本：这一下生效了，不是没动静");

        // 副本出现了：计划让取回，点完产出格空了、光标上是绑定好的副本。
        step = BindingPlan.next(menu.planInput());
        BindingPlan.TakeCopy takeCopy = assertInstanceOf(BindingPlan.TakeCopy.class, step);
        assertEquals(OUTPUT_SLOT, takeCopy.slotId());
        before = slotAt(menu, takeCopy.slotId());
        cursorBefore = menu.cursor;
        putIntoSlot(menu, takeCopy.slotId(), SlotSnapshot.empty());
        menu.cursor = before;
        assertTrue(clickAndJudge(menu, takeCopy.slotId(), before, cursorBefore), "取回副本生效了");

        // 放回背包：副本进了角色侧的空格，光标空了。
        step = BindingPlan.next(menu.planInput());
        BindingPlan.PutDown putDown = assertInstanceOf(BindingPlan.PutDown.class, step);
        before = slotAt(menu, putDown.slotId());
        cursorBefore = menu.cursor;
        putIntoSlot(menu, putDown.slotId(), cursorBefore);
        menu.cursor = SlotSnapshot.empty();
        assertTrue(clickAndJudge(menu, putDown.slotId(), before, cursorBefore), "副本放回背包生效了");
        assertTrue(FakeMenu.isLinkedCopy(slotAt(menu, putDown.slotId())), "身上这份带上了绑定：绑定真的落上了");

        // 全链收尾：计划宣告做完，可以去关界面、按身上的物品核对绑定。
        assertInstanceOf(BindingPlan.Finished.class, BindingPlan.next(menu.planInput()));
    }

    @Test
    void 服务端不收放进的那一下_判定仍是没生效() {
        FakeMenu menu = new FakeMenu(new ItemStack(Items.TORCH));

        // 先把终端拿在手上（跳过拿起的结算细节）。
        BindingPlan.PickUp pickUp = assertInstanceOf(BindingPlan.PickUp.class, BindingPlan.next(menu.planInput()));
        menu.playerSlots.set(0, SlotSnapshot.empty());
        menu.cursor = SlotSnapshot.of(new ItemStack(Items.TORCH));

        // 服务端不收：现场一动不动（替身不去结算），判定必须仍是没生效。
        BindingPlan.PutIn putIn = assertInstanceOf(BindingPlan.PutIn.class, BindingPlan.next(menu.planInput()));
        assertFalse(clickAndJudge(menu, putIn.slotId(), slotAt(menu, putIn.slotId()), menu.cursor),
                "光标没空，这一下就是没生效");
    }

    @Test
    void 放进后被结算的现场_按只看绑定格的旧判定会误报没动静() {
        // 复演第七轮前 binding 断链的那一幕：放进生效后绑定格与点之前一样是空的、光标也空了，
        // 旧判定（不看产出格）对这样的现场给不出"生效"；新判定靠产出格里的绑定副本认出它。
        SlotSnapshot empty = SlotSnapshot.empty();
        SlotSnapshot terminal = SlotSnapshot.of(new ItemStack(Items.TORCH));
        assertFalse(WirelessBinding.clickApplied(empty, terminal, empty, empty, false),
                "旧判定：绑定格空回去、光标空了，只能算没动静");
        assertTrue(WirelessBinding.clickApplied(empty, terminal, empty, empty, true),
                "新判定：产出格里出现了绑定副本，这一下就是生效了");
    }
}
