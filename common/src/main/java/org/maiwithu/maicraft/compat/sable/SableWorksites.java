// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * Sable 物理结构上的工地：角色附近看得到这个结构身份就给一份，施工按它在结构上读格、靠近、点、挖。
 */
final class SableWorksites implements StructureWorksites {

    private final SableCompat compat;
    private final PlayerServices services;

    SableWorksites(SableCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public Optional<Worksite> at(String structureId) {
        PlayerContext current = services.context().get();
        ClientLevel level = current == null ? null : current.level();
        LocalPlayer player = current == null ? null : current.localPlayer();
        return StructureScene.find(compat, level, player, structureId)
                .map(seen -> new StructureWorksite(compat, services, seen));
    }
}
