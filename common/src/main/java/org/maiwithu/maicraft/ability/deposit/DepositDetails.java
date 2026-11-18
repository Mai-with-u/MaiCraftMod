// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 存东西能力的结果细节：存进了哪些容器、各自存了什么，以及给了 {@code source} 时从哪些来源容器搬出了什么。
 *
 * @param containers 每个存放处一段：名字与存进了什么（按物品合并）
 * @param takenFrom  给了 {@code source} 时每个来源容器一段：名字与实际搬出了什么；未指定来源或未搬出时为 null
 */
record DepositDetails(List<String> containers, List<String> takenFrom) implements ResultDetails {

    DepositDetails(List<String> containers) {
        this(containers, null);
    }

    DepositDetails {
        containers = List.copyOf(containers);
        takenFrom = takenFrom == null || takenFrom.isEmpty() ? null : List.copyOf(takenFrom);
    }
}
