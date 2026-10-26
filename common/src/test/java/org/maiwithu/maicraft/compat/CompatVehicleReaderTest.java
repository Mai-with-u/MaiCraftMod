// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 读载具办法的包装：经登记表拿到的读载具办法照转受力的询问（machine_inspect 的 balance 才问得到服务端）；
 * 停用后不再给询问，问到一半停用或接口对不上时给一份读不到的受力并写明原因，不让询问一直等。
 */
class CompatVehicleReaderTest {

    /** 一份替身受力：读到了，质量 120，问过 ballast 就带一处配重。 */
    private static VehicleReader.Balance balance(boolean ballast) {
        return new VehicleReader.Balance(true, "", 120, 1.1, false,
                List.of(new VehicleReader.Case("关着动力", 0.2, 0.1, true, "稳着")),
                ballast ? List.of(new VehicleReader.Ballast(1, 70, 2, "minecraft:iron_block", 30, 2, 1)) : List.of(), "");
    }

    /** 替身读载具办法：受力头一刻还没答上、第二刻答上；broken 时问的那一下碰到接口对不上。 */
    private static VehicleReader reader(CompatModule module, boolean broken) {
        return new VehicleReader() {
            @Override public Optional<View> read(String structureId) { return Optional.empty(); }

            @Override public Optional<BalanceQuery> balance(String structureId, boolean ballast) {
                int[] polls = {0};
                return Optional.of(() -> {
                    if (broken) {
                        return module.call("读结构的受力", () -> {
                            throw new NoSuchMethodError("SubLevel.getMassTracker");
                        });
                    }
                    return ++polls[0] < 2 ? Optional.empty() : Optional.of(CompatVehicleReaderTest.balance(ballast));
                });
            }
        };
    }

    private static CompatRegistry load(boolean broken) {
        CompatModule module = new CompatModule("simulated", "Simulated 载具部件") {
            @Override public void contribute(CompatRegistry registry) {
                registry.vehicleReader(this, services -> reader(this, broken));
            }
        };
        return CompatRegistry.load(List.of(new SupportedMod<>("simulated", "Simulated 载具部件",
                new VerifiedVersions("1.3.0", "1.4"), () -> module)), new FakeLoader().with("simulated", "1.3.0"));
    }

    @Test
    void 经登记表拿到的读载具办法照转受力询问() {
        VehicleReader reader = load(false).vehicleReaders(null).getFirst();
        assertInstanceOf(CompatVehicleReader.class, reader);
        VehicleReader.BalanceQuery query = reader.balance("ship", true).orElseThrow();
        assertTrue(query.poll().isEmpty(), "服务端还没答上就接着等");
        VehicleReader.Balance answered = query.poll().orElseThrow();
        assertTrue(answered.known());
        assertEquals(120, answered.mass());
        assertEquals(1, answered.ballast().size(), "要了配重建议就带上");
    }

    @Test
    void 停用后不给询问_问到一半停用给读不到的受力() {
        CompatRegistry registry = load(false);
        VehicleReader reader = registry.vehicleReaders(null).getFirst();
        VehicleReader.BalanceQuery query = reader.balance("ship", false).orElseThrow();
        CompatModule module = registry.modules().getFirst();
        assertThrows(ModApiMismatch.class, () -> module.call("读无线红石打字机的回路", () -> {
            throw new NoClassDefFoundError("LinkedTypewriterBlockEntity");
        }));
        VehicleReader.Balance stopped = query.poll().orElseThrow();
        assertFalse(stopped.known());
        assertTrue(stopped.why().startsWith("问受力时联动已停用"), stopped.why());
        assertTrue(reader.balance("ship", false).isEmpty(), "停用后不再给询问");
    }

    @Test
    void 问的时候接口对不上就收住() {
        VehicleReader.Balance broken = load(true).vehicleReaders(null).getFirst().balance("ship", false).orElseThrow()
                .poll().orElseThrow();
        assertFalse(broken.known());
        assertTrue(broken.why().contains("读结构的受力"), broken.why());
    }
}
