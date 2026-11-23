// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读取已登记建筑档案与在役世界机器档案的结构保护区只读接缝。
 *
 * <p>生产实现由启动侧把建筑档案库与机器档案库抄成线程安全快照，供后台寻路线程与客户端线程共用；
 * 离线测试直接喂只读实现。
 */
@FunctionalInterface
public interface ReadsStructureProtection {

    /**
     * 这一格是不是已登记且未退役的建筑（过滤单格残档）或在役世界机器的结构格（含机器里的流体源格）。
     * 寻路清障、采掘、收回工作站与箱顶挖盖问这一份。
     */
    StructureProtection blockAt(WorldPosition position);

    /**
     * 这一格流体受不受建筑或机器结构保护：除建筑/机器声明的非空气格（含流体源格）外，
     * 也保护落在已登记建筑外框范围内的水池、农田或喷泉水源。
     */
    default StructureProtection fluidAt(WorldPosition position) {
        return blockAt(position);
    }

    /** 还没接上或世界里没有任何建筑与机器档案时：一格都不保护。 */
    ReadsStructureProtection NONE = position -> StructureProtection.NONE;
}
