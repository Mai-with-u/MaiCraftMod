// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.List;

/**
 * 酿造表读端：这个世界里能酿的每一步。酿造配方不在配方管理器里，游戏另有一份酿造表，
 * 客户端进世界时按启用的特性自己建（模组登记的酿造配方也在里面）；实现从它拼出每一步，测试用替身摆一张表。
 */
public interface ReadsBrewing {

    /** 能酿的每一步；不在世界里、读不到时给空。 */
    List<BrewStep> steps();
}
