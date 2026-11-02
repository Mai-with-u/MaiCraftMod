// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.Optional;
import org.maiwithu.maicraft.behavior.perception.CaveOpenings;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 进过的洞口的生产实现：读世界记忆里内容是"洞口"、来源到访以上的产地线索。
 * 只是远远望见过的洞口不算：没进去过，不知道和角色此刻所在的洞连不连着。
 */
public final class RememberedCaveEntrances implements CaveEntrances {

    private final WorldMemory memory;

    public RememberedCaveEntrances(WorldMemory memory) {
        this.memory = memory;
    }

    @Override
    public Optional<WorldPosition> nearestEntered(WorldPosition from, double within) {
        String word = CaveOpenings.Kind.CAVE_MOUTH.word();
        return memory.recordsNear(from, within).stream()
                .filter(record -> record.kind() == MemoryKind.SITE && record.origin() != MemoryOrigin.SEEN
                        && record.contents() != null && record.contents().contains(word))
                .map(MemoryRecord::position)
                .findFirst();
    }
}
