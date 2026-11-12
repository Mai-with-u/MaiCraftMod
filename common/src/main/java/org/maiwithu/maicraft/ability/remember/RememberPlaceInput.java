// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.remember;

import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次记（或忘）地点、圈地盘、按区域清记忆的任务输入：名字、做哪种操作、要记下的位置与半径、动手前这个名字记成什么。
 * 不可变；名字在能力的决定阶段就去过首尾空白，位置带维度。
 *
 * @param name           地点或地盘的名字，原样比较；按范围清记忆时为 null
 * @param operation      记地点、圈地盘、忘掉一个名字，还是按范围清掉失效记忆
 * @param position       记地点时是那个位置，圈地盘与按范围清理时是中心；忘掉一个名字时为 null
 * @param radius         圈地盘或按范围清理的半径（格）；别的操作为 0
 * @param previous       动手前这个名字记成的地点；没记过为 null，写进结果的 previous_position
 * @param previousRegion 动手前同名的地盘；没圈过为 null。圈地盘时写进结果的 previous_position、previous_radius，
 *                       忘掉时写进 forgotten_region
 */
record RememberPlaceInput(String name, Operation operation, WorldPosition position, int radius,
        WorldPosition previous, RememberedRegion previousRegion) implements TaskInput {

    /** 记一个地点、圈一块地盘、忘掉同名的地点与地盘、按范围清掉一片失效记忆。 */
    enum Operation { REMEMBER, REGION, FORGET, FORGET_AREA }

    RememberPlaceInput {
        if (operation == null) throw new IllegalArgumentException("要写清是记、圈还是忘");
        if (operation != Operation.FORGET_AREA && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("地点名不能为空");
        }
        if (operation != Operation.FORGET && position == null) {
            throw new IllegalArgumentException("记地点、圈地盘、按范围清理都要有位置");
        }
        if ((operation == Operation.REGION || operation == Operation.FORGET_AREA) && radius <= 0) {
            throw new IllegalArgumentException("半径必须为正：" + radius);
        }
        if (operation == Operation.FORGET && previous == null && previousRegion == null) {
            throw new IllegalArgumentException("忘掉要有记过的地点或地盘");
        }
    }

    /** 记一个地点：同名覆盖，previous 是覆盖前记在哪。 */
    static RememberPlaceInput place(String name, WorldPosition position, WorldPosition previous) {
        return new RememberPlaceInput(name, Operation.REMEMBER, position, 0, previous, null);
    }

    /** 圈一块地盘：同名覆盖，previousRegion 是覆盖前的那块。 */
    static RememberPlaceInput region(String name, WorldPosition center, int radius, RememberedRegion previousRegion) {
        return new RememberPlaceInput(name, Operation.REGION, center, radius, null, previousRegion);
    }

    /** 忘掉同名的地点与地盘：哪样记过就忘哪样。 */
    static RememberPlaceInput forget(String name, WorldPosition previous, RememberedRegion previousRegion) {
        return new RememberPlaceInput(name, Operation.FORGET, null, 0, previous, previousRegion);
    }

    /** 按范围清掉一片失效记忆：中心与半径，不需要名字。 */
    static RememberPlaceInput forgetArea(WorldPosition center, int radius) {
        return new RememberPlaceInput(null, Operation.FORGET_AREA, center, radius, null, null);
    }

    @Override public String describe() {
        return switch (operation) {
            case REMEMBER -> "把「" + name + "」记在 " + position;
            case REGION -> "把 " + position + " 周围 " + radius + " 格圈成地盘「" + name + "」";
            case FORGET -> "忘掉「" + name + "」";
            case FORGET_AREA -> "清掉 " + position + " 周围 " + radius + " 格里的失效记忆";
        };
    }
}
