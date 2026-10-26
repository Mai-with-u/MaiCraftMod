// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.flightcontrol;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 读写飞行稳定器：拿角色此刻所在的客户端世界，交给读写端读结构地块、发设置包。角色不在世界里时读不到、发不出。
 */
final class StabilizerReading implements FlightStabilizers {

    private final FlightControlCompat compat;
    private final PlayerServices services;

    StabilizerReading(FlightControlCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public Optional<Setup> read(String structureId) {
        ClientLevel level = level();
        if (level == null) return Optional.empty();
        return compat.call("读飞行稳定器", () -> compat.parts().read(level, structureId));
    }

    @Override public boolean holdAltitude(String structureId, BlockPos local, double y) {
        ClientLevel level = level();
        return level != null && compat.call("设高度稳定器的目标高度",
                () -> compat.parts().holdAltitude(level, structureId, local, y));
    }

    @Override public boolean switchOn(String structureId, BlockPos local) {
        ClientLevel level = level();
        return level != null && compat.call("打开飞行稳定器", () -> compat.parts().switchOn(level, structureId, local));
    }

    @Override public boolean setHorizontalSpeed(String structureId, BlockPos local, double speed) {
        ClientLevel level = level();
        return level != null && compat.call("设线速度耦合器的最大速度",
                () -> compat.parts().setHorizontalSpeed(level, structureId, local, speed));
    }

    private ClientLevel level() {
        PlayerContext context = services.context().get();
        return context == null ? null : context.level();
    }
}
