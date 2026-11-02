// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 钻洞现场：量路、看矿、认洞里洞外要用的只读世界事实。实现在本包的读端，测试用替身；每刻的读数只代表本刻。
 */
public interface CaveWorld {

    /** 角色此刻脚下那一格（带维度）；没有角色时给 null。 */
    WorldPosition currentSpot();

    /** 角色此刻的视角角（度，0 朝南顺时针增大）；没有角色时给 null。 */
    Float facingYaw();

    /** 这一格所在的区块加载了没有；没加载的不量。 */
    boolean loaded(BlockPos cell);

    /** 能站：脚、头两格能进人，脚下踩得住，不在水里。 */
    boolean standable(BlockPos feet);

    /** 能进人：身体占得下这一格，不是水也不是岩浆。 */
    boolean roomy(BlockPos cell);

    /** 这一格是水、岩浆，还是都不是。 */
    Liquid liquid(BlockPos cell);

    /** 这一格的天空光（0..15）：露天 15，往遮挡下面每格少 1。 */
    int skyLight(BlockPos cell);

    /** 从角色眼睛看得见这一格（过视线闸，与 find、采掘同一套遮挡判断）。 */
    boolean visible(BlockPos cell);

    /**
     * 把半径内的矿、岩浆与留意清单里的方块登记进扫描并推进一点：扫描分刻走，扫稳了答真。
     * 看一眼的那两秒里每刻问一次，转完头时这一片也就扫稳了。
     */
    boolean prepareSight(int radius, List<String> interestBlocks);

    /** 半径内视线没被挡住的矿、岩浆与留意清单里的方块：露在外面、过视线闸，带上那一块有多亮；近的在前。 */
    List<Sighting> blocksInSight(int radius, List<String> interestBlocks);

    /** 液体：钻洞不往水里、岩浆里走，过不去的地方要说清挡着的是什么。 */
    enum Liquid { NONE, WATER, LAVA }

    /**
     * 看见的一格。
     *
     * @param blockId  方块注册 ID
     * @param pos      在哪
     * @param ore      是矿（挂在 #c:ores 下的，原版矿与模组矿都算）
     * @param lava     是岩浆
     * @param interest 是留意清单里的
     * @param light    这一块露出来的那几面挨着的空气格里最亮的亮度（0..15，方块光与天空光取大）：暗处远的矿看不清
     */
    record Sighting(String blockId, BlockPos pos, boolean ore, boolean lava, boolean interest, int light) {

        public Sighting {
            pos = pos.immutable();
        }
    }
}
