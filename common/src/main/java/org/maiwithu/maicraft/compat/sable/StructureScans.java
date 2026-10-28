// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 物理结构逐格读结果的记事本：读一艘船要读很多格，读一次按地块坐标记下来（和位姿无关），两百刻内复用、过了重读，
 * 方块改了（加配重、被炸）这么久以内会看出来；很久没再问的结构顺手丢掉。只在客户端线程上用。
 * Sable 与 Simulated 两个联动入口共用一本：在船上施工改了方块后作废一次，观察、上下船、看载具都照新结构读。
 */
public final class StructureScans {

    /** 一本空的记事本；联动清单建一本交给两个入口共用。 */
    public StructureScans() {
    }

    /** 逐格读的结果多久重读一次（刻）。 */
    static final int FRESH_TICKS = 200;

    private final Map<String, Scanned> scans = new HashMap<>();

    private record Scanned(long at, StructureGeometry.Scan scan) {}

    // 记事本里有两百刻内读过的就用它；不然逐格重读一遍（连机翼一起认）记下来。
    StructureGeometry.Scan scan(long now, SableStructures.Structure structure, Function<BlockPos, BlockState> read,
            StructureGeometry.WingRules wings) {
        Scanned cached = scans.get(structure.id());
        if (cached != null && now - cached.at() < FRESH_TICKS) return cached.scan();
        StructureGeometry.Scan scan = StructureGeometry.scan(structure, read, wings);
        scans.entrySet().removeIf(entry -> now - entry.getValue().at() > FRESH_TICKS * 10L);
        scans.put(structure.id(), new Scanned(now, scan));
        return scan;
    }

    /** 这个结构下次一定重读（刚组装、刚拆回、刚改过方块）。 */
    void forget(String id) {
        scans.remove(id);
    }
}
