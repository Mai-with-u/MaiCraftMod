// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.List;

/**
 * 方位说法：全项目统一怎么描述一个东西在角色的哪个方向。
 *
 * <p>相对朝向按角色脸的方向分成八个词（前方、右前方、右侧、右后方、后方、左后方、左侧、左前方），
 * 东南西北按世界坐标分成八个词（北、东北、东、东南、南、西南、西、西北）；两者都给，
 * 用的人挑顺口的。距离与高低差也在这里给词：声音之类给不出精确数字的用远近档位，
 * 看得见的直接给格数。要把相对朝向、罗盘方位与距离一起报时，
 * 用 {@link Bearing} 一次算好，别在调用方里重摆三角函数。
 */
public final class DirectionWords {

    // 从北起顺时针：北、东北、东、东南、南、西南、西、西北。
    private static final String[] COMPASS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
    // 从角色脸的方向起顺时针：前方、右前方、右侧、右后方、后方、左后方、左侧、左前方。
    private static final String[] RELATIVE = {"前方", "右前方", "右侧", "右后方", "后方", "左后方", "左侧", "左前方"};

    /** 相对朝向词按从前到后、先右后左的固定顺序，速写与视图都按这个顺序读。 */
    public static List<String> relativeOrder() {
        return List.of(RELATIVE);
    }

    private DirectionWords() {}

    /** 东西向偏移 dx、南北向偏移 dz 的世界方位词：北是 -Z，东是 +X。 */
    public static String compassOf(double dx, double dz) {
        return COMPASS[sector(Math.atan2(dx, -dz))];
    }

    /**
     * 相对朝向词：目标相对角色脸的方向。facingYawDegrees 用游戏的角色视角角
     * （0 朝南，顺时针增大），dx、dz 是目标相对角色的水平偏移。
     */
    public static String relative(double dx, double dz, float facingYawDegrees) {
        double[] forward = forwardVector(facingYawDegrees);
        int facing = sector(Math.atan2(forward[0], -forward[1]));
        int target = sector(Math.atan2(dx, -dz));
        // 方位角顺时针增大，转过的方位数就是往右偏了多少。
        return RELATIVE[Math.floorMod(target - facing, 8)];
    }

    /** 远近档位：给不出精确格数的声音，或数量太大分组时用。 */
    public static String distanceWord(double blocks) {
        if (blocks <= 4) {
            return "很近";
        }
        if (blocks <= 12) {
            return "近处";
        }
        if (blocks <= 32) {
            return "有点远";
        }
        return "很远";
    }

    /** 高低差词：目标脚底比角色脚底高几格、低几格，或同高。 */
    public static String heightWord(int dy) {
        if (dy == 0) {
            return "同高";
        }
        return dy > 0 ? "高" + dy + "格" : "低" + (-dy) + "格";
    }

    /**
     * 方位三元组：相对朝向词、罗盘方位词、距离（格）一次算好。观察整理与详情视图都从这里取，
     * 不再各自展开同一套三角函数；声音给不出精确格数，拿 {@link #nearness()} 取远近档位。
     */
    public static final class Bearing {

        private final String direction;
        private final String compass;
        private final double blocks;

        private Bearing(String direction, String compass, double blocks) {
            this.direction = direction;
            this.compass = compass;
            this.blocks = blocks;
        }

        public String direction() {
            return direction;
        }

        public String compass() {
            return compass;
        }

        /** 距离（格）：四舍五入取整。 */
        public int distance() {
            return (int) Math.round(blocks);
        }

        /** 远近档位：声音之类给不出精确格数的用，按未取整的距离分档。 */
        public String nearness() {
            return distanceWord(blocks);
        }

        /**
         * 观察整理的统一入口（多数派口径）：目标与角色都按各自所在方块取整来算偏移——
         * 观察接缝交来的本来就是方块位置，角色取脚下方块。实体、设施、告示牌都走这条，距离含高低差。
         */
        public static Bearing byBlock(double selfX, double selfY, double selfZ, float facingYaw,
                                      int targetX, int targetY, int targetZ) {
            return of(targetX - Math.floor(selfX), targetY - Math.floor(selfY), targetZ - Math.floor(selfZ),
                    facingYaw);
        }

        /** 水平的一版：地物与声音只按水平距离说，不含高低差，取整口径同 {@link #byBlock}。 */
        public static Bearing byBlockFlat(double selfX, double selfZ, float facingYaw, int targetX, int targetZ) {
            return of(targetX - Math.floor(selfX), 0, targetZ - Math.floor(selfZ), facingYaw);
        }

        /**
         * 目标按方块中心量（+0.5 修正）的一版：模组的东西与记住的地标实机一直按这个口径报，
         * 数值已由金样测试钉住。收拢时把这套口径原样落在这里写明，
         * 不再让两种取整写法在调用方里悄悄分叉；距离含高低差。
         */
        public static Bearing byBlockCenter(double selfX, double selfY, double selfZ, float facingYaw,
                                            int targetX, int targetY, int targetZ) {
            return of(targetX + 0.5 - selfX, targetY + 0.5 - selfY, targetZ + 0.5 - selfZ, facingYaw);
        }

        /** 同 {@link #byBlockCenter}，只算水平距离：记住的地标与记忆条目都用它。 */
        public static Bearing byBlockCenterFlat(double selfX, double selfZ, float facingYaw,
                                                int targetX, int targetZ) {
            return of(targetX + 0.5 - selfX, 0, targetZ + 0.5 - selfZ, facingYaw);
        }

        private static Bearing of(double dx, double dy, double dz, float facingYaw) {
            return new Bearing(relative(dx, dz, facingYaw), compassOf(dx, dz),
                    Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
    }

    // 游戏的角色视角角转脸的方向向量：0 朝南（+Z），顺时针增大，90 朝西（-X）。
    private static double[] forwardVector(double yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        return new double[]{-Math.sin(radians), Math.cos(radians)};
    }

    // 方位角（从北顺时针的度数）换成八向序号，跨圈取模。
    private static int sector(double radians) {
        int index = (int) Math.round(Math.toDegrees(radians) / 45.0);
        return Math.floorMod(index, 8);
    }
}
