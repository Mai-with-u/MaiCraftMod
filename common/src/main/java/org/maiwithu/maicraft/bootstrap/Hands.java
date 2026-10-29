// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.inventory.hands.LiveDigToolRule;
import org.maiwithu.maicraft.behavior.interaction.BlockBreaking;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.game.player.InputDriver;

/**
 * 动手的部件：交互动作入口、按住使用键投影、挖一格的动作供给与角色输入——
 * 角色对世界出手的几条路都在这一组。新加一条出手的路，加在这里并传入所在组即可。
 */
record Hands(
        Interactions interactions,
        UseKeyProjection useKeyProjection,
        /** 挖一格的动作供给：原生挖掘走生存需求共用的那套挖掘基础代码，每次要挖一格开一份。 */
        Supplier<BlockBreaking> digging,
        InputDriver inputs,
        /** 挖之前拿哪件工具：挑工具一份，挖格、走近站位、船上挖块都照它换手。 */
        LiveDigToolRule digTools) {

    Hands {
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(useKeyProjection, "useKeyProjection");
        Objects.requireNonNull(digging, "digging");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(digTools, "digTools");
    }
}
