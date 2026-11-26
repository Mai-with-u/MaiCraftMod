// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;

/**
 * 用机器做东西时的运行状态、所属网络读数与周期提示拼装：把机器当刻的活动状态、转速、进度、网络汇总与预估加工时长写成给结果阅读的一句话。
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

    /**
     * 预估加工时长的一句话：按配方原始定义给出的周期刻数换算成秒，读不到按常规 10 秒一轮估，
     * 方便 wait=false 投料开机即走时配 timer 回头收货。
     */
    static String describeCycleHint(ShownRecipe recipe, int batches) {
        int cycleTicks = LookBackSchedule.cycleTicks(recipe == null ? null : recipe.definition());
        int singleSeconds = Math.max(1, (cycleTicks + 19) / 20);
        int totalBatches = Math.max(1, batches);
        int totalSeconds = (int) Math.max(1, ((long) cycleTicks * totalBatches + 19) / 20);
        if (totalBatches == 1) {
            return "预计单轮约 " + singleSeconds + " 秒，可配 timer 回头收货";
        }
        return "预计单轮约 " + singleSeconds + " 秒，共 " + totalBatches + " 批约 " + totalSeconds
                + " 秒，可配 timer 回头收货";
    }

    /** 投料开机即走（wait=false）时的一句话总结。 */
    static String noWaitSummary(String machineName, boolean fedAny, boolean collectRequested,
            String cycleHint, String machineReading) {
        String lead = fedAny ? "已把料放进" + machineName + "并启动" : "已启动" + machineName;
        String tail = collectRequested ? "（不等待出货，已跳过本次收货，稍后自行来取）" : "（不等待出货）";
        return lead + tail + "；" + cycleHint + "；机器读数：" + machineReading;
    }
}
