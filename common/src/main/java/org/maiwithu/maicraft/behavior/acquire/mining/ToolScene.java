// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.game.interaction.DigToolUse;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 把这一趟挖方块用到的工具拼成结果里的一条：用了什么、磨了多少、比徒手省了多少。
 *
 * <p>拼法是句人话：一件工具挖到底就说"用石镐挖了 3 格"，中途换了工具就按先后说"先用石镐挖了 2 格，
 * 又用铁镐挖了 1 格"；点名的那件写成"你点名的钻石镐"。磨损照实说，磨了 0 就说没磨——
 * 空手、挖不掉耐久的方块都是这样。省下的时间按刻折算成秒，不到一秒不提。
 */
public final class ToolScene {

    private ToolScene() {
    }

    /**
     * 一条结果里的工具现场。
     *
     * @param uses 挖下来的格子用了哪些工具，按先后
     * @return 试过什么（用了什么工具、挖了几格）与结果（磨了多少、省了多少）；没挖过时没有这一条
     */
    public static Optional<Attempt> tell(List<DigToolUse> uses) {
        List<DigToolUse> merged = merged(uses);
        if (merged.isEmpty()) return Optional.empty();
        return Optional.of(new Attempt(tried(merged), how(merged)));
    }

    /**
     * 把这一趟的工具现场记进任务结果。挖方块的地方不止一处（成批收、采一格），说法却只有一套，
     * 所以"记进结果"这一步也留在这里，调用方只管把挖下来的格子用过什么交过来。
     *
     * @param records 发起任务的记账口；没有记账口时（问价、替身）做了什么都不记
     * @param uses    挖下来的格子用了哪些工具，按先后
     */
    public static void record(TaskRecords records, List<DigToolUse> uses) {
        if (records == null) return;
        tell(uses).ifPresent(attempt -> records.attempt(attempt.tried(), attempt.result()));
    }

    // 同一件工具连着挖的合成一段，段数就是句子里要说的工具件数。
    private static List<DigToolUse> merged(List<DigToolUse> uses) {
        List<DigToolUse> merged = new ArrayList<>();
        for (DigToolUse use : uses) {
            if (use.blocks() <= 0) continue;
            DigToolUse last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.sameToolAs(use)) {
                merged.set(merged.size() - 1,
                        last.plus(use.blocks(), use.wear(), use.ticks(), use.bareTicks()));
            } else {
                merged.add(use);
            }
        }
        return merged;
    }

    // 用了什么：一件工具一段，换过工具就按先后一件件说。
    private static String tried(List<DigToolUse> merged) {
        if (merged.size() == 1) return one(merged.get(0));
        StringBuilder text = new StringBuilder("先").append(one(merged.get(0)));
        for (int index = 1; index < merged.size(); index++) {
            text.append(index == 1 ? "，又" : "，再").append(one(merged.get(index)));
        }
        return text.toString();
    }

    private static String one(DigToolUse use) {
        if (use.toolId().isEmpty()) return "空手挖了 " + use.blocks() + " 格";
        return "用" + (use.named() ? "你点名的" : "") + use.toolName() + "挖了 " + use.blocks() + " 格";
    }

    // 结果：磨了多少、比徒手省了几秒。
    private static String how(List<DigToolUse> merged) {
        int wear = 0;
        int ticks = 0;
        int bareTicks = 0;
        for (DigToolUse use : merged) {
            wear += use.wear();
            ticks += use.ticks();
            bareTicks += use.bareTicks();
        }
        StringBuilder text = new StringBuilder(wear > 0 ? "磨掉 " + wear + " 点耐久" : "没磨耐久");
        long seconds = Math.max(0, bareTicks - ticks) / 20L;
        if (seconds >= 1) text.append("，比徒手快约 ").append(seconds).append(" 秒");
        return text.toString();
    }
}
