// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;

/**
 * 压印器界面的布局证明：实机菜单 0..3 为升级格、4..39 为角色背包、40..43 为四个工作格，
 * 证明通过后容器侧规范化重排为 [40, 41, 42, 43, 0, 1, 2, 3]（前四格工作格、后四格升级格），且八格全按机器格核对。
 */
class Ae2InscriberLayoutTest {

    // 模拟实机 InscriberMenu：0..upgradeCount-1 是升级格，紧接 36 格角色背包，末尾 workCount 格是工作格。
    private static MenuSlots inscriberSlots(int upgradeCount, int workCount) {
        int playerStart = upgradeCount;
        int playerEndExclusive = upgradeCount + 36;
        int total = playerEndExclusive + workCount;
        return new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:inscriber"; }
            @Override public int slotCount() { return total; }
            @Override public boolean playerBacked(int slot) {
                return slot >= playerStart && slot < playerEndExclusive;
            }
            @Override public int playerInventoryIndex(int slot) { return slot - playerStart; }
        };
    }

    private static List<Integer> range(int from, int toInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int i = from; i <= toInclusive; i++) values.add(i);
        return List.copyOf(values);
    }

    @Test
    void 实机前四格升级后四格工作重排为工作格在前升级格在后_全部按机器格核对() {
        List<Integer> otherSlots = new ArrayList<>();
        otherSlots.addAll(range(0, 3));
        otherSlots.addAll(range(40, 43));
        MenuLayout.Layout layout = new Ae2InscriberLayout().classify(
                inscriberSlots(4, 4), range(4, 39), otherSlots);
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, layout);
        assertEquals(List.of(40, 41, 42, 43, 0, 1, 2, 3), supported.containerSlots(),
                "前四格对齐压印器顶、底、中间、输出四个工作格，后四格为升级卡槽");
        assertEquals(8, supported.machineSlots().size(), "工作格与升级格都按机器格核对，不当普通存取格搬动");
        assertEquals(36, supported.playerSlots().size());
    }

    @Test
    void 容器侧格数不对或工作格位置颠倒时证明不了() {
        assertInstanceOf(MenuLayout.Unsupported.class,
                new Ae2InscriberLayout().classify(inscriberSlots(4, 0), range(4, 39), range(0, 3)));
        assertInstanceOf(MenuLayout.Unsupported.class,
                new Ae2InscriberLayout().classify(inscriberSlots(8, 0), range(8, 43), range(0, 7)));
    }

    @Test
    void 携带网络工具箱多出九个工具箱槽时仍按语义精准识别四工作格与四升级格() {
        // 实机携带 ae2:network_tool 时：0..8 TOOLBOX，9..12 UPGRADE，13..48 角色背包，49..52 四个工作格。
        Map<String, List<Integer>> semantics = Map.of(
                "TOOLBOX", range(0, 8),
                "UPGRADE", List.of(9, 10, 11, 12),
                "INSCRIBER_PLATE_TOP", List.of(49),
                "INSCRIBER_PLATE_BOTTOM", List.of(50),
                "MACHINE_INPUT", List.of(51),
                "MACHINE_OUTPUT", List.of(52));
        Ae2MenuSlots menuSlots = new Ae2MenuSlots() {
            @Override public boolean isAe2MenuOpen() { return true; }
            @Override public List<Integer> slotsOf(String semanticId) {
                return semantics.getOrDefault(semanticId, List.of());
            }
            @Override public boolean sendConfigButton(String settingName, boolean backwards) { return false; }
            @Override public boolean sendSetFilter(int slotIndex, ItemStack stack) { return false; }
            @Override public boolean sendNoArgAction(String actionName) { return false; }
            @Override public boolean sendIntAction(String actionName, int value) { return false; }
            @Override public boolean sendLongAction(String actionName, long value) { return false; }
            @Override public boolean sendBooleanAction(String actionName, boolean value) { return false; }
            @Override public boolean sendStringAction(String actionName, String value) { return false; }
            @Override public boolean sendRawJsonAction(String actionName, String jsonPayload) { return false; }
            @Override public boolean openSubMenu(String menuTypeId) { return false; }
            @Override public boolean returnToParentMenu() { return false; }
        };
        List<Integer> otherWithToolbox = new ArrayList<>();
        otherWithToolbox.addAll(range(0, 12));
        otherWithToolbox.addAll(range(49, 52));
        MenuSlots withToolbox = new MenuSlots() {
            @Override public String menuTypeId() { return "ae2:inscriber"; }
            @Override public int slotCount() { return 53; }
            @Override public boolean playerBacked(int slot) { return slot >= 13 && slot <= 48; }
            @Override public int playerInventoryIndex(int slot) { return slot - 13; }
        };

        MenuLayout.Layout layout = new Ae2InscriberLayout(menuSlots).classify(
                withToolbox, range(13, 48), otherWithToolbox);
        MenuLayout.Supported supported = assertInstanceOf(MenuLayout.Supported.class, layout);
        assertEquals(List.of(49, 50, 51, 52, 9, 10, 11, 12), supported.containerSlots(),
                "携带网络工具箱时排除 9 个 TOOLBOX 槽，仍按顶/底/中/出 + 4 升级槽顺序映射");
        assertEquals(8, supported.machineSlots().size());
        assertEquals(36, supported.playerSlots().size());
    }
}
