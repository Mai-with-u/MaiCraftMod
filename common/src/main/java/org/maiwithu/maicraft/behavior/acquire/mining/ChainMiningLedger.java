// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 连锁挖掘的事实统计账本：按世界记录每次挖方块是成批连上了还是退回单挖，以及没连上的主要原因。
 *
 * <p>{@code obtain} 与 {@code gather} 在开场时取下游标，收场时汇总本次任务期间的挖掘统计写进 {@code details.chain_mining}，
 * 同时刷新本世界最近一次挖掘动作的统计供 {@code observe(what=self)} 的 {@code last_chain_mining} 直接展示。
 */
public final class ChainMiningLedger {

    /**
     * 一段挖掘动作的连锁统计摘要。
     *
     * @param chainedBatches   真正连上的批数
     * @param chainedBlocks    通过连锁成批挖掉的总格数
     * @param singleBlocks     单格挖掉的格数
     * @param notChainedReason 尝试连锁被拒或没用上连锁时次数最多的主要原因；全部连上且没有被拒时为 null
     */
    public record Summary(int chainedBatches, int chainedBlocks, int singleBlocks, String notChainedReason) {}

    private record Entry(boolean chained, int blocks, String reason) {}

    private final List<Entry> entries = new ArrayList<>();
    private Summary lastCompleted;
    private int lastArchivedIndex;

    /** 当前已记录的挖掘条数；任务开始前取作游标。 */
    public int mark() {
        return entries.size();
    }

    /** 记录真正连上并挖掉的一批（至少两格才算成批连锁，只掉一格按单挖记）。 */
    public void recordChained(int brokenBlocks, String fallbackReasonIfOne) {
        if (brokenBlocks >= 2) {
            entries.add(new Entry(true, brokenBlocks, null));
        } else {
            entries.add(new Entry(false, 1, normalizeReason(fallbackReasonIfOne)));
        }
    }

    /** 记录单格挖掉的一格，以及这次没用上连锁或被拒的原因（没有原因传 null）。 */
    public void recordSingle(String rawReason) {
        entries.add(new Entry(false, 1, normalizeReason(rawReason)));
    }

    /**
     * 汇总从 {@code mark} 起本次任务产生的所有挖掘记录，若动过挖掘则一并刷新本世界最近一次挖掘统计；
     * 一次都没挖过时返回空（结果细节不输出 {@code chain_mining}）。
     */
    public Optional<Summary> completeSince(int mark) {
        int from = Math.clamp(mark, 0, entries.size());
        if (from >= entries.size()) return Optional.empty();
        Summary summary = summarize(entries.subList(from, entries.size()));
        lastCompleted = summary;
        lastArchivedIndex = entries.size();
        return Optional.of(summary);
    }

    /** 本世界最近一次挖掘动作的连锁统计；本世界还没挖过任何方块时返回空。 */
    public Optional<Summary> lastSummary() {
        if (entries.size() > lastArchivedIndex) {
            return Optional.of(summarize(entries.subList(lastArchivedIndex, entries.size())));
        }
        return Optional.ofNullable(lastCompleted);
    }

    /** 把底层带具体坐标或刻数的现场拒绝说明归并成简明可读的中文短句。 */
    public static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.contains("没加载")) return "选区伸进未加载区块";
        if (raw.contains("耐久")) return "工具剩余耐久不够一整批";
        if (raw.contains("水或岩浆")) return "选区紧挨水或岩浆";
        if (raw.contains("工具挖不下")) return "手上的工具挖不下选区方块";
        if (raw.contains("不在这次该挖的格里")) return "选区超出允许挖掘的范围";
        if (raw.contains("设施")) return "选区挨着箱子或机器设施";
        if (raw.contains("没有连锁挖") || raw.contains("服务器没装") || raw.contains("未接入连锁")) {
            return "当前实例未接入连锁破坏";
        }
        if (raw.contains("界面被藏起来")) return "界面隐藏（F1）时不连锁";
        if (raw.contains("只连得到一格") || raw.contains("只剩这一格")) return "周围没有相连的同种方块";
        if (raw.contains("超时未挥出") || raw.contains("还没挥下去")) return "按下连锁键后超时未挥出";
        return raw;
    }

    private static Summary summarize(List<Entry> slice) {
        int batches = 0;
        int chainedBlocks = 0;
        int singleBlocks = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Entry entry : slice) {
            if (entry.chained()) {
                batches++;
                chainedBlocks += entry.blocks();
            } else {
                singleBlocks += entry.blocks();
                if (entry.reason() != null) {
                    reasons.merge(entry.reason(), 1, Integer::sum);
                }
            }
        }
        String topReason = null;
        int topCount = 0;
        for (Map.Entry<String, Integer> candidate : reasons.entrySet()) {
            if (candidate.getValue() > topCount) {
                topCount = candidate.getValue();
                topReason = candidate.getKey();
            }
        }
        return new Summary(batches, chainedBlocks, singleBlocks, topReason);
    }
}
