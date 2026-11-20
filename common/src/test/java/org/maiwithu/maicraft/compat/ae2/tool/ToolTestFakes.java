// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.compat.ae2.Ae2Inscribers;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuSlots;
import org.maiwithu.maicraft.compat.ae2.Ae2Patterns;
import org.maiwithu.maicraft.compat.ae2.Ae2TerminalMenu;
import org.maiwithu.maicraft.compat.ae2.Ae2Terminals;
import org.maiwithu.maicraft.compat.ae2.Ae2Transforms;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 手持工具离线测试共用的轻量替身工厂：构造最小可用的 Ae2Compat 与 PlayerServices，
 * 不依赖真实客户端世界、不用 Unsafe、不用反射。
 */
final class ToolTestFakes {

    private ToolTestFakes() {
    }

    static Ae2Compat compat() {
        return compat(Ae2MenuSlots.NONE);
    }

    static Ae2Compat compat(Ae2MenuSlots menuSlots) {
        Ae2Terminals terminals = new Ae2Terminals() {
            @Override public List<Terminal> near(ClientLevel level, BlockPos center, int radius) {
                return List.of();
            }

            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
                return false;
            }
        };
        Ae2TerminalMenu menus = new Ae2TerminalMenu() {
            @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block,
                    Direction side) {
                return false;
            }

            @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
                return Optional.of(true);
            }

            @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
                return Optional.of(List.of());
            }

            @Override public void takeStack(AbstractContainerMenu menu, long serial) {
            }

            @Override public void takeOne(AbstractContainerMenu menu, long serial) {
            }

            @Override public void putBack(AbstractContainerMenu menu) {
            }
        };
        Ae2Grid grid = new Ae2Grid() {
            @Override public Optional<GridCell> cell(ClientLevel level, BlockPos at) {
                return Optional.empty();
            }

            @Override public boolean partItem(String itemId) {
                return false;
            }

            @Override public boolean cableItem(String itemId) {
                return false;
            }
        };
        return new Ae2Compat(terminals, menus, grid, Ae2Patterns.NONE, Ae2Transforms.NONE, Ae2Inscribers.NONE,
                menuSlots);
    }

    static PlayerServices services(Supplier<PlayerContext> contexts, Map<String, Set<String>> itemTags,
            List<BackpackStack> backpackStacks) {
        Protection protection = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), "self");
        GearChanges noGear = new GearChanges() {
            @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
                return Optional.empty();
            }

            @Override public Optional<Action> takeOff(GearSlotName slot) {
                return Optional.empty();
            }
        };
        BackpackView backpack = new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return backpackStacks;
            }

            @Override public int usedSlots() {
                return backpackStacks.size();
            }

            @Override public int totalSlots() {
                return 36;
            }
        };
        return new PlayerServices(
                contexts,
                (target, permissions) -> null,
                new Interactions(null),
                itemId -> Optional.empty(),
                noGear,
                action -> action,
                (request, permissions) -> null,
                protection,
                itemId -> itemTags.getOrDefault(itemId, Set.of()),
                MenuLayouts.VANILLA,
                new BlockScanService(),
                ServerReads.NONE,
                backpack,
                DigToolRule.HELD);
    }
}
