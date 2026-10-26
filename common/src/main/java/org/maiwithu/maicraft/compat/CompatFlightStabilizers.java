// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;

/**
 * 联动模组交来的飞行稳定器读写办法，登记表给它包的一层：模组停用后读不到、改不了（看载具照实说没读到，
 * 驾驶照旧按键开）；碰到模组接口对不上也按读不到、改不了收场，不让它变成任务的内部错误。
 */
final class CompatFlightStabilizers implements FlightStabilizers {

    private final CompatModule module;
    private final FlightStabilizers stabilizers;

    CompatFlightStabilizers(CompatModule module, FlightStabilizers stabilizers) {
        this.module = Objects.requireNonNull(module, "module");
        this.stabilizers = Objects.requireNonNull(stabilizers, "stabilizers");
    }

    @Override public Optional<Setup> read(String structureId) {
        if (!module.active()) return Optional.empty();
        try {
            return stabilizers.read(structureId);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public boolean holdAltitude(String structureId, BlockPos local, double y) {
        if (!module.active()) return false;
        try {
            return stabilizers.holdAltitude(structureId, local, y);
        } catch (ModApiMismatch broken) {
            return false;
        }
    }

    @Override public boolean switchOn(String structureId, BlockPos local) {
        if (!module.active()) return false;
        try {
            return stabilizers.switchOn(structureId, local);
        } catch (ModApiMismatch broken) {
            return false;
        }
    }

    @Override public boolean setHorizontalSpeed(String structureId, BlockPos local, double speed) {
        if (!module.active()) return false;
        try {
            return stabilizers.setHorizontalSpeed(structureId, local, speed);
        } catch (ModApiMismatch broken) {
            return false;
        }
    }
}
