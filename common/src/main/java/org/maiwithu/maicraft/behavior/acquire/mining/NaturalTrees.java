// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 天然树的辨认：砍树只砍长着的树，不拆玩家用原木搭的木屋、栅栏柱、横梁。
 * 看的是树冠的样子——沿树干上半段逐层找，哪一层至少两侧贴着原木（距离 1）的非持久树叶，就以那层为树冠起点，
 * 在它附近按树干粗细放开的小范围里数到八片叶子。橡树/桦树的团状冠抱在树顶，云杉/枞树的锥形冠叶环挂在中上部、
 * 顶梢光秃，逐层找两层形状都覆盖。
 * 这是识别规则，不是读游戏的"自然生成"标签——客户端拿不到方块实体的生成来源，只能从叶子的长法反推。
 */
public final class NaturalTrees {

    /** 一根树干最多往上下各找这么多格：再高的原木柱不是树。 */
    private static final int MAX_TRUNK = 40;
    /** 树顶附近数到这么多片天然树叶就算有树冠。 */
    private static final int CROWN_LEAVES = 8;

    private NaturalTrees() {}

    /** 这一格是不是原木（含菌柄）。 */
    public static boolean isLog(BlockState state) {
        if (state.is(BlockTags.LOGS)) return true;
        // 标签还没同步过来（离线测试、刚进世界）时按注册名兜底：原木与菌柄都叫 *_log / *_stem，而且是柱状方块。
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.getBlock() instanceof RotatedPillarBlock && (path.endsWith("_log") || path.endsWith("_stem"));
    }

    /**
     * 这一格原木是不是长在一棵天然树上：连出整根树干（同种原木，粗干带相邻的列），看树干上有没有树冠。
     * 同一根树干查过一次就记在 checked 里（键是树干顶），一次扫描里不重复数叶子。
     */
    public static boolean partOfNaturalTree(BlockPos log, BlockGetter world, Predicate<BlockPos> loaded,
            Map<BlockPos, Boolean> checked) {
        Set<BlockPos> trunk = trunkOf(log, world, loaded);
        if (trunk.isEmpty()) return false;
        BlockPos top = trunk.stream().max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
        return checked.computeIfAbsent(top, key -> hasNaturalCrown(trunk, world, loaded));
    }

    /**
     * 从这一格原木连出整根树干：先横着把相邻的同种原木列认进来（云杉/枞树这类 2x2 粗干是两列并排），
     * 再每列沿竖直方向上下连到头（同一种原木就连着）。
     */
    static Set<BlockPos> trunkOf(BlockPos log, BlockGetter world, Predicate<BlockPos> loaded) {
        Set<BlockPos> trunk = new HashSet<>();
        if (!loaded.test(log)) return trunk;
        BlockState kind = world.getBlockState(log);
        if (!isLog(kind)) return trunk;
        List<BlockPos> columns = new ArrayList<>();
        columns.add(log.immutable());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = log.relative(side);
            if (loaded.test(neighbor) && world.getBlockState(neighbor).is(kind.getBlock())) {
                columns.add(neighbor.immutable());
            }
        }
        for (BlockPos column : columns) {
            trunk.add(column);
            for (Direction direction : new Direction[] {Direction.UP, Direction.DOWN}) {
                BlockPos next = column.relative(direction);
                for (int i = 0; i < MAX_TRUNK && loaded.test(next) && world.getBlockState(next).is(kind.getBlock()); i++) {
                    trunk.add(next.immutable());
                    next = next.relative(direction);
                }
            }
        }
        return trunk;
    }

    /**
     * 这根树干有没有天然树冠：从树干顶往下扫到树干中点，哪一层至少两侧贴着距离 1 的非持久树叶，
     * 就以那层为树冠起点在附近数叶，数到八片算有冠。逐层找是因为锥形冠（云杉/枞树）的叶环挂在中上部、顶梢光秃，
     * 只看顶端两层会把整族树漏掉。
     */
    static boolean hasNaturalCrown(Set<BlockPos> trunk, BlockGetter world, Predicate<BlockPos> loaded) {
        if (trunk.isEmpty()) return false;
        int topY = trunk.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int bottomY = trunk.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minX = trunk.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = trunk.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = trunk.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = trunk.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        for (int y = topY; y >= bottomY + (topY - bottomY) / 2; y--) {
            Set<Direction> attachedSides = new HashSet<>();
            List<BlockPos> ring = new ArrayList<>();
            for (BlockPos log : trunk) {
                if (log.getY() != y) continue;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos leaf = log.relative(side);
                    if (!loaded.test(leaf)) continue;
                    BlockState state = world.getBlockState(leaf);
                    if (naturalLeaf(state) && state.getValue(LeavesBlock.DISTANCE) == 1) {
                        attachedSides.add(side);
                        ring.add(leaf);
                    }
                }
            }
            // 至少两侧贴叶才像长出来的冠：单侧一片可能是搭棚子顺手靠的原木边。
            if (attachedSides.size() < 2) continue;
            if (countCrownLeaves(ring, world, loaded, minX, maxX, minZ, maxZ, y)) return true;
        }
        return false;
    }

    /** 从树冠起点那圈的叶子向外数：水平到树干外包盒再外扩两格（1x1 干即 ±2，2x2 干跟着放宽），竖直从起点层往下 1、往上 2。 */
    private static boolean countCrownLeaves(List<BlockPos> ring, BlockGetter world, Predicate<BlockPos> loaded,
            int minX, int maxX, int minZ, int maxZ, int anchorY) {
        Set<BlockPos> visited = new HashSet<>(ring);
        ArrayDeque<BlockPos> pending = new ArrayDeque<>(ring);
        int leaves = 0;
        while (!pending.isEmpty()) {
            BlockPos leaf = pending.removeFirst();
            // 只在树冠起点附近的小范围里数：再远的是邻树或别的东西。
            if (leaf.getX() < minX - 2 || leaf.getX() > maxX + 2 || leaf.getZ() < minZ - 2 || leaf.getZ() > maxZ + 2
                    || leaf.getY() < anchorY - 1 || leaf.getY() > anchorY + 2
                    || !loaded.test(leaf)) continue;
            if (!naturalLeaf(world.getBlockState(leaf))) continue;
            if (++leaves >= CROWN_LEAVES) return true;
            for (Direction side : Direction.values()) {
                BlockPos next = leaf.relative(side);
                if (visited.add(next)) pending.addLast(next);
            }
        }
        return false;
    }

    // 天然的树叶：树叶方块且不是玩家放的（放的树叶是持久的，不会腐烂）。
    private static boolean naturalLeaf(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT);
    }
}
