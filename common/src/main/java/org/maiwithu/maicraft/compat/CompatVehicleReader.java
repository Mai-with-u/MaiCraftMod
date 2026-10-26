// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;

/**
 * 联动模组交来的读载具办法，登记表给它包的一层：模组停用后答不上，碰到模组接口对不上也按答不上，
 * machine_inspect 照实说读不了这辆。读、角色脚下的结构、受力的询问三样都照转；问到一半模组停用或接口对不上，
 * 受力按读不到收场并写明原因，不让询问一直等下去。
 */
final class CompatVehicleReader implements VehicleReader {

    private final CompatModule module;
    private final VehicleReader reader;

    CompatVehicleReader(CompatModule module, VehicleReader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override public Optional<String> carrying() {
        if (!module.active()) return Optional.empty();
        try {
            return reader.carrying();
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public Optional<View> read(String structureId) {
        if (!module.active()) return Optional.empty();
        try {
            return reader.read(structureId);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    // 受力要问服务端、等几刻：询问照转给联动，逐刻 poll 时再包一层，问到一半停用也有个收场。
    @Override public Optional<BalanceQuery> balance(String structureId, boolean ballast) {
        if (!module.active()) return Optional.empty();
        try {
            return reader.balance(structureId, ballast).map(query -> new GuardedQuery(module, query));
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 一次受力的询问：模组停用或接口对不上时给一份读不到的受力，写明原因。 */
    private record GuardedQuery(CompatModule module, BalanceQuery query) implements BalanceQuery {
        @Override public Optional<Balance> poll() {
            if (!module.active()) return Optional.of(unreadable("联动已停用：" + module.disabledReason().orElse("")));
            try {
                return query.poll();
            } catch (ModApiMismatch broken) {
                return Optional.of(unreadable(broken.getMessage()));
            }
        }

        private static Balance unreadable(String why) {
            return new Balance(false, "问受力时" + why, 0, 0, false, List.of(), List.of(), "");
        }
    }
}
