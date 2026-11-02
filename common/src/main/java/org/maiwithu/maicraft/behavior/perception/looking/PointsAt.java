// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 结果指着的地方：结果细节说到了世界里的哪几处。LLM 读完这份结果，角色闲着时就顺着看过去。
 *
 * <p>find 指着最近的那个命中，machine_inspect 指着看的那台或那一片机器，read_site 指着那一栋的左中右与顶。
 */
public interface PointsAt {

    /** 指着的几处，按要看的先后；没有时为空。 */
    List<WorldPosition> pointsAt();

    /** 真：从左到右每处看一眼（一片机器、一栋房子）；假：盯着第一处看一会儿（找到的那个东西）。 */
    default boolean sweep() {
        return true;
    }
}
