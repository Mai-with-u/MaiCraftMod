// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;

/** 连锁挖掘统计账本与同因拒绝跳过记忆测试。 */
class ChainMiningLedgerTest {

    @Test
    void 统计账本汇总成批与单挖并归并次数最多的拒绝原因() {
        ChainMiningLedger ledger = new ChainMiningLedger();
        assertTrue(ledger.lastSummary().isEmpty(), "还没挖过任何方块时为空");

        int mark = ledger.mark();
        assertTrue(ledger.completeSince(mark).isEmpty(), "没动过挖掘时不产出统计");

        ledger.recordChained(8, null);
        ledger.recordChained(6, null);
        ledger.recordSingle("选区里 -465, 34, 41 没加载");
        ledger.recordSingle("工具只剩 2 点耐久，挖不完这一批 8 格");
        ledger.recordSingle("选区里 -466, 34, 41 没加载");

        ChainMiningLedger.Summary summary = ledger.completeSince(mark).orElseThrow();
        assertEquals(2, summary.chainedBatches());
        assertEquals(14, summary.chainedBlocks());
        assertEquals(3, summary.singleBlocks());
        assertEquals("选区伸进未加载区块", summary.notChainedReason());
        assertEquals(summary, ledger.lastSummary().orElseThrow());

        // 下一轮全部连上时 notChainedReason 为 null，并刷新本世界最近一次挖掘统计。
        int nextMark = ledger.mark();
        ledger.recordChained(12, null);
        ChainMiningLedger.Summary allChained = ledger.completeSince(nextMark).orElseThrow();
        assertEquals(1, allChained.chainedBatches());
        assertEquals(12, allChained.chainedBlocks());
        assertEquals(0, allChained.singleBlocks());
        assertNull(allChained.notChainedReason());
        assertEquals(allChained, ledger.lastSummary().orElseThrow());
    }

    @Test
    void 同因拒绝跳过记忆_同区块同朝向不每格重试_跨区块或换方块后自动恢复() {
        ChainSkipMemory memory = new ChainSkipMemory();
        BlockPos chunk0Start = new BlockPos(2, 33, 4);
        BlockPos chunk0Next = new BlockPos(10, 33, 4);
        BlockPos chunk1Start = new BlockPos(16, 33, 4);

        memory.noteRefusal("选区里 66, 33, 4 没加载", chunk0Start, Direction.EAST, false, "minecraft:stone");
        assertTrue(memory.skipReason(chunk0Next, Direction.EAST, false, "minecraft:stone").isPresent(),
                "同一区块同一朝向内直接跳过按键单挖");
        assertTrue(memory.skipReason(chunk0Next, Direction.NORTH, false, "minecraft:stone").isEmpty(),
                "换了挖掘朝向后恢复尝试连锁");

        memory.noteRefusal("选区里 66, 33, 4 没加载", chunk0Start, Direction.EAST, false, "minecraft:stone");
        assertTrue(memory.skipReason(chunk1Start, Direction.EAST, false, "minecraft:stone").isEmpty(),
                "跨进下一个区块后新区块同步进来，恢复尝试连锁");

        // 表层泥土斜线伸进石头导致手上锹挖不下：泥土层跳过，挖进石头层后自动恢复。
        memory.noteRefusal("手上的工具挖不下 0, 60, -4", new BlockPos(0, 63, -1), Direction.NORTH, true, "minecraft:dirt");
        assertTrue(memory.skipReason(new BlockPos(0, 62, -2), Direction.NORTH, true, "minecraft:dirt").isPresent());
        assertTrue(memory.skipReason(new BlockPos(0, 60, -4), Direction.NORTH, true, "minecraft:stone").isEmpty());

        // 耐久不够属于整段动作不变的原因，跨区块也不再白按连锁键。
        memory.noteRefusal("工具只剩 3 点耐久，挖不完这一批 10 格", chunk1Start, Direction.EAST, false, "minecraft:stone");
        assertEquals(Optional.of("工具只剩 3 点耐久，挖不完这一批 10 格"),
                memory.skipReason(new BlockPos(64, 33, 4), Direction.SOUTH, true, "minecraft:deepslate"));
    }
}
