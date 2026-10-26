// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Objects;

/**
 * machine_run 给的 item 对这台机器是什么：大多数机器是"要做出的东西"，按配方备料投料；
 * 发电机这类烧燃料的机器，item 就是要投进去烧的燃料、count 是投几件，不查配方；
 * 太阳能、风力这类不吃料的发电机没有燃料格，投不进去也不用投。
 *
 * @param kind 哪一种
 * @param note 给 LLM 看的一句话：烧燃料的写收哪些燃料，不吃料的写它靠什么发电；按配方的为空
 */
public record FuelUse(Kind kind, String note) {

    /** 按配方投料：大多数机器。 */
    public static final FuelUse RECIPE = new FuelUse(Kind.RECIPE, "");

    public FuelUse {
        Objects.requireNonNull(kind, "kind");
        note = note == null ? "" : note;
    }

    /** 烧燃料：item 是要投的燃料，note 写收哪些燃料。 */
    public static FuelUse fuel(String note) {
        return new FuelUse(Kind.FUEL, note);
    }

    /** 不吃料：note 写它靠什么发电、为什么不用投。 */
    public static FuelUse noFuel(String note) {
        return new FuelUse(Kind.NO_FUEL, note);
    }

    /** item 怎么理解。 */
    public enum Kind {
        /** item 是要做出的东西，按配方备料投料。 */
        RECIPE,
        /** item 是要投进去烧的燃料。 */
        FUEL,
        /** 机器不吃料，没有可投的格子。 */
        NO_FUEL
    }
}
