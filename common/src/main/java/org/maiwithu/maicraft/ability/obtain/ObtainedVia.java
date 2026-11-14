// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.List;

import org.maiwithu.maicraft.behavior.acquire.cook.LeftCooking;
import org.maiwithu.maicraft.behavior.acquire.mining.ChainMiningLedger;
import org.maiwithu.maicraft.behavior.acquire.mining.DugPits;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 拿东西的结果细节：这次东西实际从哪些途径拿到的，按拿到的先后排。
 * 写法与参数 via 的取值一致（craft、smelt、container、mine、harvest、trade、hunt、carried）；
 * 一条都没拿到时为空列表——结果里靠 changes 与问题交代，不编一条途径出来。
 * 一炉要烧很久、装好炉先走了时，再逐台写留着烧的那一炉；采掘留坑与动过挖掘时的连锁统计一并如实列出。
 *
 * @param obtainedVia 实际拿到东西的途径；写进结果时就是 details.obtained_via，和能力说明里写的一致
 * @param leftCooking 装好炉先走、还在烧的那几炉（details.left_cooking）；没有为 null，结果里不出现
 * @param pits        本次采掘在地面留下的两格及以上深坑或台阶入口（details.pits）；没有为 null，结果里不出现
 * @param chainMining 本次任务期间动过方块挖掘时的连锁统计（details.chain_mining）；没挖过方块为 null，结果里不出现
 */
record ObtainedVia(List<String> obtainedVia, List<LeftFurnace> leftCooking, List<Pit> pits,
        ChainMiningLedger.Summary chainMining) implements ResultDetails {

    ObtainedVia {
        obtainedVia = List.copyOf(obtainedVia);
        leftCooking = leftCooking == null || leftCooking.isEmpty() ? null : List.copyOf(leftCooking);
        pits = pits == null || pits.isEmpty() ? null : List.copyOf(pits);
    }

    /** 没有留着烧的、没留坑、也没动过挖掘：只有途径。 */
    ObtainedVia(List<String> obtainedVia) {
        this(obtainedVia, null, null, null);
    }

    ObtainedVia(List<String> obtainedVia, List<LeftFurnace> leftCooking) {
        this(obtainedVia, leftCooking, null, null);
    }

    ObtainedVia(List<String> obtainedVia, List<LeftFurnace> leftCooking, List<Pit> pits) {
        this(obtainedVia, leftCooking, pits, null);
    }

    /**
     * 留着烧的一炉，details.left_cooking 里的一项：炉子在哪、什么炉、烧出什么、原料、投入口里还在烧几件、
     * 约几秒烧好、背包里还有几件这种原料没装、挂上的计时编号（挂满了没挂上为 null，结果里不出现）。
     */
    record LeftFurnace(int x, int y, int z, String dimension, String block, String item, String ingredient,
            int loaded, long readyInSeconds, int ingredientsInBackpack, Integer timerId) {

        static LeftFurnace of(LeftCooking cooking) {
            return new LeftFurnace(cooking.furnace().x(), cooking.furnace().y(), cooking.furnace().z(),
                    cooking.furnace().dimension(), cooking.kind().blockType(), cooking.resultItemId(),
                    cooking.ingredientItemId(), cooking.loaded(), cooking.readyInSeconds(),
                    cooking.ingredientsInBackpack(), cooking.timerId());
        }
    }

    /** 采掘在地面挖出的一处深坑或台阶入口，details.pits 里的一项。 */
    record Pit(WorldPosition at, int depth, boolean filled, String note) {
        static Pit of(DugPits.Pit pit) {
            return new Pit(pit.at(), pit.depth(), pit.filled(), pit.note());
        }
    }
}
