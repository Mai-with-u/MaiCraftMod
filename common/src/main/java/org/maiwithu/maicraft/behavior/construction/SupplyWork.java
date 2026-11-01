// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 备料：把缺的几种材料写成一张单子交给拿到物品的引擎，一次走完；同一种箱子里的几样一起拿。
 * 弄不到的记一笔，接着施工——有多少建多少，材料用完时施工停在材料尽头。
 */
final class SupplyWork extends ConstructionWork {

    private final Permissions permissions;
    private final String purpose;
    /** 这次缺的几种材料，各要几件。 */
    private final List<ItemRequest> missing;
    /** 动身去备前每样身上有几件：备完按身上的差值算这次自己补进来几件。 */
    private final Map<String, Integer> carriedBefore = new LinkedHashMap<>();
    private boolean started;

    SupplyWork(ConstructionServices services, ConstructionSite site, TaskRecords records, Permissions permissions,
               String purpose, Map<String, Integer> missing) {
        super(services, site, records);
        this.permissions = permissions;
        this.purpose = purpose;
        List<ItemRequest> requests = new ArrayList<>();
        missing.forEach((itemId, count) ->
                requests.add(new ItemRequest(WantedItem.ofItem(itemId), count, purpose)));
        this.missing = List.copyOf(requests);
    }

    @Override protected ActionStatus advance(TickContext context) {
        if (missing.isEmpty() || services.needs() == null) return ActionStatus.done();
        if (started) {
            // 备料收场先把补料记下：身上多出来的几件就是这次自己补进来的，记进工地账，收场结果里写明来源。
            settleSupplement();
            records.attempt("备料 " + summary(), "拿到了");
            return ActionStatus.done();
        }
        started = true;
        missing.forEach(request -> carriedBefore.put(request.wanted().itemId(), services.site().carried(request.wanted().itemId())));
        begin(services.needs().actionForSeveral(missing, permissions, records), "去备 " + summary());
        return ActionStatus.progressed();
    }

    @Override protected ActionStatus failed(TickContext context, ActionStatus.Failed failure) {
        // 有几样弄不到：已到手的几件照实记下来，接着施工；缺的格到施工时如实停在材料尽头。
        settleSupplement();
        records.attempt("备料 " + summary(), failure.problem().message());
        return ActionStatus.done();
    }

    // 每样按身上的库存差值算这次补进来几件；合成来的也是补的，来源都算"施工中自己去拿"。
    private void settleSupplement() {
        missing.forEach(request -> {
            String itemId = request.wanted().itemId();
            site.supplemented(itemId, services.site().carried(itemId) - carriedBefore.getOrDefault(itemId, 0));
        });
    }

    private String summary() {
        return missing.stream().map(request -> request.wanted().itemId() + " ×" + request.count())
                .collect(Collectors.joining("、"));
    }
}
