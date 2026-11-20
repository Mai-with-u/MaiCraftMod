// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.OptionalInt;

import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 箱式加工站对进料箱与出料箱的原生容器操作接缝：往进料箱投料、从出料箱取成品、查看进料箱剩余原料。
 * 生产实现走走到跟前、右键点开界面、逐笔搬运与关箱的全原生交互；离线测试用替身实现全路径验证。
 */
public interface ContainerStationOps {

    /** 往投料容器放原料的动作；完成后可读取确认投入件数与关箱前箱内该原料剩余件数。 */
    interface FeedAction extends Action {
        /** 确认投入该进料箱（含刚放进去就被下方漏斗或溜槽立刻抽走）的原料总件数。 */
        int delivered();

        /** 关箱前进料箱内该原料的剩余件数；若放进瞬间全部被抽走则为 0。 */
        int remainingInInput();
    }

    /** 从出料容器取成品的动作；完成后可读取本次实际取出的件数，同箱进出时顺带可读该原料剩余件数。 */
    interface TakeAction extends Action {
        /** 这一趟确认取出进背包的目标成品件数。 */
        int taken();

        /** 关箱前容器里指定原料的剩余件数（仅同箱进出、且成功读到界面时有值）。 */
        OptionalInt remainingIngredient();
    }

    /** 打开进料容器核对指定原料还剩几件的动作，用于出料箱 0 产出时判断机器是否在抽料。 */
    interface PeekAction extends Action {
        /** 进料容器里此刻该原料的总件数。 */
        int remainingIngredient();
    }

    /** 走到投料箱点开并投入最多 count 件指定原料。 */
    FeedAction feed(WorldPosition input, String stationName, String ingredientItemId, int count,
            Permissions permissions);

    /** 走到出料箱点开并尝试取出最多 wantCount 件目标成品；ingredientItemId 非空时顺带数出同箱内该原料件数。 */
    TakeAction takeOutput(WorldPosition output, String stationName, WantedItem result, int wantCount,
            String ingredientItemId, Permissions permissions);

    /** 走到进料箱点开查看指定原料此刻还剩几件，看完即关箱。 */
    PeekAction peekInput(WorldPosition input, String stationName, String ingredientItemId, Permissions permissions);
}
