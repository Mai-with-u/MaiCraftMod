// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.HeldItemMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 身边能连上 ME 网络的终端，以及怎么到它跟前：身上绑过访问点的无线终端（拿到主手右键打开，不用走路），
 * 附近装着的 ME 终端（走过去点在面板上打开）。取货、存东西、腾地方都从这里认终端、开终端，认法只写这一份。
 *
 * <p>装着的终端只用自己或自家人的：问保护判断里"存储能不能取用"那一条，归属没问清的不用。
 * 打开后看到的网络连没连上、网络存货记进同一份 {@link SeenNetworkStock}，取货报价和挑终端都看它。
 */
final class NearbyTerminals {

    private final Ae2Compat compat;
    private final PlayerServices services;
    private final SeenNetworkStock seen;
    private final Supplier<Instant> clock;

    NearbyTerminals(Ae2Compat compat, PlayerServices services, SeenNetworkStock seen, Supplier<Instant> clock) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 身上（主背包、快捷栏、副手）第一件绑过访问点的无线终端的物品 ID；角色不在世界里或没带给空。 */
    Optional<String> carriedWireless() {
        PlayerContext player = services.context().get();
        if (player == null || player.localPlayer() == null) return Optional.empty();
        return compat.carriedWirelessTerminal(player.localPlayer());
    }

    /**
     * center 周围 radius 格内装着的终端，带着离 center 多远、能不能用、上次看到的网络存货；
     * 只看已加载区块，角色不在世界里给空表。
     */
    List<TerminalChooser.Candidate> around(BlockPos center, int radius, Permissions permissions) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) return List.of();
        ClientLevel level = player.level();
        String dimension = dimensionOf(level);
        Instant now = clock.get();
        List<TerminalChooser.Candidate> candidates = new ArrayList<>();
        for (Ae2Terminals.Terminal terminal : compat.terminalsNear(level, center, radius)) {
            candidates.add(new TerminalChooser.Candidate(terminal, distance(center, terminal),
                    usable(level, dimension, terminal, permissions), seen.lastSeen(dimension, terminal, now)));
        }
        return candidates;
    }

    /**
     * 动手前再核对报价时挑的那一台：那一格那一面还装着终端、还能用才给。终端被拆、换了部件就忘掉它的记录，
     * 免得下次照着旧数去找；报价之后才问清是别人的（例如刚知道是别人放的）同样不给，引擎换路。
     */
    Optional<Ae2Terminals.Terminal> stillUsable(BlockPos block, Direction side, Permissions permissions) {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null) return Optional.empty();
        ClientLevel level = player.level();
        String dimension = dimensionOf(level);
        Optional<Ae2Terminals.Terminal> terminal = compat.terminalsNear(level, block, 0).stream()
                .filter(found -> found.side() == side)
                .findFirst();
        if (terminal.isEmpty()) {
            seen.forget(dimension, block, side);
            return Optional.empty();
        }
        return terminal.filter(found -> usable(level, dimension, found, permissions));
    }

    /** 角色此刻所在的维度；不在世界里为空。 */
    Optional<String> dimension() {
        PlayerContext player = services.context().get();
        return player == null || player.level() == null ? Optional.empty() : Optional.of(dimensionOf(player.level()));
    }

    /** 走到这台装着的终端前点开；走过去能动多少地形按这次任务的许可来。 */
    TerminalAccess placed(Ae2Terminals.Terminal terminal, String dimension, Permissions permissions) {
        return new Placed(terminal, dimension, permissions);
    }

    /** 拿出身上这件无线终端打开。 */
    TerminalAccess wireless(String itemId) {
        return new Wireless(itemId);
    }

    // 能不能用：问保护判断里"存储能不能取用"那一条，自己或自家人放的才行，归属没问清的不用。
    private boolean usable(ClientLevel level, String dimension, Ae2Terminals.Terminal terminal,
            Permissions permissions) {
        BlockPos block = terminal.block();
        String blockType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(block).getBlock()).toString();
        return services.protection().mayUseStorage(
                new WorldPosition(block.getX(), block.getY(), block.getZ(), dimension), blockType,
                permissions.protectedLandmarks());
    }

    // 离 center 那一格的中心多远，量到终端面板的中心。
    private static double distance(BlockPos center, Ae2Terminals.Terminal terminal) {
        var panel = terminal.panel().getCenter();
        double dx = panel.x - (center.getX() + 0.5);
        double dy = panel.y - (center.getY() + 0.5);
        double dz = panel.z - (center.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String dimensionOf(ClientLevel level) {
        return level.dimension().location().toString();
    }

    /** 装在线缆上的一台终端：走过去、点在面板上打开（不点线缆芯）。 */
    private final class Placed implements TerminalAccess {
        private final Ae2Terminals.Terminal terminal;
        private final String dimension;
        private final Permissions permissions;

        Placed(Ae2Terminals.Terminal terminal, String dimension, Permissions permissions) {
            this.terminal = terminal;
            this.dimension = dimension;
            this.permissions = permissions;
        }

        // 走过去能动多少地形按这次任务的许可来；靠近与右键都对准面板，不点线缆芯。
        @Override public MenuOpening opening() {
            return new ClientMenuOpening(terminal.block(), terminal.panel(), permissions,
                    services.menuLayouts(), services.approach(), services.interactions(), services.context());
        }

        @Override public boolean belongs(AbstractContainerMenu raw, ClientLevel level) {
            return compat.menuBelongsTo(raw, level, terminal.block(), terminal.side());
        }

        @Override public String describe() {
            return terminal.describe();
        }

        @Override public void remember(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, boolean complete) {
            seen.saw(dimension, terminal, linked, stock, clock.get(), complete);
        }
    }

    /** 身上带着的无线终端：不用走路，拿到主手右键打开，连的是它绑定的那张网。 */
    private final class Wireless implements TerminalAccess {
        private final String itemId;

        Wireless(String itemId) {
            this.itemId = itemId;
        }

        @Override public MenuOpening opening() {
            return new HeldItemMenuOpening(itemId, services.menuLayouts(), services.toMainhand(),
                    services.interactions(), services.context());
        }

        @Override public boolean belongs(AbstractContainerMenu raw, ClientLevel level) {
            return compat.wirelessMenu(raw);
        }

        @Override public String describe() {
            return "身上的" + itemId;
        }

        @Override public void remember(boolean linked, List<Ae2TerminalMenu.StockEntry> stock, boolean complete) {
            seen.sawWireless(linked, stock, clock.get(), complete);
        }
    }
}
