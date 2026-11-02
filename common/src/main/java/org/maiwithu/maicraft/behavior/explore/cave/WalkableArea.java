// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 量路：从脚下沿能站的格泛洪，量出这一片洞能走到哪——水平 24 格、上下 16 格、最多 4096 格，分刻量。
 *
 * <p>平走、上下一格算连通；许可能动土时往下跳不超过 3 格也算（回来时走到会垫块或挖台阶）；
 * 不许动土只认上下一格，走进去的地方都走得回来。挨着岩浆一格的、水里的不算能走。
 * 泛洪边上挨着水、岩浆、往下超过能跳的落差，记成"那边过不去"，带上挡着的是什么、落差多深。
 *
 * <p>量出来的只用来知道哪里连着、哪里走过：不报里面有什么，也不拿它找矿。
 */
public final class WalkableArea {

    /** 水平量多远（格）。 */
    static final int RADIUS = 24;
    /** 上下量多远（格）。 */
    static final int HEIGHT = 16;
    /** 最多量多少格。 */
    static final int MAX_CELLS = 4096;
    /** 一刻量多少格：量路不是这一刻的正事，别把刻占满。 */
    static final int CELLS_PER_TICK = 512;
    /** 许可能动土时往下跳最多几格还算连通。 */
    static final int MAX_DROP = 3;
    /** 过不去的落差往下最多量几格；更深的按这个数报。 */
    static final int DROP_MEASURE = 16;
    /** 同一种挡路离已记的那处这么近就算同一处，不一格记一条。 */
    private static final int SAME_BLOCKED = 4;

    /** 那边为什么过不去。 */
    public enum Reason { WATER, LAVA, DROP }

    /**
     * 一处过不去的地方。
     *
     * @param at     挡在哪一格
     * @param reason 是水、岩浆还是落差
     * @param depth  落差往下几格；水和岩浆为 0
     */
    public record Blocked(BlockPos at, Reason reason, int depth) {}

    private final CaveWorld world;
    private final BlockPos start;
    private final boolean mayDropDown;
    private final Deque<BlockPos> pending = new ArrayDeque<>();
    /** 量到的能站的格，和从脚下走过去要几步。 */
    private final Map<BlockPos, Integer> reached = new LinkedHashMap<>();
    private final List<Blocked> blocked = new ArrayList<>();

    /**
     * @param start       从哪一格量起（角色脚下）；站在半砖、台阶上读成下面一格时往上挪一格
     * @param mayDropDown 许可能不能动土：能的话往下跳三格以内也算连通
     */
    public WalkableArea(CaveWorld world, BlockPos start, boolean mayDropDown) {
        this.world = world;
        this.mayDropDown = mayDropDown;
        this.start = !world.standable(start) && world.standable(start.above()) ? start.above() : start.immutable();
        reached.put(this.start, 0);
        pending.add(this.start);
    }

    /** 量一刻；量完返回真。 */
    public boolean tick() {
        int budget = CELLS_PER_TICK;
        while (budget-- > 0 && !pending.isEmpty()) {
            if (reached.size() >= MAX_CELLS) {
                // 量到上限就当作量到这里为止：大溶洞一眼量不完，走过去再量。
                pending.clear();
                break;
            }
            BlockPos cell = pending.poll();
            int steps = reached.get(cell);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                expand(cell, steps, cell.relative(direction));
            }
        }
        return pending.isEmpty();
    }

    /** 量没量完。 */
    public boolean complete() {
        return pending.isEmpty();
    }

    /** 从哪一格量起。 */
    public BlockPos start() {
        return start;
    }

    /** 量到的能站的格与走过去要几步，先量到的在前；没量完时是已量到的部分。 */
    public Map<BlockPos, Integer> reached() {
        return reached;
    }

    /** 泛洪边上过不去的地方：水、岩浆、往下跳不下去的落差。 */
    public List<Blocked> blocked() {
        return List.copyOf(blocked);
    }

    // 往旁边一列迈一步：落脚格新、在范围里、不挨着岩浆，就收下接着往外量。
    private void expand(BlockPos from, int steps, BlockPos side) {
        if (!inBounds(side) || !world.loaded(side)) {
            return;
        }
        BlockPos next = stepOnto(from, side);
        if (next == null || reached.containsKey(next)) {
            return;
        }
        if (nearLava(next)) {
            note(next, Reason.LAVA, 0);
            return;
        }
        reached.put(next, steps + 1);
        pending.add(next);
    }

    // 往旁边一列走一步落在哪一格：平走、上一级（头顶要留得出跳的空）、下一级；许可能动土时再往下跳两三格。
    // 迈不过去的，看看是水、岩浆还是落差，记下来。
    private BlockPos stepOnto(BlockPos from, BlockPos side) {
        if (world.standable(side)) {
            return side;
        }
        if (world.standable(side.above()) && world.roomy(from.above(2))) {
            return side.above();
        }
        if (!world.roomy(side) || !world.roomy(side.above())) {
            noteLiquid(side);
            noteLiquid(side.above());
            return null;
        }
        int maxDrop = mayDropDown ? MAX_DROP : 1;
        for (int down = 1; down <= maxDrop; down++) {
            BlockPos below = side.below(down);
            if (world.standable(below)) {
                return below;
            }
            if (!world.roomy(below)) {
                noteLiquid(below);
                return null;
            }
        }
        noteDrop(side);
        return null;
    }

    // 往下跳不下去：一直往下量到落脚处，底下是水或岩浆就按水、岩浆记，不然记成多深的落差。
    private void noteDrop(BlockPos side) {
        int depth = 0;
        BlockPos cursor = side.below();
        while (depth < DROP_MEASURE && world.roomy(cursor)) {
            depth++;
            cursor = cursor.below();
        }
        CaveWorld.Liquid bottom = world.liquid(cursor);
        if (bottom == CaveWorld.Liquid.WATER) {
            note(side, Reason.WATER, 0);
        } else if (bottom == CaveWorld.Liquid.LAVA) {
            note(side, Reason.LAVA, 0);
        } else {
            note(side, Reason.DROP, depth);
        }
    }

    private void noteLiquid(BlockPos cell) {
        CaveWorld.Liquid liquid = world.liquid(cell);
        if (liquid == CaveWorld.Liquid.WATER) {
            note(cell, Reason.WATER, 0);
        } else if (liquid == CaveWorld.Liquid.LAVA) {
            note(cell, Reason.LAVA, 0);
        }
    }

    // 挨着岩浆一格（脚下、四周与斜角的脚边和脚下那一层）就不当能走的：一脚踩空就进岩浆了。
    private boolean nearLava(BlockPos feet) {
        if (world.liquid(feet.below()) == CaveWorld.Liquid.LAVA) {
            return true;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos beside = feet.offset(dx, 0, dz);
                if (world.liquid(beside) == CaveWorld.Liquid.LAVA
                        || world.liquid(beside.below()) == CaveWorld.Liquid.LAVA) {
                    return true;
                }
            }
        }
        return false;
    }

    // 同一种挡路离已记的那处 4 格以内算同一处：一片地下湖的岸边不记成几十条。
    private void note(BlockPos at, Reason reason, int depth) {
        for (Blocked known : blocked) {
            if (known.reason() == reason && known.at().distSqr(at) <= SAME_BLOCKED * SAME_BLOCKED) {
                return;
            }
        }
        blocked.add(new Blocked(at.immutable(), reason, depth));
    }

    private boolean inBounds(BlockPos cell) {
        int dx = cell.getX() - start.getX();
        int dz = cell.getZ() - start.getZ();
        return dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(cell.getY() - start.getY()) <= HEIGHT;
    }
}
