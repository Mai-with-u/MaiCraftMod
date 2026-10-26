// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.travel.planning.TravelWorldView;
import org.maiwithu.maicraft.behavior.travel.settle.TravelProgressListener;

/**
 * 走到与出行：寻路入口和它的内件、出行现场的只读视图与出行进展的收件口——
 * 把角色从一处送到另一处的东西都在这一组。新加一段送角色的路，加在这里并传入所在组即可。
 */
record Travel(
        WalkTo walks,
        BaritoneInternals walkInternals,
        TravelWorldView travelWorld,
        TravelProgressListener travelProgress) {

    Travel {
        Objects.requireNonNull(walks, "walks");
        Objects.requireNonNull(walkInternals, "walkInternals");
        Objects.requireNonNull(travelWorld, "travelWorld");
        Objects.requireNonNull(travelProgress, "travelProgress");
    }
}
