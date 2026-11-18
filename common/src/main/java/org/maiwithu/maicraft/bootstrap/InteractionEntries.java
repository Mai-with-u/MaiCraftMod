// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.inventory.hands.LiveDigToolRule;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;

/**
 * 角色对世界出手的入口：交互动作入口、按住使用键投影、交互提交、容器界面操作与挑工具——
 * 动手的部件（Hands）与生存需求共用的原生挖掘都从这里造。新加一条出手的入口，加在这里并传入所在组即可。
 */
record InteractionEntries(
        Interactions interactions,
        UseKeyProjection useKeyProjection,
        InteractionSender interactionSender,
        MenuActions menuActions,
        /** 挖之前拿哪件工具：挑工具一份，挖格、走近站位、船上挖块都照它换手。 */
        LiveDigToolRule digTools) {

    InteractionEntries {
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(useKeyProjection, "useKeyProjection");
        Objects.requireNonNull(interactionSender, "interactionSender");
        Objects.requireNonNull(menuActions, "menuActions");
        Objects.requireNonNull(digTools, "digTools");
    }
}
