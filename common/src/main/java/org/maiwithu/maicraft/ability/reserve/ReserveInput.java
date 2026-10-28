// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.reserve;

import java.util.List;
import java.util.stream.Collectors;

import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 一次预留操作的任务输入：写下几样、撤掉几样，或者全部撤掉。不可变；参数在能力的决定阶段已经核对过。
 *
 * @param operation 写下、撤掉几样还是全部撤掉
 * @param entries   写下的几条；别的操作为空
 * @param items     要撤掉的物品或标签；别的操作为空
 */
record ReserveInput(Operation operation, List<ReservedItems.Reservation> entries, List<String> items)
        implements TaskInput {

    /** 写下、撤掉几样、全部撤掉。 */
    enum Operation { SET, CANCEL, CANCEL_ALL }

    ReserveInput {
        if (operation == null) throw new IllegalArgumentException("要写清是写下还是撤掉");
        entries = List.copyOf(entries);
        items = List.copyOf(items);
        if (operation == Operation.SET && entries.isEmpty()) throw new IllegalArgumentException("写下预留至少要有一样");
        if (operation == Operation.CANCEL && items.isEmpty()) throw new IllegalArgumentException("撤掉预留要写撤哪几样");
    }

    static ReserveInput set(List<ReservedItems.Reservation> entries) {
        return new ReserveInput(Operation.SET, entries, List.of());
    }

    static ReserveInput cancel(List<String> items) {
        return new ReserveInput(Operation.CANCEL, List.of(), items);
    }

    static ReserveInput cancelAll() {
        return new ReserveInput(Operation.CANCEL_ALL, List.of(), List.of());
    }

    @Override public String describe() {
        return switch (operation) {
            case SET -> "预留 " + entries.stream()
                    .map(entry -> entry.item() + (entry.atLeast() == null ? " 全都留着" : " 留 " + entry.atLeast()))
                    .collect(Collectors.joining("、"));
            case CANCEL -> "不再预留 " + String.join("、", items);
            case CANCEL_ALL -> "撤掉全部预留";
        };
    }
}
