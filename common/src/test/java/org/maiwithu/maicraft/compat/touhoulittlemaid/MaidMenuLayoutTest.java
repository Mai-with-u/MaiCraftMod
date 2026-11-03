// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;

/** 女仆界面的布局证明：8 种背包的容器侧只列她的储物格；格数对不上、角色背包不在前 36 格都不认。 */
class MaidMenuLayoutTest {

    private static final MaidMenuLayout LAYOUT = new MaidMenuLayout();

    private static MenuLayout.Layout classify(String type, int total) {
        MenuSlots slots = new MenuSlots() {
            @Override public String menuTypeId() { return "touhou_little_maid:" + type; }
            @Override public int slotCount() { return total; }
            @Override public boolean playerBacked(int slot) { return slot < 36; }
            @Override public int playerInventoryIndex(int slot) { return slot; }
        };
        List<Integer> player = IntStream.range(0, 36).boxed().toList();
        List<Integer> other = IntStream.range(36, total).boxed().toList();
        return LAYOUT.classify(slots, player, other);
    }

    @Test
    void 八种界面都认() {
        assertEquals(8, LAYOUT.menuTypes().size());
    }

    @Test
    void 没背包_只有内置六格() {
        MenuLayout.Supported layout = assertInstanceOf(MenuLayout.Supported.class,
                classify("maid_empty_backpack_container", 49));
        assertEquals(List.of(42, 43, 44, 45, 46, 47), layout.containerSlots());
    }

    @Test
    void 大背包_内置六格加三十格_不含装备格与展示位() {
        MenuLayout.Supported layout = assertInstanceOf(MenuLayout.Supported.class,
                classify("maid_big_backpack_container", 79));
        assertEquals(36, layout.containerSlots().size());
        assertTrue(!layout.containerSlots().contains(40) && !layout.containerSlots().contains(48));
        assertEquals(78, layout.containerSlots().getLast());
    }

    @Test
    void 熔炉背包_功能格不列() {
        MenuLayout.Supported layout = assertInstanceOf(MenuLayout.Supported.class,
                classify("maid_furnace_container", 64));
        assertEquals(18, layout.containerSlots().size());
        assertEquals(60, layout.containerSlots().getLast());
    }

    @Test
    void 末影箱背包_角色自己的末影箱不列() {
        MenuLayout.Supported layout = assertInstanceOf(MenuLayout.Supported.class,
                classify("maid_ender_chest_container", 76));
        assertEquals(6, layout.containerSlots().size());
    }

    @Test
    void 格数对不上_不认() {
        assertInstanceOf(MenuLayout.Unsupported.class, classify("maid_small_backpack_container", 56));
    }
}
