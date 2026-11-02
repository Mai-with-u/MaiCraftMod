// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 走过的地方：钻洞时脚踩过的点，每挪 2 格记一个；挑新地方时离它 6 格以内的算走过。
 *
 * <p>几次钻洞之间留着：走满一趟收场后再钻一次，接着走没走过的，不把刚走过的洞再走一遍。
 * 换维度清空；最多 4096 个点（约 8 公里），满了丢最早的。不是来时的路：回露天不倒着重放它。
 * 一个世界一份，由钻洞能力持有；只在客户端线程读写。
 */
public final class WalkedArea {

    /** 每挪这么多格记一个点。 */
    static final int STEP = 2;
    /** 最多记多少个点。 */
    static final int MAX_POINTS = 4096;
    /** 查"走没走过"时按这么大的方块分桶，只看相邻几只桶。 */
    private static final int BUCKET = 8;

    private final Deque<BlockPos> order = new ArrayDeque<>();
    private final Map<Long, List<BlockPos>> buckets = new HashMap<>();
    private String dimension;
    private BlockPos last;

    /** 记下角色此刻脚下：离上一个点挪了 2 格以上才记；换了维度先清空。 */
    public void note(WorldPosition feet) {
        if (feet == null) {
            return;
        }
        if (!Objects.equals(feet.dimension(), dimension)) {
            clear();
            dimension = feet.dimension();
        }
        BlockPos at = new BlockPos(feet.x(), feet.y(), feet.z());
        if (last != null && last.distSqr(at) < STEP * STEP) {
            return;
        }
        last = at;
        order.addLast(at);
        buckets.computeIfAbsent(bucketKey(at), key -> new ArrayList<>()).add(at);
        // 满了丢最早的：很久以前钻过的洞记不清了，再去也算新地方。
        while (order.size() > MAX_POINTS) {
            BlockPos oldest = order.removeFirst();
            List<BlockPos> bucket = buckets.get(bucketKey(oldest));
            bucket.remove(oldest);
            if (bucket.isEmpty()) {
                buckets.remove(bucketKey(oldest));
            }
        }
    }

    /** 这一格离走过的哪个点不超过 within 格（直线，高低也算）。 */
    public boolean walkedNear(BlockPos cell, int within) {
        int reach = Math.floorDiv(within, BUCKET) + 1;
        int bx = Math.floorDiv(cell.getX(), BUCKET);
        int by = Math.floorDiv(cell.getY(), BUCKET);
        int bz = Math.floorDiv(cell.getZ(), BUCKET);
        double limit = (double) within * within;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    List<BlockPos> bucket = buckets.get(BlockPos.asLong(bx + dx, by + dy, bz + dz));
                    if (bucket == null) continue;
                    for (BlockPos point : bucket) {
                        if (point.distSqr(cell) <= limit) return true;
                    }
                }
            }
        }
        return false;
    }

    /** 记了几个点。 */
    public int size() {
        return order.size();
    }

    private void clear() {
        order.clear();
        buckets.clear();
        last = null;
    }

    private static long bucketKey(BlockPos at) {
        return BlockPos.asLong(Math.floorDiv(at.getX(), BUCKET), Math.floorDiv(at.getY(), BUCKET),
                Math.floorDiv(at.getZ(), BUCKET));
    }
}
