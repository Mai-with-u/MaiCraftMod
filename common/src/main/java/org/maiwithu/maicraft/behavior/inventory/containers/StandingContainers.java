// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 记得的容器里眼下还在的：一条"这里有只箱子"的记忆，格子加载了、现场却是别的东西，
 * 这条记忆就与现场对不上——当场从记忆里划掉，不再给取物、存物的路线引路，
 * 不然每一条要翻箱子的路都先奔着一个不存在的格子去（实机备床被一条坐标早已不对的
 * 记得箱拖着，十二种做法轮着走同一个死格子，临时任务迟迟收不了场）。
 *
 * <p>这是记忆不是事实的核对纪律：没加载的格子到不了现场，记忆先算数，到场再核对；
 * 划掉的记忆如果箱子还在，感知看见后会重新记下，不丢事实。
 */
public final class StandingContainers {

    /** 现场只读接缝：那一格加载了就读到此刻的方块 ID；没加载给空。 */
    public interface ReadsCells {
        Optional<String> blockIdAt(WorldPosition at);
    }

    private final WorldMemory memory;
    private final ReadsCells cells;
    /** 哪些方块 ID 算容器：按设施分类认（原版加联动证明过的），现场方块对不对按它判。 */
    private final Predicate<String> isContainer;

    public StandingContainers(WorldMemory memory, ReadsCells cells, Predicate<String> isContainer) {
        this.memory = memory;
        this.cells = cells;
        this.isContainer = isContainer;
    }

    /**
     * 给定范围里眼下还成立的记得容器，近的在前（与记忆查询同一把尺子）；
     * 到得了的格子现场对不上的，划掉后不给。
     */
    public List<MemoryRecord> near(WorldPosition center, double radiusBlocks) {
        return memory.recordsNear(center, radiusBlocks).stream()
                .filter(record -> record.kind() == MemoryKind.CONTAINER)
                .filter(record -> stillThere(record))
                .toList();
    }

    /** 指定那一格的记得容器：现场对不上的划掉并给空；没加载的按记忆给，到场再核对。 */
    public Optional<MemoryRecord> at(WorldPosition position) {
        return memory.recordAt(MemoryKind.CONTAINER, position).filter(this::stillThere);
    }

    // 现场核对：格子加载了就按此刻的方块判——不是容器、或不是记下的那种，这条记忆已错，划掉；
    // 记忆里没记方块类型的按"这里是容器就算数"认（开箱时以现场为准）。没加载给不了现场，记忆先算数。
    private boolean stillThere(MemoryRecord record) {
        String here = cells.blockIdAt(record.position()).orElse(null);
        if (here == null) return true;
        if (!isContainer.test(here) || (record.blockType() != null && !record.blockType().equals(here))) {
            // 到现场发现东西没了、搬走了，留着旧记录只会再骗一次：当场从记忆里划掉。
            memory.forget(MemoryKind.CONTAINER, record.position());
            return false;
        }
        return true;
    }
}
