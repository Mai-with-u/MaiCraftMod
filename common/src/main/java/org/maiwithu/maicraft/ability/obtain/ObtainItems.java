// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次拿东西的任务输入：要哪几样、各再多拿几件、走哪几条途径、愿意走多远、在多大范围里找，
 * 以及每样东西自带的材料倾向与掉落线索。参数在能力下决定时就换成了带类型的取值，任务不再碰原始 JSON。
 *
 * <p>一次只要一样时清单里就一项；同一样写两次的在这里合并成一项、件数相加。
 *
 * @param requests     这一趟要的几样，每样是自己的物品（或标签）、件数、用途、材料倾向与掉落线索
 * @param scope        这次拿东西的限定：途径、距离上限、搜索半径
 * @param permissions  这次任务的许可；来源备料与腾背包受同一份许可管
 * @param purpose      用途标签，写进结果与腾背包的备注，例如"按需要拿取"
 * @param describeText 给日志与面板的一句话，例如"再拿 8 个 minecraft:torch"
 */
record ObtainItems(List<ItemRequest> requests, ItemAcquisition.Scope scope, Permissions permissions,
        String purpose, String describeText) implements TaskInput {

    ObtainItems {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("拿东西必须带要拿的东西");
        requests = merge(requests);
        if (scope == null) throw new IllegalArgumentException("拿东西必须带限定（不限也是一种限定）");
        if (permissions == null) throw new IllegalArgumentException("拿东西必须带这次的许可");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("拿东西必须带用途标签");
        if (describeText == null || describeText.isBlank()) throw new IllegalArgumentException("任务输入必须有一句话描述");
    }

    /** 只要一样东西时的任务输入。 */
    ObtainItems(WantedItem wanted, int count, ItemAcquisition.Scope scope, Permissions permissions,
            String purpose, String describeText) {
        this(List.of(new ItemRequest(wanted, count, purpose)), scope, permissions, purpose, describeText);
    }

    // 同一样东西写两次按一项算、件数相加；先写的排在前面。
    private static List<ItemRequest> merge(List<ItemRequest> requests) {
        Map<String, ItemRequest> merged = new LinkedHashMap<>();
        for (ItemRequest request : requests) {
            if (request.count() < 1) throw new IllegalArgumentException("要拿的数量至少为 1：" + request.count());
            merged.merge(request.wanted().specifier(), request, (first, again) -> first.just(first.count() + again.count()));
        }
        return List.copyOf(merged.values());
    }

    @Override public String describe() {
        return describeText;
    }
}
