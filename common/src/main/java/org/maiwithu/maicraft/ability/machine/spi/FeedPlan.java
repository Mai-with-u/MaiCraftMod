// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一条配方在这台机器上怎么投，一次解析成的计划值：备料与投料的顺序、每样投几件、机内有什么冲突、
 * 有冲突时先腾空的动作。调用方一次拿到，不再逐格问机器。
 *
 * @param entries  备料与投料的顺序：换料在前，先放的（模板）再排，普通主料最后
 * @param notes    算计划时值得知道的事（机内已有匹配模板不用再放、化学品带不进机器这类）
 * @param conflict 机内此刻占着与这条配方不兼容的遗留物时的一句中文说明；没有冲突为空
 * @param clearing 把冲突物品取回角色背包的动作；没有冲突为空。给了它，machine_run 先执行腾空再按计划备料
 */
public record FeedPlan(List<Entry> entries, List<String> notes, Optional<String> conflict,
        Optional<Action> clearing) {

    public FeedPlan {
        entries = List.copyOf(entries);
        notes = List.copyOf(notes);
        conflict = conflict == null ? Optional.empty() : conflict;
        clearing = clearing == null ? Optional.empty() : clearing;
    }

    /** 顺序里的一样料：投什么、几件。 */
    public record Entry(String item, int count, boolean collectBack) {

        public Entry {
            Objects.requireNonNull(item, "item");
            if (count < 0) throw new IllegalArgumentException("投料件数不能是负数：" + item);
        }
    }
}
