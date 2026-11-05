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
 * 世界记忆里亲手睡过的床：睡下或右键用过一张床，原版就把重生点设在那里，记忆里记成"亲手用过的床"。
 *
 * <p>收回自己的床按它认哪张是自己睡过的；连别人的服务器读不到真实重生点时，按最近睡过的那张推断会回到哪儿。
 * 这是记忆不是事实：床可能已经被拆、被挡住，用的时候要说明是推断。
 */
public final class SleptBeds {

    private SleptBeds() {}

    /** 这条记忆是不是一张亲手睡过的床。 */
    public static boolean sleptIn(MemoryRecord record) {
        return record.kind() == MemoryKind.WORKSTATION && record.blockType() != null
                && record.blockType().endsWith("_bed") && record.origin() == MemoryOrigin.USED;
    }

    /** 最近一次睡过的那张床在哪：按记下的时刻挑最新的；一张都没睡过为空。 */
    public static Optional<WorldPosition> latest(WorldMemory memory) {
        return memory.allRecords().stream().filter(SleptBeds::sleptIn)
                .max(Comparator.comparing(MemoryRecord::recordedAt)).map(MemoryRecord::position);
    }
}
