// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;

/**
 * 一格方块或流体受不受「结构保护区」保护、是哪一路挡的：独立于圈地与额外保护地标，
 * 用于在自动取材、舀水、寻路清障、箱顶挖盖、收回工作站与纯坐标破坏性操作时，
 * 避开已登记的在役建筑、在役世界机器与耕地非无限灌溉水源；不拦容器取存与建筑内成熟作物收割。
 *
 * @param route 哪一路结构保护挡的；不受保护为 {@link Route#NONE}
 * @param name  命中的建筑档案名或机器档案名；不受保护或耕地灌溉水源为 null
 */
public record StructureProtection(Route route, String name) {

    /** 不在结构保护区里。 */
    public static final StructureProtection NONE = new StructureProtection(Route.NONE, null);

    /** 属于耕地灌溉用的非无限水水源。 */
    public static final StructureProtection FARMLAND_WATER =
            new StructureProtection(Route.FARMLAND_IRRIGATION, null);

    /** 结构保护区的三类来源。 */
    public enum Route {
        /** 不在结构保护区里。 */
        NONE,
        /** 属于已登记且未退役的建筑档案。 */
        BUILDING,
        /** 属于在役世界机器档案（含机器里的流体源格）。 */
        MACHINE,
        /** 属于耕地周围的非无限水灌溉水源。 */
        FARMLAND_IRRIGATION
    }

    public StructureProtection {
        Objects.requireNonNull(route, "route");
    }

    /** 命中已登记的建筑档案。 */
    public static StructureProtection building(String archiveName) {
        return new StructureProtection(Route.BUILDING, Objects.requireNonNull(archiveName, "archiveName"));
    }

    /** 命中在役世界机器档案。 */
    public static StructureProtection machine(String archiveName) {
        return new StructureProtection(Route.MACHINE, Objects.requireNonNull(archiveName, "archiveName"));
    }

    /** 这一格是否受结构保护区保护。 */
    public boolean protects() {
        return route != Route.NONE;
    }

    /** 是哪一路结构保护挡的，说成一小句；不受保护给空串。 */
    public String describe() {
        return switch (route) {
            case NONE -> "";
            case BUILDING -> "属于已登记的建筑「" + name + "」";
            case MACHINE -> "属于在役机器「" + name + "」";
            case FARMLAND_IRRIGATION -> "是周围耕地的灌溉水源（非无限水，舀走后不会自动再生）";
        };
    }
}
