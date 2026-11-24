// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.Optional;
import java.util.OptionalInt;

import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读采集现场的只读接缝：一格现在是什么、熟没熟、这一柱列顶到哪。
 * 数据来自真实世界（含作物的成熟状态，例如小麦 age=7），实现在游戏接口层；
 * 测试里用替身摆现场。观察编号与记忆只是线索，动手前以这里的现场为准。
 */
public interface ReadsSpot {

    /** 这一格现在的方块 ID；没加载、是空气或目标只剩记忆时为 empty。 */
    Optional<String> blockTypeAt(WorldPosition at);

    /** 这种方块是不是作物（无论熟没熟）；收熟不收生只对作物有意义。 */
    boolean isCrop(String blockType);

    /** 这一格是不是熟了可以收的作物；作物没熟、或根本不是作物，都是 false。 */
    boolean matureCrop(WorldPosition at, String blockType);

    /** 这一柱列能站（或目标所在）的最高点；整列没加载时为 empty。position 目标缺高度时用它。 */
    OptionalInt surfaceY(int x, int z);

    /**
     * 这一格是不是功能设施方块（营火、容器、工作站、厨锅、床与模组带方块实体的加工设施）：
     * 离线替身未重写时默认按已知工作站与床识别，生产实现同时复用全仓功能方块判定。
     */
    default boolean isFacilityBlock(WorldPosition at, String blockType) {
        return blockType != null && (FacilityKinds.isWorkstationId(blockType) || FacilityKinds.isBed(blockType));
    }
}
