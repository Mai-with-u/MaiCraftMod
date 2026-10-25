// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.List;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 问询来源时告诉它的一次现场：角色现在在哪、这次任务有哪些许可、愿意走多远。
 * 来源按位置估距离、按许可决定哪些来源根本不参与；愿意走的距离只有要"出去找"的来源用
 * （附近没有时走多远再找一片），报价太远的路仍然由引擎按它挡下。
 *
 * <p>可选字段按真实出现过的组合用静态工厂表达：问价用不带记账口的两个工厂，
 * 要往任务结果里记东西、要顺路拿别样时才带全参。新加可选字段时只改全参构造与
 * 用到它的工厂，其余调用点不动。
 *
 * @param characterAt  角色此刻所在的位置
 * @param permissions  这次任务的许可
 * @param radiusBlocks 这次任务允许搜索的范围（格）；null 表示用来源自己的默认半径。
 *                     半径给了就不越界，这是"给了就冻结在范围内"的规矩
 * @param maxDistanceBlocks 愿意为此走多远（格）；null 表示不限。只有要走出半径再找一片的来源用它
 * @param records      发起任务的记账口：来源动作在世界上确认做过的事（自己做出了什么）直接记进那次任务的
 *                     结果。null 表示这次问价或不接记账口的执行，做了什么都不记。交给来源前已滤掉
 *                     最外层要的东西进背包的那一笔——那一件由任务自己结算，来源再记就重了
 * @param alsoWanted   这一趟还要的另外几样（同一次要货里还缺的）：开一次箱能一起拿的来源用它，
 *                     每样都写着这一刻还缺几件；已经拿够的、别样任务要的不在这里面
 */
public record SourceContext(WorldPosition characterAt, Permissions permissions, Integer radiusBlocks,
        Double maxDistanceBlocks, TaskRecords records, List<ItemRequest> alsoWanted) {

    public SourceContext {
        if (characterAt == null) throw new IllegalArgumentException("问询来源必须带着角色的位置");
        if (permissions == null) throw new IllegalArgumentException("问询来源必须带着这次的许可");
        alsoWanted = alsoWanted == null ? List.of() : List.copyOf(alsoWanted);
    }

    /** 不限搜索半径与距离的问询：来源按自己的默认半径找。 */
    public static SourceContext unscoped(WorldPosition characterAt, Permissions permissions) {
        return new SourceContext(characterAt, permissions, null, null, null, List.of());
    }

    /** 限定了这次允许搜的范围和愿意走的距离的问询，还不接记账口。 */
    public static SourceContext scoped(WorldPosition characterAt, Permissions permissions,
            Integer radiusBlocks, Double maxDistanceBlocks) {
        return new SourceContext(characterAt, permissions, radiusBlocks, maxDistanceBlocks, null, List.of());
    }

    /** 带记账口的问询：来源动作确认做过的事直接记进那次任务的结果。 */
    public static SourceContext booked(WorldPosition characterAt, Permissions permissions, TaskRecords records) {
        return new SourceContext(characterAt, permissions, null, null, records, List.of());
    }

    /** 顺路还要拿几样的问询：开一次箱能一起拿的来源会把这些一起带出来。 */
    public static SourceContext alsoWanting(WorldPosition characterAt, Permissions permissions,
            List<ItemRequest> alsoWanted) {
        return new SourceContext(characterAt, permissions, null, null, null, alsoWanted);
    }
}
