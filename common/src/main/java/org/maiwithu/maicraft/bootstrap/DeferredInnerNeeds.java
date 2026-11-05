// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 拿到物品引擎的内需入口：来源备料缺原料缺工具时回到引擎，但引擎要等来源清单
 * 建好才建得出来。这里先接住内需、引擎建好后接上，来源用它时不觉得有先后。
 * 内需的每一问都原样转给引擎，不落回接口的默认实现：默认实现会丢掉"这一层是备工具""顺手一起拿的几样"这类信息。
 */
final class DeferredInnerNeeds implements ItemNeeds {

    private ItemNeeds engine;

    /** 引擎建好后接上；这之后内需才真的往下问。 */
    void attach(ItemNeeds engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override public Action actionFor(ItemRequest request, Permissions permissions) {
        if (engine == null) {
            throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
        }
        return engine.actionFor(request, permissions);
    }

    @Override public Action actionFor(ItemRequest request, Permissions permissions, TaskRecords records) {
        if (engine == null) {
            throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
        }
        return engine.actionFor(request, permissions, records);
    }

    // 为干活备工具（成批砍树前做斧头）要原样转给引擎，引擎才记得这一层是备工具：不转的话退回普通内需，
    // 为斧头做木板时身上正攒的原木被当成外层正要的东西，不拿来做木板，转去找竹子。
    @Override public Action actionForTool(ItemRequest request, Permissions permissions, TaskRecords records) {
        if (engine == null) {
            throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
        }
        return engine.actionForTool(request, permissions, records);
    }

    // 备料时把同一张单子里的另外几样带上：原样转给引擎，开一次箱能一起拿几样；落回默认实现会把它们丢掉。
    @Override public Action actionFor(ItemRequest request, Permissions permissions, TaskRecords records,
            List<ItemRequest> alsoWanted) {
        return attached().actionFor(request, permissions, records, alsoWanted);
    }

    // 一张要货单交给引擎逐样去弄：引擎怎么串由引擎定。
    @Override public Action actionForSeveral(List<ItemRequest> requests, Permissions permissions, TaskRecords records) {
        return attached().actionForSeveral(requests, permissions, records);
    }

    // 只问价不拿的估算（夜里先备武器还是先备床比哪个费事）：引擎建好后按引擎此刻会走的路估，还没建好时估不了。
    @Override public GoalEstimate estimate(ItemRequest request, Permissions permissions) {
        return engine == null ? ItemNeeds.super.estimate(request, permissions) : engine.estimate(request, permissions);
    }

    private ItemNeeds attached() {
        if (engine == null) {
            throw new IllegalStateException("拿到物品的引擎还没建好，内需没有可以回的地方");
        }
        return engine;
    }

    // 引擎还没建好时谁也没在拿东西，没有已知拿不到的。
    @Override public boolean knownUnobtainable(WantedItem wanted) {
        return engine != null && engine.knownUnobtainable(wanted);
    }

    // 缺料一样多的几种做法按缺的原料远近排：引擎还没建好时问不出远近，排法退回原来的先后。
    @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
        return engine == null ? Optional.empty() : engine.nearestDistanceBlocks(request, permissions);
    }

    // 几种做法分不出远近时先看一眼 AE 这类"开了才知道"的来源；引擎还没建好时没得看。
    @Override public Optional<Action> lookAround(ItemRequest request, Permissions permissions) {
        return engine == null ? Optional.empty() : engine.lookAround(request, permissions);
    }

    // 挑可以互换的原料时问最省的一条路；引擎还没建好时问不出，按配方里的先后。
    @Override public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions,
            boolean includeMaking) {
        return engine == null ? Optional.empty() : engine.cheapestCost(request, permissions, includeMaking);
    }

    // 分辨"问不出价"是还没看完还是真没有来路；引擎还没建好时谁也没在扫现场，不算还没看完。
    @Override public boolean priceStillLooking(ItemRequest request, Permissions permissions, boolean includeMaking) {
        return engine != null && engine.priceStillLooking(request, permissions, includeMaking);
    }
}
