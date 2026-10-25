// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

/**
 * 挖一格掉要的东西的机会：每格掉的机会，掉了平均几件。矿石、石头这类一挖就掉的是"必掉"；
 * 砾石出燧石、草出种子、树叶出树苗是概率掉落，挖一块不出是常态，要按期望放大挖的块数。
 * 掉落线索点名、原版对照里又没有的方块，掉没掉说不准，按每格一件估、照概率掉落那样不因一块没出就放弃。
 *
 * @param chance    每挖一格掉它的机会，0 到 1（已按手上工具的时运算过）
 * @param meanCount 掉了的话平均几件（草出种子、树叶出木棍不止一件）
 * @param guessed   说不准：线索点名的方块，原版对照里不知道它掉不掉、掉多少
 */
public record DropOdds(double chance, double meanCount, boolean guessed) {

    /** 一挖就掉：矿石、石头、泥土这类，按每格一件估。 */
    public static final DropOdds CERTAIN = new DropOdds(1, 1, false);
    /** 线索点名、对照里没有：掉没掉说不准，按每格一件估。 */
    public static final DropOdds GUESSED = new DropOdds(1, 1, true);

    public DropOdds {
        if (chance < 0 || chance > 1) throw new IllegalArgumentException("掉的机会要在 0 到 1 之间：" + chance);
        if (meanCount <= 0) throw new IllegalArgumentException("掉了平均几件要大于 0：" + meanCount);
    }

    /** 一挖就掉、不用碰运气：矿石这类，以及时运 III 挖砾石必出燧石。 */
    public boolean certain() {
        return !guessed && chance >= 1;
    }

    /** 平均每挖一格得几件：按它算要挖多少块。 */
    public double perBlock() {
        return chance * meanCount;
    }

    /** 说给 LLM 听的机会：例如"每块 10%"；说不准的写明说不准。 */
    public String describe() {
        if (guessed) return "掉没掉说不准";
        return "每块 " + trim(chance * 100) + "%" + (meanCount > 1 ? "、出了平均 " + trim(meanCount) + " 件" : "");
    }

    // 数字给人看：整数不带小数点，其余留一位（12.5、14.3、0.5）。
    private static String trim(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }
}
