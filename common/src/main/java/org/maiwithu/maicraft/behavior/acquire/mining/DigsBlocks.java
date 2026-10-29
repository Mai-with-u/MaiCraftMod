// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖方块的执行接缝：走到一格方块跟前，持续挖到它消失，掉落物等角色自己捡。
 * 采集熟作物与采掘矿石都用它；现场动作（靠近、按住左键、确认方块消失）由游戏接口层
 * 用靠近与交互的公开接口实现，测试用替身。这个入口还没接上时返回 empty，来源如实报告不支持。
 */
public interface DigsBlocks {

    /** 挖一格的耐心：动手挖了这么久（刻）还没挖下来就当这一格挖不动，换下一格。类别：玩家常识。 */
    int DIG_PATIENCE_TICKS = 20 * 15;

    /** 为挖掉一格生成动作；做完时这一格已经是空气。 */
    Optional<Action> dig(BlockPos target);

    /**
     * 这一趟挖掘的规模与记账：挖楼梯、清障、采掘这些路径知道这一趟大概还要挖几格。挑工具按它算徒手也掉的方块换不换，
     * 身上没有对路的工具时按它算现做一把划不划算；现做走这次任务的许可，做了什么、为什么没做记进 records（没有就不记）。
     *
     * @param expectedBlocks 这一趟预计还要挖几格（估不出给 1）
     * @param permissions    这次任务的许可：现做工具放工作台、拿料都按它
     * @param records        发起任务的记账口；可以为 null
     */
    record ToolPrep(int expectedBlocks, Permissions permissions, TaskRecords records) {
        public ToolPrep {
            expectedBlocks = Math.max(1, expectedBlocks);
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    /** 挖一格，带上这一趟的规模：没有专门实现时和 dig 一样。 */
    default Optional<Action> dig(BlockPos target, ToolPrep prep) {
        return dig(target);
    }

    /**
     * 连锁挖一格：对准触发格挖，装了连锁挖的模组时按住连锁键，按形状把一批一起挖掉——
     * 模组给出的那一批每一格 mayBreak 都同意才连锁，不然只挖触发格。face 不为空时先从这一面挖
     * （开通道的形状按命中面算）。做完时触发格已经挖掉；哪些副格也挖掉了由调用方逐格复查。没有连锁挖时和 dig 一样。
     */
    default Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
            Predicate<BlockPos> mayBreak) {
        return dig(trigger);
    }

    /** 连锁挖一格，带上这一趟的规模：没有专门实现时和不带规模的一样。 */
    default Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
            Predicate<BlockPos> mayBreak, ToolPrep prep) {
        return digChained(trigger, face, shape, mayBreak);
    }

    /**
     * 为概率掉落挖一格（砾石出燧石、树叶出树苗、草出种子）：和 dig 一样挖到这一格没了，
     * 挑工具时不拿剪刀和带精准采集的（拿了只掉方块自己），有时运的优先。没有专门实现时和 dig 一样。
     */
    default Optional<Action> digForChanceDrops(BlockPos target) {
        return dig(target);
    }

    /** 装了连锁挖的模组：挑格、挑面时值得为连锁凑整片。没装时为假。 */
    default boolean chains() {
        return false;
    }

    /** 这一格此刻已经没了（空气或只剩流体）：连锁挖顺带挖掉的格不用再走过去挖。读不到时答还在。 */
    default boolean gone(TickContext context, BlockPos cell) {
        return false;
    }
}
