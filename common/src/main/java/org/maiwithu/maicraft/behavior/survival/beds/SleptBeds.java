// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.beds;

import java.util.Comparator;
import java.util.Optional;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 世界记忆里亲手用过的床：亲手放下的、睡下过的都记成"亲手用过的床"；真睡下过的另记一项"睡过"。
 *
 * <p>收回自己的床按它认哪张是自己放过、睡过的；圈"她自己的家"只认真睡过的那张。
 * 这是记忆不是事实：床可能已经被拆、被挡住，用的时候要说明是推断。
 */
public final class SleptBeds {

    private SleptBeds() {}

    /** 这条记忆是不是一张亲手睡过的床。 */
    public static boolean sleptIn(MemoryRecord record) {
        return record.kind() == MemoryKind.WORKSTATION && record.blockType() != null
                && record.blockType().endsWith("_bed") && record.origin() == MemoryOrigin.USED;
    }

    /** 最近一次亲手用过（放下或睡过）的那张床在哪：按记下的时刻挑最新的；一张都没有为空。 */
    public static Optional<WorldPosition> latest(WorldMemory memory) {
        return memory.allRecords().stream().filter(SleptBeds::sleptIn)
                .max(Comparator.comparing(MemoryRecord::recordedAt)).map(MemoryRecord::position);
    }

    /** 这条记忆是不是一张真睡下过的床：只放下没睡过的不算。 */
    public static boolean reallySleptIn(MemoryRecord record) {
        return sleptIn(record) && record.contents() != null && record.contents().contains(WorldMemory.SLEPT_IN);
    }

    /** 最近一次真睡下过的那张床：圈"她自己的家"用，只放下没睡过的床不算；一张都没睡过为空。 */
    public static Optional<WorldPosition> latestReallySleptIn(WorldMemory memory) {
        return memory.allRecords().stream().filter(SleptBeds::reallySleptIn)
                .max(Comparator.comparing(MemoryRecord::recordedAt)).map(MemoryRecord::position);
    }
}
