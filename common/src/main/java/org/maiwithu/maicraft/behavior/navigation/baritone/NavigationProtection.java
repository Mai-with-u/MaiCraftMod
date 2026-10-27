// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把当前走到任务的保护要求交给内嵌 Baritone：哪些格不得破坏或掩埋，哪些格身体不得进入。
 * 后台搜索与实际走路都从这里取同一份保护；每次安装先复制集合再整体替换，搜索侧拿到的
 * 快照因此与接单时一致，不会被下一次任务中途改写。
 *
 * <p>除了这一趟的保护要求，还有这个世界的保护判断（别人放的方块、记住的区域、归属拿不准的格子）：
 * 进世界时装进来、离开时摘掉，挖路与垫路都要过它，不挖穿别人的房子。它在后台搜索线程上也会被问，
 * 装进来的判断必须能在任何线程上读。
 *
 * <p>角色被封死在小坑里、按任务许可已经挖不出去时，这一趟的保护要求会放开别人放的普通方块：
 * 算路时它们能挖但代价很高，走路时挖之前再问一次这一格是不是还是那种方块。出坑就收回。
 *
 * <p>这里用一处静态登记，是因为内嵌 Baritone 的搜索与移动对象都由它自己创建，
 * 无法从构造函数把服务传进去；走到的实现方在接单时把保护要求装进来，结束时清掉。
 */
public final class NavigationProtection {

    private static final AtomicReference<Snapshot> CURRENT = new AtomicReference<>(Snapshot.EMPTY);
    private static final AtomicReference<CellRule> WORLD = new AtomicReference<>(CellRule.NONE);

    /** 被困时挖一格别人放的普通方块，按挖同一格天然方块的这么多倍算：路线先走空当与天然方块，只挖绕不开的那几格。 */
    public static final double ESCAPE_BREAK_MULTIPLIER = 20;

    /**
     * 这个世界的保护判断：一格方块受不受保护；任何线程都能问。
     * 方块由调用方带进来：后台算路拿它自己读到的那份，走路时拿当刻读到的；判断本身不回头读现场，
     * 因为后台线程上碰不了当刻的角色与世界。
     */
    @FunctionalInterface
    public interface CellRule {
        /** 什么都不保护：没进世界时用它。 */
        CellRule NONE = (x, y, z, state) -> false;

        /** @param state 调用方读到的这一格方块；读不到传 null，推断那一路就不猜 */
        boolean protects(int x, int y, int z, BlockState state);

        /**
         * 角色被封死在小坑里时，这一格能不能挖开出去：是别人放的普通方块，就给放它的人的编号；不能挖给空。
         * 只在这一趟放开了别人的普通方块时才问；任何线程都能问。
         */
        default Optional<String> placedByOtherWhenTrapped(int x, int y, int z, BlockState state) {
            return Optional.empty();
        }
    }

    private NavigationProtection() {}

    /** 安装一份新的保护要求；内容与当前一致时不替换，返回是否发生了变化。 */
    public static boolean install(LongSet sacred, LongSet noEntryCells, int minimumFeetY) {
        return install(sacred, noEntryCells, minimumFeetY, false);
    }

    /** 同上；throughOthers 为真表示这一趟被封死在小坑里，放开了别人放的普通方块。 */
    public static boolean install(LongSet sacred, LongSet noEntryCells, int minimumFeetY, boolean throughOthers) {
        Snapshot next = new Snapshot(copy(sacred), copy(noEntryCells), minimumFeetY, throughOthers);
        boolean changed = !next.equals(CURRENT.get());
        CURRENT.set(next);
        return changed;
    }

    /** 取回当前保护要求；搜索开始前调用一次并冻结，供整个计算过程使用。 */
    public static Snapshot snapshot() {
        return CURRENT.get();
    }

    /** 进世界时装上这个世界的保护判断；离开世界时装回 {@link CellRule#NONE}。 */
    public static void installWorldRule(CellRule rule) {
        WORLD.set(rule == null ? CellRule.NONE : rule);
    }

    /** 这一格受不受这个世界的保护（别人放的、记住的区域里、归属拿不准、像玩家放的门）：挖路与垫路前问它。 */
    public static boolean worldProtects(int x, int y, int z, BlockState state) {
        return WORLD.get().protects(x, y, z, state);
    }

    /**
     * 按一份保护要求问：这一格是不是放开给脱困的别人的普通方块，是就给放它的人的编号。
     * 这一趟没放开、这一格是这次任务自己保护的，都给空。算路用冻结的那份，走路与挖用此刻生效的那份。
     */
    public static Optional<String> escapeOwner(Snapshot policy, int x, int y, int z, BlockState state) {
        if (!policy.throughOthers() || policy.protects(x, y, z)) return Optional.empty();
        return WORLD.get().placedByOtherWhenTrapped(x, y, z, state);
    }

    /** 此刻生效的保护要求下，这一格是不是放开给脱困的别人的普通方块：走路时挖之前再问一次。 */
    public static Optional<String> escapeOwner(BlockPos pos, BlockState state) {
        return pos == null ? Optional.empty() : escapeOwner(CURRENT.get(), pos.getX(), pos.getY(), pos.getZ(), state);
    }

    /** 走到结束后清空保护，避免上一次任务的保护格挡住下一次无关路线。 */
    public static void clear() {
        CURRENT.set(Snapshot.EMPTY);
    }

    /** 这一刻的实时保护检查：搜索走冻结的快照，实际挖掘与站位走这里；state 是此刻读到的这一格方块。 */
    public static boolean protects(BlockPos pos, BlockState state) {
        return pos != null && (CURRENT.get().protects(pos.getX(), pos.getY(), pos.getZ())
                || worldProtects(pos.getX(), pos.getY(), pos.getZ(), state));
    }

    /** 这一刻的实时禁入检查：身体是否被允许站进这一格。 */
    public static boolean forbidsEntry(BlockPos pos) {
        return pos != null && CURRENT.get().forbidsEntry(pos.getX(), pos.getY(), pos.getZ());
    }

    private static LongSet copy(LongSet cells) {
        if (cells == null || cells.isEmpty()) return LongSets.emptySet();
        LongOpenHashSet copied = new LongOpenHashSet(cells.size());
        copied.addAll(cells);
        return LongSets.unmodifiable(copied);
    }

    /**
     * 一份不可变的保护要求：受保护格不得破坏或掩埋；禁入格与脚位下界约束身体可站的位置。
     * 只禁修改的格并不自动禁止身体进入，两个集合各管各的。throughOthers 为真时这一趟被封死在小坑里，
     * 放开了别人放的普通方块（能挖但代价很高）。
     */
    public record Snapshot(LongSet protectedCells, LongSet noEntryCells, int minimumFeetY, boolean throughOthers) {
        public static final Snapshot EMPTY =
                new Snapshot(LongSets.emptySet(), LongSets.emptySet(), Integer.MIN_VALUE, false);

        public Snapshot {
            protectedCells = LongSets.unmodifiable(new LongOpenHashSet(protectedCells));
            noEntryCells = LongSets.unmodifiable(new LongOpenHashSet(noEntryCells));
        }

        public boolean protects(int x, int y, int z) {
            return protectedCells.contains(BlockPos.asLong(x, y, z));
        }

        public boolean forbidsEntry(int x, int y, int z) {
            return y < minimumFeetY || noEntryCells.contains(BlockPos.asLong(x, y, z));
        }

        /** 同一份保护要求，只把放不放开别人的普通方块换掉：只读复算按"假如放开"算一次时用。 */
        public Snapshot withThroughOthers(boolean open) {
            return new Snapshot(protectedCells, noEntryCells, minimumFeetY, open);
        }

        /** 是否带身体侧的约束；没有时移动搜索可以跳过整圈站位核对。 */
        public boolean hasEntryConstraints() {
            return minimumFeetY != Integer.MIN_VALUE || !noEntryCells.isEmpty();
        }
    }
}
