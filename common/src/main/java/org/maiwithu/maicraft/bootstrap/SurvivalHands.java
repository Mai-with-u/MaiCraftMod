// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.interaction.BlockBreaking;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;

/**
 * 生存需求动手的几条路：交互动作入口、挖一格的供给与换手——
 * 被埋挖顶、出岩浆换手喝药、举盾退开、插火把都从这里出手。
 * 新加一条生存需求出手的路，加在这里并传入所在组即可。
 */
record SurvivalHands(
        Interactions interactions,
        /** 挖一格的动作供给：被埋挖顶与换气挖顶共用生存需求那套原生挖掘，每次要挖一格开一份。 */
        Supplier<BlockBreaking> digging,
        /** 换到主手：吃饭换手与插火把换手共用同一套界面搬运。 */
        ClientMovesToMainhand hands) {

    SurvivalHands {
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(digging, "digging");
        Objects.requireNonNull(hands, "hands");
    }
}
