// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.Objects;
import java.util.function.Supplier;

import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoal;
import org.maiwithu.maicraft.behavior.navigation.calc.NavGoalFingerprint;
import org.maiwithu.maicraft.behavior.navigation.calc.StandingDry;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;

/**
 * 把项目的目标判断交给 Baritone：把三个坐标转成脚位格，再转发到达判断和搜索估价；这里不额外增加距离或高度条件。
 *
 * <p>要求站在干地上的目标（出行目的地），搜索的终点也只要干地格：引擎在水面格收线的话，
 * 路线走完人停在水里，到达判据永远不放行，跑的只能在原地来回打转或干等下一轮路线——
 * 收尾段就是这么死的。干地可站按 {@link BlockPassability#isDryStandable} 那一份事实判，
 * 与到达那一刻用的身体判据同源。别的目标（采矿站位、交互站位）水底的格照样能当终点，水下的活才干得了。
 */
final class MaiCraftGoalAdapter implements Goal {
    private final NavGoal delegate;
    private final NavGoalFingerprint fingerprint;
    /** 读现场地形，用来核候选脚位是不是干地；还没读到世界（没上路）时给 null，只按位置条件放行。 */
    private final Supplier<BlockGetter> world;

    MaiCraftGoalAdapter(NavGoal delegate) {
        this(delegate, () -> null);
    }

    MaiCraftGoalAdapter(NavGoal delegate, Supplier<BlockGetter> world) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.world = Objects.requireNonNull(world, "world");
        // 同一静止目标每刻重新包装仍属同一搜索请求；真正移动或改变容差时才让旧失败失效。
        // 干地与否是世界状态不是目标语义，不进指纹。
        this.fingerprint = delegate.semanticFingerprint();
    }

    NavGoal delegate() {
        return delegate;
    }

    /** 取回包装前的项目目标；非本适配器包装的目标（调试或原版目标）返回 null。 */
    static NavGoal unwrap(Goal goal) {
        return goal instanceof MaiCraftGoalAdapter adapter ? adapter.delegate() : null;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        if (!delegate.isAt(feet)) {
            return false;
        }
        // 只有要求干地的目标（出行目的地）再加一道干地核验；宽松目标与采矿、交互这类站实目标按位置条件放行，
        // 水底的站位照样是终点——不然湖底的黏土、河床的沙砾搜不到终点，全报走不到。
        if (!StandingDry.requiredBy(delegate)) {
            return true;
        }
        BlockGetter level = world.get();
        return level == null || BlockPassability.isDryStandable(level, feet);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return delegate.heuristic(new BlockPos(x, y, z));
    }

    @Override public boolean equals(Object other) {
        return other instanceof MaiCraftGoalAdapter adapter && fingerprint.equals(adapter.fingerprint);
    }
    @Override public int hashCode() { return fingerprint.hashCode(); }

    @Override
    public String toString() {
        return "MaiCraftGoal{" + delegate.getClass().getSimpleName()
                + " center=" + delegate.center().toShortString() + '}';
    }
}
