// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;

/**
 * 压印器界面的布局证明：容器侧恰好四个工作格加四个升级格，全都按机器格核对；
 * 样子对不上的界面证明不了，一格都不点。
 */
class Ae2InscriberLayoutTest {

    // 替身：前 containerCount 格是容器侧，最后 36 格背后是角色背包（压印器界面是前 8 格工作、后 36 格背包）。
    private static MenuSlots slots(int containerCount) {
        int total = containerCount + 36;
        return new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:inscriber"; }
            @Override public int slotCount() { return total; }
            @Override public boolean playerBacked(int slot) { return slot >= containerCount; }
            @Override public int playerInventoryIndex(int slot) { return slot - containerCount; }
        };
    }

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) values.add(i);
        return List.copyOf(values);
    }

    @Test
    void 八格容器侧证明得了_全部按机器格核对() {
        MenuLayout.Layout layout = new Ae2InscriberLayout().classify(slots(8), range(8, 43), range(0, 7));
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, layout);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), supported.containerSlots());
        assertEquals(8, supported.machineSlots().size(), "工作格与升级格都按机器格核对，不当普通存取格搬动");
        assertEquals(36, supported.playerSlots().size());
    }

    @Test
    void 容器侧不是八格时证明不了() {
        assertInstanceOf(MenuLayout.Unsupported.class,
                new Ae2InscriberLayout().classify(slots(4), range(4, 39), range(0, 3)));
    }
}
