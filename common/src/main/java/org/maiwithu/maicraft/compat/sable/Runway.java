// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import net.minecraft.world.phys.Vec3;

/**
 * 固定翼起降用的一条跑道：跑道头（落地那一头的中线上、地面顶面的高度）、往哪边跑（度，和玩家朝向一样算：0 南、90 西）、
 * 长、宽；附近没有够长的、挑了最长的一片平地迫降的另标出来。沿跑道走了多远、离中线多远都按它算。
 *
 * @param threshold 跑道头：落地那一头中线上的一点，y 是跑道面（站上去脚底）的高度
 * @param heading   沿跑道往前的朝向（度）
 * @param length    长（格）
 * @param width     宽（格）
 * @param forced    迫降挑的：附近没有够长的跑道，这是最长的一片平地
 */
record Runway(Vec3 threshold, double heading, double length, double width, boolean forced) {

    /** 沿跑道往前的单位方向（水平）。 */
    Vec3 direction() {
        double radians = Math.toRadians(heading);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** 往前看时右手那一边的单位方向（水平）。 */
    Vec3 right() {
        double radians = Math.toRadians(heading);
        return new Vec3(-Math.cos(radians), 0, -Math.sin(radians));
    }

    /** 跑道面的高度。 */
    double y() {
        return threshold.y;
    }

    /** 一点沿跑道离跑道头多远：跑道头之前为负，过了跑道头为正。 */
    double along(Vec3 point) {
        Vec3 dir = direction();
        return (point.x - threshold.x) * dir.x + (point.z - threshold.z) * dir.z;
    }

    /** 一点离中线多远：在中线右边为正，左边为负。 */
    double lateral(Vec3 point) {
        Vec3 side = right();
        return (point.x - threshold.x) * side.x + (point.z - threshold.z) * side.z;
    }

    /** 沿跑道 along 格、离中线 lateral 格的那一点（跑道面高度）。 */
    Vec3 at(double along, double lateral) {
        return threshold.add(direction().scale(along)).add(right().scale(lateral));
    }

    /** 一句话：跑道头在哪、朝哪、多长。 */
    String describe() {
        return "跑道头 " + Math.round(threshold.x) + "," + Math.round(threshold.y) + "," + Math.round(threshold.z)
                + "、朝" + compass(heading) + "、长 " + Math.round(length) + " 格";
    }

    // 朝向换成八个方位的说法。
    static String compass(double heading) {
        String[] names = {"南", "西南", "西", "西北", "北", "东北", "东", "东南"};
        int index = (int) Math.floorMod(Math.round(heading / 45.0), 8);
        return names[index];
    }
}
