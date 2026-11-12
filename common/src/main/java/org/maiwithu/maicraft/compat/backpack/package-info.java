// SPDX-License-Identifier: GPL-3.0-only
/**
 * 精妙背包（SophisticatedBackpacks）的联动：角色从随身背包取东西（{@code ItemSource}）、
 * 主背包满时把杂物放进随身背包腾格子（{@code CarriedBackpack}），以及在自身观察里呈现各随身栏位的背包与新鲜存货。
 *
 * <p>覆盖 Curios 与 Accessories 饰品栏、胸甲格、副手与主背包全部随身栏位，取货与存货共用同一份开包存货账本；
 * 读写接缝只用 Minecraft 与 Java 类型描述，直接调用模组类的读写端在 NeoForge 模块里。
 */
package org.maiwithu.maicraft.compat.backpack;
