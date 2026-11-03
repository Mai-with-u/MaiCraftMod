// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.interaction.DigTool;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;

/**
 * 把一格方块收进包：走到够得着的地方 → 挖掉它 → 走过去捡起这一下掉出来的东西。
 * 像真人挖矿一样一步步来，不隔空挖，也不把掉在地上的东西丢着不管。
 *
 * <p>收没收到、收了几件以背包的重新清点为准；这个动作只保证每一步都如实做了：
 * 一批里有一格走不到、挖不动就跳过它接着收别的，挖下来的照样去捡；一格都没收下来才以问题失败，
 * 掉落物捡不到如实失败。实现接在启动时创建并登记上，测试用替身。
 */
public interface CollectsBlocks {

    /**
     * @param cell        要收的那一格
     * @param permissions 这次任务的许可：走过去能动多少地形按它来
     * @return 收这一格的动作；接不上时为空，调用方按"这条路还没通"换路
     */
    Optional<Action> collect(BlockPos cell, Permissions permissions);

    /**
     * 把相邻的一批方块收进包：一格一格走到跟前挖掉，整批挖完再去捡掉出来的东西——像真人砍一棵树、挖一片矿，
     * 不是挖一格捡一格。没有成批实现的接缝按一格一格收。
     *
     * @param cells 要收的格子，按先后
     */
    default Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions) {
        return collectBatch(cells, permissions, null, null, null);
    }

    /**
     * 同上，挖的时候用连锁挖：装了连锁挖的模组时，挖一格按住连锁键把和它连着的同种方块（整条矿脉、整根树干）一起挖掉，
     * 模组给出的那一批每一格 chainable 都同意才连锁；一起挖掉的格不用再走过去挖，掉落物整批挖完一起捡。
     * 没有连锁实现的接缝照常一格一格收。
     *
     * @param chainable 连锁挖到的这一格能不能挖：天然长的、会掉要的东西的、许可准挖的
     */
    default Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions, Predicate<BlockPos> chainable) {
        return collectBatch(cells, permissions);
    }

    /**
     * 同上，并把跳过的格告诉调用方：走不到跟前、挖了半天挖不下来的那一格交给 givenUp，
     * 采掘据此记一阵、先挑别处。没有跳过实现的接缝不报。
     *
     * @param givenUp 一格做不成、跳过它时收到那一格
     */
    default Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions, Predicate<BlockPos> chainable,
            Consumer<BlockPos> givenUp) {
        return collectBatch(cells, permissions, chainable);
    }

    /**
     * 同上，并带上目标里点名的工具：写了就只用那一件，身上没有就直接失败，不换成别的；
     * 不写时按每格代价自动挑。没有成批实现的接缝没有带工具的入口，按一格一格收、忽略点名。
     *
     * @param digTool 目标里点名的工具；null 表示自动挑
     */
    default Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions, Predicate<BlockPos> chainable,
            Consumer<BlockPos> givenUp, DigTool digTool) {
        List<Action> steps = new ArrayList<>();
        for (BlockPos cell : cells) {
            Optional<Action> one = collect(cell, permissions);
            if (one.isEmpty()) return Optional.empty();
            steps.add(one.get());
        }
        return steps.isEmpty() ? Optional.empty()
                : Optional.of(new StepwiseActions("一格一格收 " + cells.size() + " 格", steps.toArray(Action[]::new)));
    }

    /**
     * 同上，并带上这次任务的记账口：整批挖完把这一趟的工具现场（用了什么、磨了多少、比徒手省了多少）
     * 记成结果里的一条，让下令人看得到钱花在哪。没有记账口时只做不记。
     *
     * @param records 这次任务的记账口；null 表示不记
     */
    default Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions, Predicate<BlockPos> chainable,
            Consumer<BlockPos> givenUp, DigTool digTool, TaskRecords records) {
        // 只实现旧签名的接缝照旧成批收：丢下点名与记账口，不把一批拆成一格一格。
        return digTool == null
                ? collectBatch(cells, permissions, chainable, givenUp)
                : collectBatch(cells, permissions, chainable, givenUp, digTool);
    }

    /**
     * 收概率掉落的方块（砾石出燧石、树叶出树苗、草出种子）：和成批收一样一格一格走到跟前挖掉、整批挖完再捡，
     * 跳过的格交给 givenUp；挖的时候不拿剪刀和带精准采集的工具（拿了方块只掉它自己），有时运的优先，不连锁。
     * 没有专门实现的接缝照常成批收。
     */
    default Optional<Action> collectForChanceDrops(List<BlockPos> cells, Permissions permissions,
            Consumer<BlockPos> givenUp) {
        return collectBatch(cells, permissions, null, givenUp);
    }

    /**
     * 收概率掉落的方块，并带上目标里点名的工具：点名了就用那一件，不再另按概率掉落的挑法换工具。
     * 没有专门实现的接缝照常成批收。
     */
    default Optional<Action> collectForChanceDrops(List<BlockPos> cells, Permissions permissions,
            Consumer<BlockPos> givenUp, DigTool digTool) {
        return digTool == null
                ? collectBatch(cells, permissions, null, givenUp)
                : collectBatch(cells, permissions, null, givenUp, digTool);
    }

    /** 同上，并带上这次任务的记账口：挖完把工具现场记成结果里的一条。 */
    default Optional<Action> collectForChanceDrops(List<BlockPos> cells, Permissions permissions,
            Consumer<BlockPos> givenUp, DigTool digTool, TaskRecords records) {
        return collectForChanceDrops(cells, permissions, givenUp, digTool);
    }
}
