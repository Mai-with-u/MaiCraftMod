// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;

/**
 * 联动模组交来的网络读取器，登记表给它包的一层：模组停用后哪一格都不算在网里，汇总如实说读不到并写明原因，
 * 也不再给接线的线材；读的时候碰到模组接口对不上也在这里收住。网络种类是登记时定下的，照常回答。
 */
public final class CompatNetworkReader implements NetworkReader {

    private final CompatModule module;
    private final NetworkReader reader;

    public CompatNetworkReader(CompatModule module, NetworkReader reader) {
        this.module = Objects.requireNonNull(module, "module");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override public NetworkKind kind() {
        return reader.kind();
    }

    @Override public Optional<String> membership(BlockPos at) {
        return DegradedAnswer.ask(module, () -> reader.membership(at), Optional.empty());
    }

    // 线材要读模组的方块与连接数：停用了就当这种网不会接线，读的时候对不上也收住。
    @Override public Optional<LineMaterials> lineMaterials() {
        return DegradedAnswer.ask(module, () -> reader.lineMaterials(), Optional.<LineMaterials>empty())
                .map(materials -> new CompatLineMaterials(module, materials));
    }

    @Override public boolean summaryPending(String networkId) {
        return DegradedAnswer.ask(module, () -> reader.summaryPending(networkId), false);
    }

    @Override public NetworkSummary summary(String networkId) {
        return DegradedAnswer.ask(module, () -> reader.summary(networkId),
                why -> NetworkSummary.unreadable(networkId, why));
    }
}
