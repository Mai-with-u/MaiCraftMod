// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.calc.StandingDry;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 出行目的地：解析完成后、交给走到的一段导航要求——去哪个位置、高度核实过没有、容差多少格。
 *
 * <p>高度纪律在这里落地：坐标只给了 x/z（或方向走多远）时高度没核实，
 * 目的地只约束那一柱列，到了附近再找能站的格子；不把没核实的高度直接交给寻路，
 * 免得寻路对着永远到不了的高度空转。名字记得的地点与观察编号都带当时亲眼确认过的高度，算核实过。
 *
 * @param position        目的地位置；高度没核实时 y 无意义，只为记录调用方给的值
 * @param heightConfirmed 高度是否核实过（给全了 y、地标或观察编号解析出来的）
 * @param radius          到达容差，单位格；0 表示要站进那一格
 * @param arrivalNote     目的地不是调用方指的那一格时带的一句话（开阔水挪到岸格：目标在水中央、落到了哪、偏差几格），
 *                        到达结果原样带上；普通目的地为 null
 */
public record TravelDestination(WorldPosition position, boolean heightConfirmed, double radius, String arrivalNote) {

    public TravelDestination {
        if (radius < 0) {
            throw new IllegalArgumentException("到达容差不能为负：" + radius);
        }
        arrivalNote = arrivalNote == null || arrivalNote.isBlank() ? null : arrivalNote;
    }

    /** 高度核实过的目的地：给全了 y 的坐标、记得的地点、观察编号指向的东西。 */
    public static TravelDestination confirmed(WorldPosition position, double radius) {
        return confirmed(position, radius, null);
    }

    /** 高度核实过、但不是调用方指的那一格的目的地：带上到达结果里要写的偏差说明。 */
    public static TravelDestination confirmed(WorldPosition position, double radius, String arrivalNote) {
        return new TravelDestination(position, true, radius, arrivalNote);
    }

    /** 高度没核实的目的地：只有 x/z（或方向走多远算出来的落点），先走到那一柱列。 */
    public static TravelDestination anyHeight(int x, int z, double radius, String dimension) {
        return new TravelDestination(new WorldPosition(x, 0, z, dimension), false, radius, null);
    }

    /** 编译成走到用的导航目标：高度核实过按位置与容差走，没核实过只走到那一柱列；到了要站在干地上才算到。 */
    public NavGoal navGoal() {
        NavGoal goal;
        if (!heightConfirmed) {
            goal = NavGoal.column(position.x(), position.z(), radius);
        } else {
            BlockPos cell = new BlockPos(position.x(), position.y(), position.z());
            goal = radius <= 0 ? NavGoal.exact(cell) : NavGoal.near(cell, radius);
        }
        // 出行是"去站到那儿"：水里漂进范围、泡在浅水里站着都不算到，
        // 与乘船腿的出发点共用同一份干地事实，不出现走路说到到了、船说脚下是水的死结。
        return new StandingDry(goal);
    }

    /** 目的地那一格；高度没核实时 y 只是占位（0），量剩余距离用 {@link #aimFrom}。 */
    public BlockPos center() {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    /**
     * 从脚下量剩余距离用的终点：高度核实过就是目的地那一格；只给了 x/z 时按脚下这层算，只比水平远近。
     * 不然只给 x/z 的出行没走到时，结果里会把占位的 y=0 当真，写成"低 64 格"，误导调用方往下挖。
     */
    public BlockPos aimFrom(BlockPos feet) {
        return heightConfirmed ? center() : new BlockPos(position.x(), feet.getY(), position.z());
    }

    /** 给日志与结果的一句话，例如"坐标 (120, 64, -80) 附近 2 格"。 */
    public String describe() {
        String place = heightConfirmed
                ? "坐标 (" + position.x() + ", " + position.y() + ", " + position.z() + ")"
                : "（" + position.x() + ", " + position.z() + "）那一列";
        return place + (radius > 0 ? " 附近 " + (int) Math.ceil(radius) + " 格" : "");
    }
}
