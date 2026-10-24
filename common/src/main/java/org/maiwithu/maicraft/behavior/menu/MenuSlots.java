// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

/** 界面布局判定的只读输入：菜单类型、槽位总数、每个槽是不是角色背包；实现接在打开的菜单对象上。 */
public interface MenuSlots {

    /** 菜单类型的注册 ID（不是方块 ID），例如大箱子是 minecraft:generic_9x6、熔炉是 minecraft:furnace；模组界面是它自己的 ID。 */
    String menuTypeId();

    /** 界面里的槽位总数。 */
    int slotCount();

    /** 这个槽位的物品格是不是角色的背包（27 格主背包 + 9 格快捷栏）；判断依据是槽位背后装的是谁的物品格。 */
    boolean playerBacked(int slot);

    /**
     * 这个角色侧槽位在角色背包里的格号：快捷栏 0-8、主背包 9-35，是物品栏数据结构的事实，
     * 与界面加槽的顺序无关（模组菜单不保证主背包的槽排在前）。只对 playerBacked 的槽位有意义。
     */
    int playerInventoryIndex(int slot);
}
