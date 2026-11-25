// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.drops;

import java.util.Set;

import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 捡起掉出来的东西：挖开一格、剪下羊毛之后，走过去让原版的拾取把地上的东西吸进包。
 *
 * <p>只捡这一下带出来的：动手前先记下脚边已经躺着哪些，动手后只捡新冒出来的，
 * 别人丢的、早就在地上的不去碰。进没进包以地上那件消失为准，不把"走过去了"当成"捡到了"。
 */
public interface PicksUpDrops {

    /**
     * 单次顺手拾取的因果域与预算上限：限制扫描圆心（固定锚点或当前角色）、扫描半径、
     * 单轮总耗时上限与单轮最大处理目标数，防止被附近持续产料的机器（如运转中的粉碎轮）磁吸漂移。
     *
     * @param anchor        固定扫描圆心方块坐标；为 null 时以当前角色坐标为中心
     * @param radiusBlocks  扫描半径（格）
     * @param maxTotalTicks 单轮总耗时上限（刻）；≤ 0 表示不设总耗时上限
     * @param maxTargets    单轮最多处理的掉落物实体数上限；≤ 0 表示不设目标数上限
     */
    record Scope(BlockPos anchor, double radiusBlocks, int maxTotalTicks, int maxTargets) {

        /** 常规拾取半径（格）：适用于采掘、专门采集等非单点交互场景。 */
        public static final double DEFAULT_RADIUS_BLOCKS = 8.0;
        /** 单次右键交互的因果半径（格）：只认被交互方块附近 3.5 格内新冒出的掉落物，避开邻近运转机器。 */
        public static final double INTERACTION_RADIUS_BLOCKS = 3.5;
        /** 单次右键交互顺手拾取的总耗时上限（刻，6 秒）：防止在产线旁久追不归。 */
        public static final int INTERACTION_MAX_TICKS = 20 * 6;
        /** 单次右键交互顺手拾取最多处理的掉落物实体数上限。 */
        public static final int INTERACTION_MAX_TARGETS = 8;
        /** 常规拾取单轮兜底总耗时上限（刻，30 秒）：不影响专门采集与捡回遗物，同时避免无限追机器吐料。 */
        public static final int DEFAULT_MAX_TICKS = 20 * 30;
        /** 常规拾取单轮兜底最大目标实体数（高于整包 41 格，保证捡遗物与连锁挖矿完整拾起）。 */
        public static final int DEFAULT_MAX_TARGETS = 48;

        /** 常规拾取范围：以角色为圆心 8 格，带 30 秒与 48 目标兜底上限。 */
        public static final Scope DEFAULT = new Scope(null, DEFAULT_RADIUS_BLOCKS, DEFAULT_MAX_TICKS, DEFAULT_MAX_TARGETS);

        public Scope {
            anchor = anchor == null ? null : anchor.immutable();
            if (!(radiusBlocks > 0)) {
                throw new IllegalArgumentException("拾取半径必须为正：" + radiusBlocks);
            }
        }

        /** 构造锚定在被交互目标坐标的窄因果域拾取范围（3.5 格、最多 6 秒、最多 8 个目标）。 */
        public static Scope aroundInteraction(BlockPos anchor) {
            return new Scope(anchor, INTERACTION_RADIUS_BLOCKS, INTERACTION_MAX_TICKS, INTERACTION_MAX_TARGETS);
        }
    }

    /** 角色附近此刻躺着哪些掉落物（实体编号）；动手前记一份。 */
    Set<Integer> nearby();

    /** 指定拾取因果域内此刻躺着哪些掉落物（实体编号）；延伸距离交互前按锚点同步留底，防漏记旧掉落。 */
    default Set<Integer> nearby(Scope scope) {
        return nearby();
    }

    /**
     * 走过去捡起动手后新冒出来的东西：先等一小会儿让掉落物冒出来（刚生成要过一两刻才看得到），
     * 一件都没冒出来就直接做完；捡不到的如实失败。
     */
    Action pickUpNewSince(Set<Integer> before);

    /**
     * 按指定的因果域锚点、半径与预算上限捡起新冒出来的掉落物：
     * 固定锚点时扫描圆心不再随角色走动漂移，达到总耗时或目标数上限即收手。
     */
    default Action pickUpNewSince(Set<Integer> before, Scope scope) {
        return pickUpNewSince(before);
    }

    /**
     * 走过去只捡指定游戏实体编号的那一件掉落物，无关的脚边杂物不碰；那一件没进本玩家背包就如实失败。
     */
    default Action pickUpOne(int entityId) {
        return pickUpNewSince(Set.of());
    }
}
