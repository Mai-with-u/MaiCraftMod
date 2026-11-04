// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

/**
 * 靠近记得的"从这里到不了"：从某处出发靠近一个对象，剩下的站位合起来再算也走不到，记下这一条；
 * 同一个目标运行里、起点 8 格内再要靠近同一个对象，直接按这条记忆收场，不再重算一轮。
 * 目标结束（换了目标运行）或角色离开起点 8 格以上就失效；手上没有目标时不记。
 * 只记方块、床这类钉在一格上的对象：实体会走动，这一刻到不了不代表下一刻还到不了。
 */
public final class UnreachableFromHere {

    /** 起点在这么多格内算"同一片起点"。 */
    static final double SAME_START = 8.0;

    /** 记着的一条：哪个目标运行里、从哪出发、到不了哪个对象。 */
    record Entry(long goalRun, InteractionKind kind, BlockPos anchor, BlockPos start) {}

    private final Supplier<OptionalLong> goalRun;
    private final List<Entry> entries = new ArrayList<>();

    /** @param goalRun 此刻主目标运行的编号；没有目标时为空 */
    public UnreachableFromHere(Supplier<OptionalLong> goalRun) {
        this.goalRun = Objects.requireNonNull(goalRun, "goalRun");
    }

    /** 手上没有目标运行时什么都不记的一份：离线清单、测试里用。 */
    public static UnreachableFromHere none() {
        return new UnreachableFromHere(OptionalLong::empty);
    }

    /** 记下：从 start 出发到不了这个对象。手上没有目标时不记；同一个对象只留最新一条。 */
    void remember(ApproachTarget target, BlockPos start) {
        OptionalLong run = goalRun.get();
        if (run.isEmpty() || start == null || target.anchorBlock() == null) return;
        entries.removeIf(entry -> sameObject(entry, target));
        entries.add(new Entry(run.getAsLong(), target.kind(), target.anchorBlock().immutable(), start.immutable()));
    }

    /**
     * 从 feet 出发靠近这个对象，有没有记着到不了。先丢掉失效的：不是此刻这个目标运行记的、
     * 或角色已经离开那条的起点 8 格以上。
     */
    Optional<Entry> recall(ApproachTarget target, BlockPos feet) {
        OptionalLong run = goalRun.get();
        entries.removeIf(entry -> run.isEmpty() || entry.goalRun() != run.getAsLong() || feet == null
                || entry.start().distSqr(feet) > SAME_START * SAME_START);
        return entries.stream().filter(entry -> sameObject(entry, target)).findFirst();
    }

    private static boolean sameObject(Entry entry, ApproachTarget target) {
        return entry.kind() == target.kind() && entry.anchor().equals(target.anchorBlock());
    }
}
