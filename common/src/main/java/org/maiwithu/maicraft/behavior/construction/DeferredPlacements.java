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
 * 人站高了一层（看得见的面多了），或者有生物挡着的格过了一会儿（等它走开）。
 * 什么都没变就不回头：别处放成一块不等于这一格有了新的路，不把所有放不上的格、每一面的站位都再走一遍。
 * 生物挡着的格回头几次还挡着，就不再按时回头，也不交给垫方块那些办法（垫方块挪不走生物），留到最后如实交代是谁挡着。
 * 纯记账，不读世界；"这一格还在等别的格""是谁挡着"由调用方告诉它。
 */
final class DeferredPlacements {

    /** 有生物挡着的格，过多少刻再回头（5 秒）：等挡着的生物走开。 */
    static final long CREATURE_RETRY_TICKS = 100;
    /** 有生物挡着的格最多按时回头几次；还挡着就如实记下，不再干等。 */
    static final int MAX_TIMED_RETRIES = 3;
    /** 目标下方多远以内新放的方块算"多了能站的地方"（水平格数）。 */
    private static final int NEW_STAND_RADIUS = 3;

    /** 为什么放一放。 */
    enum Reason {
        /** 蓝图里要等别的格先放好（带漏斗等下面的带子）。 */
        WAITS_ON_OTHERS,
        /** 每一面都试过，点不到或落下来不是要的样子。 */
        NO_FACE_WORKED,
        /** 有生物站在那一格里（原版不让放），或者挡在人和要点的那一面之间（点下去点到了它）。 */
        CREATURE_IN_THE_WAY
    }

    /**
     * 最后也没放成、交给调用方如实交代的格。
     *
     * @param creature 生物挡着时，挡着的是谁、在哪（"站在这一格里的 阿缕（touhou_little_maid:maid）"）；别的原因为 null
     */
    record Left(PlannedCell cell, Reason reason, String creature) {}

    /**
     * 一格放一放时的情况。
     *
     * @param reason   为什么放一放
     * @param creature 生物挡着时挡着的是谁；别的原因为 null
     * @param mark     放一放那一刻已经放成了几块（之后放成的才算新变化）
     * @param feetY    那一刻人站在第几层；不知道时为 null
     * @param retryAt  生物挡着的格到哪一刻回头；不按时回头的是 {@link Long#MAX_VALUE}
     */
    private record Entry(PlannedCell cell, Reason reason, String creature, int mark, Integer feetY, long retryAt) {}

    private final List<Entry> entries = new ArrayList<>();
    /** 这一阶段放成的每一块（蓝图格和临时方块都算），按放成的先后。 */
    private final List<BlockPos> placed = new ArrayList<>();
    /** 每格已经因为生物挡着回头过几次。 */
    private final Map<BlockPos, Integer> timedRetries = new HashMap<>();

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 放成了一块：之后放一放的格拿它判断情况变没变。 */
    void placed(BlockPos pos) {
        placed.add(pos.immutable());
    }

    /**
     * 放一放这一格（不是生物挡着的原因）。
     *
     * @param feet 人现在站在哪；不知道时为 null
     * @param now  现在第几刻
     */
    void defer(PlannedCell cell, Reason reason, BlockPos feet, long now) {
        defer(cell, reason, null, feet, now);
    }

    /**
     * 放一放这一格。
     *
     * @param creature 生物挡着时挡着的是谁、在哪；别的原因为 null
     * @param feet     人现在站在哪；不知道时为 null
     * @param now      现在第几刻
     */
    void defer(PlannedCell cell, Reason reason, String creature, BlockPos feet, long now) {
        long retryAt = Long.MAX_VALUE;
        // 生物挡着的格过一会儿再回头，等它走开；回头几次还挡着，就不再按时回头，留到最后如实交代。
        if (reason == Reason.CREATURE_IN_THE_WAY) {
            int tries = timedRetries.merge(cell.pos().immutable(), 1, Integer::sum);
            if (tries <= MAX_TIMED_RETRIES) retryAt = now + CREATURE_RETRY_TICKS;
        }
        entries.add(new Entry(cell, reason, creature, placed.size(), feet == null ? null : feet.getY(), retryAt));
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
        // 人站高了一层才多看得见几面；在高低不平的地上往下走一层看不到更多，不算情况变了。
        if (feet != null && entry.feetY() != null && feet.getY() > entry.feetY()) return true;
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
     * 什么都没变时，挑一格出来想别的办法（垫台阶、垫链）：挑最早放一放、不在等别的格、也不是生物挡着的那格。
     * 生物挡着的格不交出去：垫方块挪不走生物。没有这样的格时返回空。
     */
    Optional<PlannedCell> takeStuck(Predicate<PlannedCell> waitingOnOthers) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (entry.reason() != Reason.CREATURE_IN_THE_WAY && !waitingOnOthers.test(entry.cell())) {
                it.remove();
                return Optional.of(entry.cell());
            }
        }
        return Optional.empty();
    }

    /** 还有生物挡着、按时回头的时候没到的格：这时什么也做不了就站着等一会儿，不急着判放不成。 */
    boolean waitingForTime() {
        return entries.stream().anyMatch(entry -> entry.retryAt() != Long.MAX_VALUE);
    }

    /** 剩下的格（在等别的格、或生物回头几次还挡着）全部取出来，交给调用方如实交代。 */
    List<Left> drain() {
        List<Left> out = entries.stream().map(entry -> new Left(entry.cell(), entry.reason(), entry.creature())).toList();
        entries.clear();
        return out;
    }
}
