// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/**
 * 放一放的格：这一下放不上的格先记在这里做别的，等情况变了再回头。放一格向来不止一条路，也不是非得现在放。
 *
 * <p>回头的时机：旁边新放了一块（多了能点的面）、目标下方够得着的地方新放了一块（多了能站的地方）、
 * 人换了一层站着（看得见的面不一样了），或者原版这会儿不让放（多半是有生物站在那一格里）的格过了一会儿。
 * 什么都没变就不回头：原先只要别处放成一块就把放不上的格全部重试一遍，每格每一面都走一遍站位，
 * 一个屋顶 25 格轮着走，十二分钟里走站位失败四百多次。
 * 纯记账，不读世界；"这一格还在等别的格"由调用方告诉它。
 */
final class DeferredPlacements {

    /** 原版这会儿不让放的格，过多少刻再回头（5 秒）：等挡着的生物走开。 */
    static final long REFUSED_RETRY_TICKS = 100;
    /** 原版不让放的格最多按时回头几次；还不行就当成普通放不上的格处理。 */
    static final int MAX_TIMED_RETRIES = 3;
    /** 目标下方多远以内新放的方块算"多了能站的地方"（水平格数）。 */
    private static final int NEW_STAND_RADIUS = 3;

    /** 为什么放一放。 */
    enum Reason {
        /** 蓝图里要等别的格先放好（带漏斗等下面的带子）。 */
        WAITS_ON_OTHERS,
        /** 每一面都试过，点不到或落下来不是要的样子。 */
        NO_FACE_WORKED,
        /** 站位、朝向都对，原版这会儿不让放（落下来会是空，多半是有生物站在那一格里）。 */
        REFUSED_BY_GAME
    }

    /**
     * 一格放一放时的情况。
     *
     * @param reason  为什么放一放
     * @param mark    放一放那一刻已经放成了几块（之后放成的才算新变化）
     * @param feetY   那一刻人站在第几层；不知道时为 null
     * @param retryAt 原版不让放的格到哪一刻回头；不按时回头的是 {@link Long#MAX_VALUE}
     */
    private record Entry(PlannedCell cell, Reason reason, int mark, Integer feetY, long retryAt) {}

    private final List<Entry> entries = new ArrayList<>();
    /** 这一阶段放成的每一块（蓝图格和临时方块都算），按放成的先后。 */
    private final List<BlockPos> placed = new ArrayList<>();
    /** 每格已经按时回头过几次。 */
    private final Map<BlockPos, Integer> timedRetries = new HashMap<>();

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 放成了一块：之后放一放的格拿它判断情况变没变。 */
    void placed(BlockPos pos) {
        placed.add(pos.immutable());
    }

    /**
     * 放一放这一格。
     *
     * @param feet 人现在站在哪；不知道时为 null
     * @param now  现在第几刻
     */
    void defer(PlannedCell cell, Reason reason, BlockPos feet, long now) {
        long retryAt = Long.MAX_VALUE;
        // 原版这会儿不让放的格过一会儿再回头；回头几次还不让，就当成普通放不上的格去想别的办法。
        if (reason == Reason.REFUSED_BY_GAME) {
            int tries = timedRetries.merge(cell.pos().immutable(), 1, Integer::sum);
            if (tries <= MAX_TIMED_RETRIES) retryAt = now + REFUSED_RETRY_TICKS;
        }
        entries.add(new Entry(cell, reason, placed.size(), feet == null ? null : feet.getY(), retryAt));
    }

    /**
     * 取出情况变了、该回头再试的格（按放一放的先后）。
     *
     * @param waitingOnOthers 这一格是不是还在等蓝图里别的格先放好；等的格好了也算情况变了
     */
    List<PlannedCell> changed(BlockPos feet, long now, Predicate<PlannedCell> waitingOnOthers) {
        List<PlannedCell> out = new ArrayList<>();
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (changedFor(entry, feet, now, waitingOnOthers)) {
                out.add(entry.cell());
                it.remove();
            }
        }
        return out;
    }

    // 这一格放一放之后，情况有没有变到值得回头。
    private boolean changedFor(Entry entry, BlockPos feet, long now, Predicate<PlannedCell> waitingOnOthers) {
        BlockPos pos = entry.cell().pos();
        // 在等别的格的格：等的格放好了才回头，别处放成什么都不算。
        if (entry.reason() == Reason.WAITS_ON_OTHERS) {
            return entry.mark() < placed.size() && !waitingOnOthers.test(entry.cell());
        }
        if (entry.retryAt() <= now) return true;
        if (feet != null && entry.feetY() != null && feet.getY() != entry.feetY()) return true;
        for (int i = entry.mark(); i < placed.size(); i++) {
            BlockPos block = placed.get(i);
            if (block.distManhattan(pos) == 1) return true;
            int dy = pos.getY() - block.getY();
            if (dy >= 1 && dy <= 3 && Math.abs(block.getX() - pos.getX()) <= NEW_STAND_RADIUS
                    && Math.abs(block.getZ() - pos.getZ()) <= NEW_STAND_RADIUS) return true;
        }
        return false;
    }

    /**
     * 什么都没变时，挑一格出来想别的办法（垫台阶、垫链）：挑最早放一放、不在等别的格、也不在等按时回头的那格。
     * 没有这样的格时返回空。
     */
    Optional<PlannedCell> takeStuck(Predicate<PlannedCell> waitingOnOthers) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.retryAt() == Long.MAX_VALUE && !waitingOnOthers.test(entry.cell())) {
                it.remove();
                return Optional.of(entry.cell());
            }
        }
        return Optional.empty();
    }

    /** 还有按时回头、时候没到的格：这时什么也做不了就站着等一会儿，不急着垫方块或判放不成。 */
    boolean waitingForTime() {
        return entries.stream().anyMatch(entry -> entry.retryAt() != Long.MAX_VALUE);
    }

    /** 剩下的全是在等别的格：取出来交代"等的格没放成"。 */
    List<PlannedCell> drain() {
        List<PlannedCell> out = entries.stream().map(Entry::cell).toList();
        entries.clear();
        return out;
    }
}
