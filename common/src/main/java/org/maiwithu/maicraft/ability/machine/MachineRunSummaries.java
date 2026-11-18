// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;

/**
 * 用机器做东西时的运行状态与所属网络读数拼装：把机器当刻的活动状态、转速、进度与网络汇总写成给结果阅读的一句话。
 */
final class MachineRunSummaries {

    private MachineRunSummaries() {}

    /** 机器读数的一句话：在干什么、转速、进度，读不到的把原因带上。 */
    static String describeState(MachineState state) {
        String activity = state.activity().name().toLowerCase(Locale.ROOT);
        StringBuilder line = new StringBuilder(activity);
        if (state.speed() != null) {
            line.append("，转速 ").append(state.speed());
        }
        if (state.progress() != null) {
            line.append("，进度 ").append(Math.round(state.progress() * 100)).append("%");
        }
        if (!state.note().isEmpty()) {
            line.append("（").append(state.note()).append("）");
        }
        return line.toString();
    }

    /** 结束时把机器挂着的网络读数带上：转速 0、过载、没电这些一眼能对上原因。 */
    static String describeNetworks(List<NetworkReader> readers, BlockPos cell) {
        StringBuilder line = new StringBuilder();
        for (NetworkReader reader : readers) {
            Optional<String> id = reader.membership(cell);
            if (id.isEmpty()) {
                continue;
            }
            var summary = reader.summary(id.get());
            line.append(line.isEmpty() ? "；网络：" : "，").append(reader.kind().id());
            if (!summary.readable()) {
                line.append("读不到（").append(summary.note()).append("）");
                continue;
            }
            List<String> readings = new ArrayList<>();
            summary.readings().forEach((name, value) -> readings.add(name + "=" + value));
            if (!readings.isEmpty()) {
                line.append("（").append(String.join("，", readings)).append("）");
            }
        }
        return line.toString();
    }
}
