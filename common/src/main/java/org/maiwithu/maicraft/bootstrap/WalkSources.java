// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.approach.UnreachableFromHere;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;

/**
 * 把角色送到别处的两条路：先下车再走的走到与内嵌寻路，连同靠近记得的"从这里到不了"——
 * 出行、靠近、生存需求的撤离都从这一组拿走路。新加一条送角色的路，加在这里并传入所在组即可。
 */
record WalkSources(
        /** 走到：走路类请求都走这一份，起步坐在载具上时先按住潜行下来再交内嵌寻路。 */
        WalkTo walksOnFoot,
        /** 内嵌 Baritone：寻路的入口与内件。 */
        BaritoneInternals walkInternals,
        /** 靠近记得的"从这里到不了"：同一个目标运行里各次靠近共用一份。 */
        UnreachableFromHere unreachable) {

    WalkSources {
        Objects.requireNonNull(walksOnFoot, "walksOnFoot");
        Objects.requireNonNull(walkInternals, "walkInternals");
        Objects.requireNonNull(unreachable, "unreachable");
    }
}
