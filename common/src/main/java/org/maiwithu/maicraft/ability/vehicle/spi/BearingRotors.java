// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 轴承转子是哪几格，纯函数：机械动力的轴承（动力轴承、风车轴承，以及从它派生的螺旋桨轴承）转起来时，
 * 把朝向那面的那一格（转子头）连同挨着它的风帆类方块一起带着转。从转子头起顺着挨着的风帆类方块认，连着的都算。
 * 补胶（转子和船身不粘）与认机翼（跟着转的风帆不是机翼）共用这一份。
 */
public final class BearingRotors {

    private BearingRotors() {}

    /**
     * 一个转子：轴承所在的格、转子头、转子包含的格（含转子头）。
     *
     * @param bearing 轴承所在的格
     * @param head    转子头：轴承朝向那面的那一格
     * @param cells   跟着转的格，含转子头
     */
    public record Rotor(BlockPos bearing, BlockPos head, Set<BlockPos> cells) {
        public Rotor {
            cells = Set.copyOf(cells);
        }
    }

    /**
     * 一片方块里的转子：转子头不在这片方块里的轴承没有转子；一格只算进先认到的那个转子。
     *
     * @param body    这片方块
     * @param bearing 这一格是不是轴承、朝哪；不是为空
     * @param sail    这一格是不是风帆类方块（机械动力的风车风帆标签）
     */
    public static List<Rotor> of(Set<BlockPos> body, Function<BlockPos, Optional<Direction>> bearing,
            Predicate<BlockPos> sail) {
        List<Rotor> rotors = new ArrayList<>();
        Set<BlockPos> taken = new LinkedHashSet<>();
        for (BlockPos cell : body) {
            Optional<Direction> facing = bearing.apply(cell);
            if (facing.isEmpty()) continue;
            BlockPos head = cell.relative(facing.get());
            if (!body.contains(head) || taken.contains(head)) continue;
            // 从转子头起顺着挨着的风帆类方块往外认，连着的都跟着转。
            Set<BlockPos> cells = new LinkedHashSet<>(List.of(head));
            taken.add(head);
            Deque<BlockPos> frontier = new ArrayDeque<>(List.of(head));
            while (!frontier.isEmpty()) {
                BlockPos current = frontier.poll();
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = current.relative(direction);
                    if (body.contains(neighbor) && !taken.contains(neighbor) && sail.test(neighbor)) {
                        taken.add(neighbor);
                        cells.add(neighbor);
                        frontier.add(neighbor);
                    }
                }
            }
            rotors.add(new Rotor(cell, head, cells));
        }
        return rotors;
    }
}
