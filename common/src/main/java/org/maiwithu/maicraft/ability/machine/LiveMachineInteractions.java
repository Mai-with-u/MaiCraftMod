// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.drops.ClientDropPickup;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 机器交互入口的生产实现：转交给玩家行为层——右键用交互模型，点开机器界面用和开箱子同一个打开动作，
 * 捡地上的产物用捡掉落物的那一套。动作自己做瞄准、提交与逐刻确认。
 */
final class LiveMachineInteractions implements MachineInteractions {

    private final Interactions interactions;
    private final BringsPlayerClose close;
    private final MenuLayouts layouts;
    private final ClientDropPickup drops;
    private final Supplier<PlayerContext> contexts;

    LiveMachineInteractions(Interactions interactions, BringsPlayerClose close, MenuLayouts layouts,
            ClientDropPickup drops, Supplier<PlayerContext> contexts) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.close = Objects.requireNonNull(close, "close");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override public Action useBlock(BlockPos target, InteractionConfirmation confirmation) {
        return interactions.useBlock(target, confirmation);
    }

    // 点开机器界面：走过去能动多少地形按这次任务的许可来，界面认不认得看联动登记的布局。
    @Override public Optional<OutputLook> lookIntoMenu(BlockPos machine, String itemId, boolean collect,
            Permissions permissions) {
        return Optional.of(new MenuOutputLook(itemId, collect,
                new ClientMenuOpening(machine, permissions, layouts, close, interactions, contexts), contexts));
    }

    // 捡地上的产物：一件件点名捡，走过去、等它从地上消失并确认是自己收的。
    @Override public Optional<OutputLook> pickUpFrom(AABB area, String itemId) {
        return Optional.of(new GroundOutputPickup(area, itemId, contexts, drops::pickUpOne));
    }
}
