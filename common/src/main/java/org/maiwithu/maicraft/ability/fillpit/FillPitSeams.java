// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fillpit;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.perception.CampPits;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 填坑任务的现场读写与动作接缝：判断与任务编排不直接碰客户端世界，离线测试喂替身即可验证选坑、许可边界与废料耗尽收尾。
 */
final class FillPitSeams {

    private FillPitSeams() {}

    interface Site {
        BlockPos feet(TickContext context);

        String dimension(TickContext context);

        Optional<WorldPosition> seen(String seenId);

        Optional<WorldPosition> landmark(String name);

        List<SceneSign> signs();

        boolean cellProtected(WorldPosition position, Set<String> protectedLandmarks);

        List<CampPits.Pit> scanPits(TickContext context, BlockPos origin, int radius, boolean requireCamp);

        /** 角色身上扣除预留需求后现成多余的可回填方块（泥土、圆石等），不为填坑另去挖料或翻箱。 */
        List<String> availableFillers(TickContext context);

        /** 这一格此刻是否仍是待回填的干空位且没有生物站在里面。 */
        boolean needsFill(TickContext context, BlockPos cell);

        /** 若角色正站在坑里或离待填格超过手长，返回坑沿外侧最近的可站平地格；原地就能安全够着时返回空。 */
        Optional<BlockPos> rimStandFor(TickContext context, BlockPos cell, List<BlockPos> pitCells);

        Action walkTo(BlockPos feet);

        Optional<Action> placeFiller(TickContext context, BlockPos cell, String fillerId);
    }
}
