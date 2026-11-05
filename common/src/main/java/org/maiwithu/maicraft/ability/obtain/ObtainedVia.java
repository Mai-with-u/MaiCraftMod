// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.List;

import org.maiwithu.maicraft.behavior.acquire.cook.LeftCooking;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 拿东西的结果细节：这次东西实际从哪些途径拿到的，按拿到的先后排。
 * 写法与参数 via 的取值一致（craft、smelt、container、mine、harvest、trade、hunt、carried）；
 * 一条都没拿到时为空列表——结果里靠 changes 与问题交代，不编一条途径出来。
 * 一炉要烧很久、装好炉先走了时，再逐台写留着烧的那一炉。
 *
 * @param obtainedVia 实际拿到东西的途径；写进结果时就是 details.obtained_via，和能力说明里写的一致
 * @param leftCooking 装好炉先走、还在烧的那几炉（details.left_cooking）；没有为 null，结果里不出现
 */
record ObtainedVia(List<String> obtainedVia, List<LeftFurnace> leftCooking) implements ResultDetails {

    ObtainedVia {
        obtainedVia = List.copyOf(obtainedVia);
        leftCooking = leftCooking == null || leftCooking.isEmpty() ? null : List.copyOf(leftCooking);
    }

    /** 没有留着烧的：只有途径。 */
    ObtainedVia(List<String> obtainedVia) {
        this(obtainedVia, null);
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
}
