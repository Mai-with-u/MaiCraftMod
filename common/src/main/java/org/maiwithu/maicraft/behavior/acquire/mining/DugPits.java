// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 采掘留下的坑位账本：记录角色在地面采掘或往下挖台阶时亲手挖出的深度不低于两格的竖坑或台阶入口，
 * 以及收工前有没有用现成的次要方块回填封平。
 *
 * <p>每个世界一份，{@code obtain} 开场时记下当前条数，收场时按增量取出这一场留下的坑写进 {@code details.pits}；
 * 只记角色本次任务亲手挖出的坑，周围原有的工程坑、水坑、农田灌溉坑不进这本账。
 */
public final class DugPits {

    /**
     * 一处深度不低于两格的地面坑口或台阶入口。
     *
     * @param at     坑口地面那一格的坐标与维度
     * @param depth  最深处比周围地面低几格（至少为 2）
     * @param filled 坑口是不是已经填平不掉人
     * @param note   一句话说明填了什么，或为什么没填
     */
    public record Pit(WorldPosition at, int depth, boolean filled, String note) {
        public Pit {
            Objects.requireNonNull(at, "at");
            if (depth < 2) throw new IllegalArgumentException("深度至少两格才算掉人的坑: " + depth);
            Objects.requireNonNull(note, "note");
        }
    }

    private final List<Pit> recorded = new ArrayList<>();

    /** 当前已记下的总条数；任务开场时取作游标。 */
    public int mark() {
        return recorded.size();
    }

    /** 记录一处本次采掘挖出的坑及其回填结果。 */
    public void record(Pit pit) {
        recorded.add(Objects.requireNonNull(pit, "pit"));
    }

    /** 取出从给定游标起新记下的全部坑位。 */
    public List<Pit> since(int mark) {
        int from = Math.clamp(mark, 0, recorded.size());
        return List.copyOf(recorded.subList(from, recorded.size()));
    }
}
