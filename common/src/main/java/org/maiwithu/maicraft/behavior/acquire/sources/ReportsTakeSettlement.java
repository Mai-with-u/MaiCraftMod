// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 开箱搬货的动作把结算与点不开的原因交回的窄接口：拿到多少以结算为准，调用方不再自己数；
 * 点不开容器时的原因原样交回，由调用方决定换一只还是收场。
 */
public interface ReportsTakeSettlement {

    /** 这次开箱的结算；动作收场后才是最终账，收场前各项按没有算。 */
    TakeSettlement settlement();

    /** 点不开容器时的原因；点开过、或还没试完时为空。 */
    Optional<Problem> openFailure();
}
