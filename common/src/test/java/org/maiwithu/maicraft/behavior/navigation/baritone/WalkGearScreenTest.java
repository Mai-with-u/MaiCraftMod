// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;

/**
 * 走路收拾挡路的界面：落地备料正用着的背包不关；备料结束后留下的界面、没人用的界面照关。
 */
class WalkGearScreenTest {

    @Test
    void theBackpackLandingPrepIsUsingIsNotClosed() {
        // 出发前把干草块换到快捷栏，背包是备料自己开的、这一刻正用着：走路不当它挡路，不去关。
        Menus menus = new Menus(true, true);
        assertFalse(WalkGear.screenInTheWay(context(menus)));
        assertEquals(0, menus.closeRequests);
    }

    @Test
    void aScreenLeftAfterThePrepEndedIsClosed() {
        // 备料结束、背包还开着，没人再用：走路先关上它，关好前算挡着。
        Menus menus = new Menus(true, false);
        assertTrue(WalkGear.screenInTheWay(context(menus)));
        assertEquals(1, menus.closeRequests);
    }

    @Test
    void nothingInTheWayWhenNoScreenBlocks() {
        Menus menus = new Menus(false, false);
        assertFalse(WalkGear.screenInTheWay(context(menus)));
        assertEquals(0, menus.closeRequests);
    }

    /** 界面替身：挡不挡路、有没有人用都由测试给定；请它关界面时记一次，关不完（还在等确认）。 */
    private static final class Menus implements MenuActions {
        private final boolean blocks;
        private final boolean inUse;
        int closeRequests;

        Menus(boolean blocks, boolean inUse) {
            this.blocks = blocks;
            this.inUse = inUse;
        }

        @Override public boolean blocksWorld(PlayerContext context) { return blocks; }
        @Override public boolean screenInUse(PlayerContext context) { return inUse; }
        @Override public boolean ensureWorldVisible(PlayerContext context) {
            closeRequests++;
            return false;
        }
        @Override public boolean hasPendingTransaction() { return false; }
        @Override public boolean ensureVisible(PlayerContext context) { return true; }
        @Override public void interactionSubmitted(PlayerContext context) {}
        @Override public PendingMenuAction click(PlayerContext context, int slot, int button, ClickType clickType,
                MenuConfirmation confirmation, int timeoutTicks) { return null; }
        @Override public PendingMenuAction swapInventoryToHotbar(PlayerContext context, int sourceInventorySlot,
                int hotbarSlot, int timeoutTicks) { return null; }
        @Override public PendingMenuAction placeRecipe(PlayerContext context, RecipeHolder<?> recipe, boolean shift,
                MenuConfirmation confirmation, int timeoutTicks) { return null; }
        @Override public PendingMenuAction close(PlayerContext context, int timeoutTicks) { return null; }
        @Override public PendingMenuAction closeForTaskBoundary(PlayerContext context, int timeoutTicks,
                String boundaryReason) { return null; }
        @Override public PendingMenuAction poll(PlayerContext context, PendingMenuAction pending) { return pending; }
    }

    // 只带界面替身的角色上下文：走路收拾界面这一步只问界面。
    private static PlayerContext context(MenuActions menus) {
        return new PlayerContext() {
            @Override public LocalPlayer localPlayer() { return null; }
            @Override public ClientLevel level() { return null; }
            @Override public ClientPacketListener connection() { return null; }
            @Override public PlayerInput input() { return null; }
            @Override public InteractionSender interactionSender() { return null; }
            @Override public MenuActions menuActions() { return menus; }
            @Override public long clientTick() { return 10; }
            @Override public boolean isCurrent() { return true; }
            @Override public boolean canInteractThisTick() { return true; }
            @Override public boolean tryClaimInteraction() { return true; }
        };
    }
}
