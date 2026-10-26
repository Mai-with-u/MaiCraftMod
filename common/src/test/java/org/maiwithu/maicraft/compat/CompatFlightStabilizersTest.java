// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;

/**
 * 飞行稳定器的槽：进世界才建、建出来包一层；模组停用后读不到、改不了（看载具照实说没读到，驾驶照旧按键）；
 * 读写时碰到模组接口对不上也收住，不往外抛。
 */
class CompatFlightStabilizersTest {

    private static final BlockPos AT = new BlockPos(3, 70, 3);

    /** 一个替身读写办法：读给一份空的，改都说发出去了；碰到 broken 时经联动入口碰一下就对不上。 */
    private static final class Stabilizers implements FlightStabilizers {
        private final CompatModule module;
        private final boolean broken;

        Stabilizers(CompatModule module, boolean broken) {
            this.module = module;
            this.broken = broken;
        }

        @Override public Optional<Setup> read(String structureId) {
            if (broken) {
                return module.call("读飞行稳定器", () -> {
                    throw new NoSuchFieldError("AltitudeHoldBlockEntity.presetAltitude");
                });
            }
            return Optional.of(new Setup(List.of(), List.of(), true, ""));
        }

        @Override public boolean holdAltitude(String structureId, BlockPos local, double y) { return true; }
        @Override public boolean switchOn(String structureId, BlockPos local) { return true; }
        @Override public boolean setHorizontalSpeed(String structureId, BlockPos local, double speed) { return true; }
    }

    private static CompatRegistry load(boolean broken) {
        CompatModule module = new CompatModule("create_flight_control", "Create: Flight Control") {
            @Override public void contribute(CompatRegistry registry) {
                registry.flightStabilizers(this, services -> new Stabilizers(this, broken));
            }
        };
        return CompatRegistry.load(List.of(new SupportedMod<>("create_flight_control", "Create: Flight Control",
                new VerifiedVersions("0.5.0", "0.6"), () -> module)), new FakeLoader().with("create_flight_control", "0.5.0"));
    }

    @Test
    void 进世界才建_停用后读不到也改不了() {
        CompatRegistry registry = load(false);
        FlightStabilizers stabilizers = registry.flightStabilizers(null).getFirst();
        assertInstanceOf(CompatFlightStabilizers.class, stabilizers);
        assertTrue(stabilizers.read("ship").isPresent());
        assertTrue(stabilizers.holdAltitude("ship", AT, 90));
        CompatModule module = registry.modules().getFirst();
        assertThrows(ModApiMismatch.class, () -> module.call("读高度稳定器", () -> {
            throw new NoSuchMethodError("AltitudeHoldBlockEntity.getLinkedComputerPos");
        }));
        assertTrue(stabilizers.read("ship").isEmpty(), "停用后读不到");
        assertFalse(stabilizers.holdAltitude("ship", AT, 90), "停用后设不了目标高度");
        assertFalse(stabilizers.switchOn("ship", AT));
        assertFalse(stabilizers.setHorizontalSpeed("ship", AT, 6));
    }

    @Test
    void 读的时候接口对不上就收住() {
        assertTrue(load(true).flightStabilizers(null).getFirst().read("ship").isEmpty());
    }
}
