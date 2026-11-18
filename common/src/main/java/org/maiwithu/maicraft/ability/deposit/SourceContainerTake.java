// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.sources.ContainerBatchTakes;
import org.maiwithu.maicraft.behavior.acquire.sources.ContainerTakeOrder;
import org.maiwithu.maicraft.behavior.acquire.sources.ReportsTakeSettlement;
import org.maiwithu.maicraft.behavior.acquire.sources.TakeSettlement;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 从一只来源容器取出一轮要转存的物品：取哪几样（整箱或点名的几样）、总共几件、取到箱空为止
 * 写成一张容器取货单，交给行为层的开箱搬一批货去做——走过去点开、逐笔搬、关箱前刷世界记忆、
 * 通报动了别人放的箱子、关上，收场算取货结算。这里只做组合：把结算翻译给存东西任务
 * （实际搬进了什么各几件、这只箱子还剩没搬完的没有），点不开的原因原样交回。
 *
 * <p>给 {@code deposit} 带 {@code source} 时做中转取货用：只认本轮确认进入角色背包的物品与件数，
 * 方便下一步原数存进 ME 网络或目标容器，绝不动角色原本随身带的工具、食物与建材。
 */
final class SourceContainerTake implements Action {

    private final KnownContainer container;
    /** 行为层的整批开箱搬货动作：点开、逐笔搬、关箱与结算都是它。 */
    private final Action take;
    private final TaskRecords records;
    private boolean unconfirmedFlushed;

    SourceContainerTake(KnownContainer container, List<String> itemIds, int maxCount, ContainerBatchTakes takes,
            Permissions permissions, TaskRecords records) {
        this.container = Objects.requireNonNull(container, "container");
        this.records = Objects.requireNonNull(records, "records");
        // source 的物品写法：# 标签、物品 ID 或带种类的写法，统一交给想要的物品认。
        List<WantedItem> wanted = itemIds.stream()
                .map(id -> id.startsWith("#") ? WantedItem.ofTag(id.substring(1)) : WantedItem.ofItem(id))
                .toList();
        this.take = takes.take(container, new ContainerTakeOrder(wanted, maxCount), permissions);
    }

    /** 点不开这只来源容器时的原因；点开过、还没试完或到了总期限时为空。 */
    Optional<Problem> openFailure() {
        return take instanceof ReportsTakeSettlement reported ? reported.openFailure() : Optional.empty();
    }

    /** 开箱第一眼容器里有没有匹配的物品：没有时说明这只箱子不用搬。 */
    boolean hadMatchingItems() {
        return settlement().matchedAtOpen();
    }

    /** 关箱时这只来源容器里是不是还剩着没搬完的匹配物品（背包满了或到了本轮限额）。 */
    boolean hasMoreMatchingItems() {
        return settlement().remainedInContainer();
    }

    /** 本轮确认从这只来源容器拿进角色背包的各物品件数，按箱子格子先后排序。 */
    Map<String, Integer> pulled() {
        return settlement().taken();
    }

    // 结算要到动作收场才算得出；收场前按没有算，任务只在动作收场后读它。
    private TakeSettlement settlement() {
        return take instanceof ReportsTakeSettlement reported ? reported.settlement() : TakeSettlement.none();
    }

    @Override
    public ActionStatus tick(TickContext context) {
        ActionStatus status = take.tick(context);
        // 点不开或没走到：原因记一笔试过办法，任务决定换下一只来源容器还是收场。
        if (status instanceof ActionStatus.Failed) {
            Optional<Problem> failedToOpen = openFailure();
            if (failedToOpen.isPresent()) {
                records.attempt("打开来源" + container.name(), failedToOpen.get().message());
            }
        }
        if (!(status instanceof ActionStatus.Running)) {
            flushUnconfirmed();
        }
        return status;
    }

    // 点出去没能等到结果的搬运一笔一句交给任务记账口，不与确认拿到的混在一起。
    private void flushUnconfirmed() {
        if (unconfirmedFlushed || !(take instanceof ReportsUnconfirmed reported)) {
            return;
        }
        unconfirmedFlushed = true;
        for (String fact : reported.unconfirmedFacts()) {
            records.unconfirmed(new Change(Change.Kind.OTHER, "搬运", 1, fact));
        }
    }

    @Override
    public Interruptibility interruptibility() {
        return take.interruptibility();
    }

    @Override
    public void pause() {
        take.pause();
    }

    @Override
    public void close() {
        take.close();
        flushUnconfirmed();
    }

    @Override
    public String describe() {
        return "从来源" + container.name() + "取货";
    }
}
