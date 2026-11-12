// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

/**
 * 物品价值分级：腾地方与搜刮战利品容器共用同一把尺，把物品从贵到贱排。
 *
 * <p>腾背包只做可逆的搬动（合并、存网络、塞随身背包、存进容器），不动的东西从垃圾级开始存：
 * 垃圾 → 顺手捡的零碎 → 建材 → 食物 → 有用的；装备、贵重与要留的东西不自动挪。
 * 搜刮战利品容器则反过来按枚举顺序从上到下挑要拿的：要留用的 → 稀罕贵重 → 装备 → 有用的 → 食物，
 * 建材、顺手零碎与垃圾留在箱子里不占格子。
 */
public enum Worth {
    /** 要留的东西：预留的、这个任务正要用的，腾背包不动，搜刮最先拿。 */
    KEPT("needed", true),
    /** 稀有贵重：游戏标了稀有度、带附魔、或是宝石（#c:gems），丢了很难补回来，开箱遇到会通报。 */
    PRECIOUS("precious", true),
    /** 工具、武器、护甲：干活和保命靠它。 */
    GEAR("gear", true),
    /** 有用的：做出来的零件、锭、机器方块，说不准哪天要用的材料。 */
    USEFUL("useful", true),
    /** 能吃的：饿了要靠它。 */
    FOOD("food", true),
    /** 整块建材：能搭能垫，也最容易再弄到。 */
    BUILDING_MATERIAL("building", false),
    /** 顺手捡的零碎：树苗、蘑菇、花、种子、生肉、蜘蛛眼、骨头这类意料之外又没什么用的战利品。 */
    INCIDENTAL("incidental", false),
    /** 垃圾：堆多了一文不值的方块和腐肉。 */
    JUNK("junk", false);

    private final String grade;
    private final boolean worthLooting;

    Worth(String grade, boolean worthLooting) {
        this.grade = grade;
        this.worthLooting = worthLooting;
    }

    /** MCP 结果里写给宿主与模型看的分级名。 */
    public String grade() {
        return grade;
    }

    /** 搜刮战利品容器时这一级要不要拿走：食物及以上拿，建材、零碎与垃圾留下。 */
    public boolean worthLooting() {
        return worthLooting;
    }
}
