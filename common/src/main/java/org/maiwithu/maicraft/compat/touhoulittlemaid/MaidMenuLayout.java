// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;

/**
 * 女仆界面的布局证明，8 种背包各一种菜单（车万女仆 1.5.3 的加槽顺序）：0–35 角色背包与快捷栏；
 * 36–39 她的头胸腿脚、40 主手、41 副手（装备格，不列进容器侧，改装备按槽号直接点）；42–47 她的内置 6 格；
 * 48 是和 47 同一格的展示位（不列，免得一格算两遍）；49 起是背包扩展的储物格：小 6、中 18、大 30，
 * 工作台、熔炉、储罐背包 12 格，后面跟着合成格、燃料格、流体格这些功能格（不列）；末影箱背包 49–75 是角色自己的末影箱（不列）。
 * 格数对不上就是界面变了，一格都不点。
 */
final class MaidMenuLayout implements MenuLayoutProof {

    /** 角色背包与快捷栏的格数。 */
    static final int PLAYER_SLOTS = 36;
    /** 她的装备格：头、胸、腿、脚、主手、副手。 */
    static final int HEAD = 36;
    static final int CHEST = 37;
    static final int LEGS = 38;
    static final int FEET = 39;
    static final int MAINHAND = 40;
    static final int OFFHAND = 41;
    /** 内置 6 格从 42 起。 */
    private static final int BUILT_IN_FIRST = 42;
    private static final int BUILT_IN_SLOTS = 6;
    /** 背包扩展的储物格从 49 起。 */
    private static final int BACKPACK_FIRST = 49;

    /** 每种菜单：背包扩展有几格储物格、整个界面一共几格。 */
    private record Shape(int storage, int total) {}

    private static final Map<String, Shape> SHAPES = Map.of(
            TouhouLittleMaidCompat.MOD_ID + ":maid_empty_backpack_container", new Shape(0, 49),
            TouhouLittleMaidCompat.MOD_ID + ":maid_small_backpack_container", new Shape(6, 55),
            TouhouLittleMaidCompat.MOD_ID + ":maid_middle_backpack_container", new Shape(18, 67),
            TouhouLittleMaidCompat.MOD_ID + ":maid_big_backpack_container", new Shape(30, 79),
            // 工作台背包：12 格储物，后面 1 格产物 + 9 格合成格。
            TouhouLittleMaidCompat.MOD_ID + ":maid_crafting_table_backpack_container", new Shape(12, 71),
            // 熔炉背包：12 格储物，后面原料、燃料、产物 3 格。
            TouhouLittleMaidCompat.MOD_ID + ":maid_furnace_container", new Shape(12, 64),
            // 储罐背包：12 格储物，后面流体进出 2 格。
            TouhouLittleMaidCompat.MOD_ID + ":maid_tank_container", new Shape(12, 63),
            // 末影箱背包：后面 27 格是角色自己的末影箱，不是她的东西。
            TouhouLittleMaidCompat.MOD_ID + ":maid_ender_chest_container", new Shape(0, 76));

    @Override public Set<String> menuTypes() {
        return SHAPES.keySet();
    }

    // 角色侧必须正好是前 36 格、总格数和这种背包对得上，才按槽号分两侧。
    @Override public MenuLayout.Layout classify(MenuSlots slots, List<Integer> playerSlots, List<Integer> otherSlots) {
        Shape shape = SHAPES.get(slots.menuTypeId());
        if (shape == null) return new MenuLayout.Unsupported("不是女仆的界面：" + slots.menuTypeId());
        if (!playerSlots.equals(IntStream.range(0, PLAYER_SLOTS).boxed().toList())) {
            return new MenuLayout.Unsupported("女仆界面的角色背包不在前 36 格，界面变了");
        }
        if (slots.slotCount() != shape.total()) {
            return new MenuLayout.Unsupported("女仆界面的格数对不上（" + slots.slotCount() + " 格，应为 " + shape.total() + " 格）");
        }
        List<Integer> container = new ArrayList<>();
        for (int i = 0; i < BUILT_IN_SLOTS; i++) container.add(BUILT_IN_FIRST + i);
        for (int i = 0; i < shape.storage(); i++) container.add(BACKPACK_FIRST + i);
        return new MenuLayout.Supported(List.copyOf(playerSlots), container, Set.of());
    }
}
