// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.calc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 导航目标的语义指纹：把目标的类型和具体要求变成不可变的值键，比较分别重新构建的两个目标快照
 * 是不是同一个目标；位置相同但半径或威胁变了，也认得出目标已经不同。
 */
public record NavGoalFingerprint(String kind, List<?> parameters) {
    public NavGoalFingerprint {
        kind = Objects.requireNonNull(kind, "kind");
        parameters = List.copyOf(parameters);
    }

    public static NavGoalFingerprint of(NavGoal goal) {
        Objects.requireNonNull(goal, "goal");
        if (goal instanceof NavGoal.Exact g) {
            return key("exact", g.goal.asLong());
        }
        if (goal instanceof NavGoal.Column g) {
            return key("column", g.x, g.z, g.radius);
        }
        if (goal instanceof NavGoal.YLevel g) {
            return key("y_level", g.level);
        }
        if (goal instanceof NavGoal.Near g) {
            return key("near", g.goal.asLong(), g.radius);
        }
        if (goal instanceof NavGoal.Ring g) {
            return key("ring", g.goal.asLong(), g.inner, g.outer);
        }
        if (goal instanceof NavGoal.DistanceBand g) {
            return key("distance_band", g.focus.x, g.focus.y, g.focus.z, g.inner, g.outer);
        }
        if (goal instanceof NavGoal.NearGround g) {
            return key("near_ground", g.goal.asLong(), g.radius, g.verticalTolerance);
        }
        if (goal instanceof NavGoal.Adjacent g) {
            return key("adjacent", g.goal.asLong());
        }
        if (goal instanceof NavGoal.MineStance g) {
            return key("mine_stance", g.ore.asLong());
        }
        if (goal instanceof NavGoal.GetToBlock g) {
            return key("get_to_block", g.goal.asLong());
        }
        if (goal instanceof NavGoal.Composite g) {
            return key("composite", multiset(g.members.stream()
                    .map(NavGoal::semanticFingerprint).toList()));
        }
        if (goal instanceof NavGoal.MineColumn g) {
            return key("mine_column", g.ore.asLong(), g.maxBelow);
        }
        if (goal instanceof NavGoal.RunAway g) {
            return key("run_away", g.from.asLong(), g.maintainY);
        }
        // 目前只有一个旧式匿名目标，变化参数只有中心点；以后新增参数的自定义目标必须覆写 semanticFingerprint()。
        // 无法识别的自定义目标默认只比较类名和代表位置；有其他会变化的要求时，应由自定义实现提供自己的比较值。
        return key("custom:" + goal.getClass().getName(), goal.center().asLong());
    }

    private static NavGoalFingerprint key(String kind, Object... parameters) {
        return new NavGoalFingerprint(kind, List.of(parameters));
    }

    /** 组合目标不依赖输入顺序，但会保留重复成员对语义的影响。 */
    private static <T> Map<T, Integer> multiset(List<T> values) {
        Map<T, Integer> counts = new HashMap<>();
        for (T value : values) {
            counts.merge(value, 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }
}
