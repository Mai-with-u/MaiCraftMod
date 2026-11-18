// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.TaskInput;

/**
 * 存东西的任务输入：解析并核对过的参数，不可变。
 *
 * @param target      存进哪个容器，或在哪片地方存；不给就在附近挑
 * @param source      从哪个容器或哪片区域的箱子往外搬出再存；null 表示直接存角色背包里的东西
 * @param itemIds     存哪些（物品 ID 或 # 标签）；不给 source 且不点名时随身要留的东西以外的全存，给了 source 时不点名表示整箱搬空
 * @param count       一共存几件；null 表示全存
 * @param radius      自己挑容器或扫来源区域时的范围（格）
 * @param permissions 这次任务的许可
 * @param network     存进 ME 网络（身上的无线终端或装着的终端），不找容器
 */
record DepositInput(Target target, Target source, List<String> itemIds, Integer count, long radius,
        Permissions permissions, boolean network) implements TaskInput {

    /** 从角色背包存进容器或网络。 */
    DepositInput(Target target, List<String> itemIds, Integer count, long radius,
            Permissions permissions, boolean network) {
        this(target, null, itemIds, count, radius, permissions, network);
    }

    /** 从角色背包存进容器。 */
    DepositInput(Target target, List<String> itemIds, Integer count, long radius, Permissions permissions) {
        this(target, null, itemIds, count, radius, permissions, false);
    }

    DepositInput {
        Objects.requireNonNull(itemIds, "itemIds");
        itemIds = List.copyOf(itemIds);
        if (radius < 1) throw new IllegalArgumentException("范围至少是 1 格：" + radius);
    }

    @Override public String describe() {
        String what = itemIds.isEmpty()
                ? (source == null ? "随身要留的东西以外全部" : "来源容器里的全部物品")
                : String.join("、", itemIds);
        String where = network ? "ME 网络" : target == null ? "附近挑容器" : "指定的目标";
        String times = count == null ? "" : "，共 " + count + " 件";
        String prefix = source == null ? "存进去：" : "从指定来源搬出并存进：";
        return prefix + what + "，" + where + times;
    }
}
