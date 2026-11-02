// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.Optional;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 进过的洞口：钻洞进洞时记进世界记忆的那几处。回露天时离得近就寻路走回洞口，
 * 远了先照平常的办法出去（就近见天、挖楼梯），都不行再去洞口。生产实现读世界记忆，测试用替身。
 */
public interface CaveEntrances {

    /** 什么都不记得：没接上世界记忆时用，回露天照旧。 */
    CaveEntrances NONE = (from, within) -> Optional.empty();

    /** 离 from 直线不超过 within 格、进过的洞口里最近的那一个（同一维度）；没有为空。 */
    Optional<WorldPosition> nearestEntered(WorldPosition from, double within);
}
