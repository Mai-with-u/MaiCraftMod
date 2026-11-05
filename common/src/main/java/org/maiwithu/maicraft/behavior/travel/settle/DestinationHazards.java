// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.settle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.travel.planning.HazardCell;

/**
 * 出行走不通时，目的地旁边因为危险没踩的格怎么说：按方块种类和原因分组，每组写几处、最近一处的坐标、
 * 在目的地哪个方位多远、为什么没踩。游戏 Agent 照着就能下目标：拿锹或水桶灭掉营火、绕开、换目的地。
 * 纯函数，离线测试直接喂格子。
 */
public final class DestinationHazards {

    /** 读目的地周围多大一圈：水平 3 格、上下 1 格，够得着"门口那堆火"，又不把远处的岩浆湖扯进来。 */
    public static final int RADIUS = 3;
    private static final String SUGGESTION = "目的地旁边有因为危险没踩的格：先处理掉它（比如灭火），或者换个目的地、换条路";

    private DestinationHazards() {}

    /** 目的地旁边没踩的危险格的说明；一格也没有时为空。 */
    public static Optional<String> describe(BlockPos destination, List<HazardCell> cells) {
        if (cells.isEmpty()) return Optional.empty();
        // 同一种方块、同一个原因归一组；组按最近一处离目的地的远近排，最近的先说。
        Map<String, List<HazardCell>> groups = new LinkedHashMap<>();
        cells.stream().sorted(Comparator.comparingDouble(cell -> cell.cell().distSqr(destination)))
                .forEach(cell -> groups.computeIfAbsent(cell.blockId() + "\n" + cell.why(), key -> new ArrayList<>())
                        .add(cell));
        List<String> parts = new ArrayList<>();
        for (List<HazardCell> group : groups.values()) {
            HazardCell nearest = group.get(0);
            BlockPos at = nearest.cell();
            parts.add(nearest.blockId() + " " + group.size() + " 处，最近一处在 (" + at.getX() + ", " + at.getY() + ", "
                    + at.getZ() + ")、" + whereFrom(destination, at) + "（" + nearest.why() + "）");
        }
        return Optional.of("目的地旁边因为危险没踩的：" + String.join("；", parts));
    }

    /** 点名了危险格时的建议：先说处理危险格，原来的建议（比如放宽许可）照留在后面。 */
    public static String suggestion(String original) {
        return original == null || original.isBlank() ? SUGGESTION : SUGGESTION + "；" + original;
    }

    // 从目的地看过去在哪：同一柱列说正上方、正下方几格，别的按八向方位、水平几格、高低差说。
    private static String whereFrom(BlockPos destination, BlockPos at) {
        int dy = at.getY() - destination.getY();
        if (at.getX() == destination.getX() && at.getZ() == destination.getZ()) {
            if (dy == 0) return "就是目的地那一格";
            return dy < 0 ? "目的地正下方 " + (-dy) + " 格" : "目的地正上方 " + dy + " 格";
        }
        return "目的地" + RemainingDistance.measure(destination, at).describe();
    }
}
