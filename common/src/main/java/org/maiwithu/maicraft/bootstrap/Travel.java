// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.approach.UnreachableFromHere;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.travel.planning.TravelWorldView;
import org.maiwithu.maicraft.behavior.travel.settle.TravelProgressListener;

/**
 * 走到与出行：寻路入口和它的内件、出行现场的只读视图与出行进展的收件口——
 * 把角色从一处送到另一处的东西都在这一组。新加一段送角色的路，加在这里并传入所在组即可。
 * 靠近记得的"从这里到不了"也在这里：同一个目标运行里各次靠近共用一份。
 */
record Travel(
        WalkTo walks,
        BaritoneInternals walkInternals,
        TravelWorldView travelWorld,
        TravelProgressListener travelProgress,
        UnreachableFromHere unreachable) {

    /** 没有目标运行表的清单（离线）：靠近什么都不记。 */
    Travel(WalkTo walks, BaritoneInternals walkInternals, TravelWorldView travelWorld,
           TravelProgressListener travelProgress) {
        this(walks, walkInternals, travelWorld, travelProgress, UnreachableFromHere.none());
    }

    Travel {
        Objects.requireNonNull(walks, "walks");
        Objects.requireNonNull(walkInternals, "walkInternals");
        Objects.requireNonNull(travelWorld, "travelWorld");
        Objects.requireNonNull(travelProgress, "travelProgress");
        Objects.requireNonNull(unreachable, "unreachable");
    }
}
