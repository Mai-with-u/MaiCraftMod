// SPDX-License-Identifier: GPL-3.0-only
/**
 * 应用能源2（AE2）的压印器：读压印器的四个工作格、算一种配方怎么投料、投料前清理冲突的格、
 * 收产物并顺手收回自己放进去的模板，以及拿东西从压印器工作格单取的来源与界面布局证明。
 * 压印器是 AE2 里不接 ME 网的单机，四个工作格全部随方块实体同步给客户端，站在外面就读得到。
 *
 * <p>投料计划经机器类型的投料接缝进来，在 {@link org.maiwithu.maicraft.compat.ae2.inscriber.Ae2InscriberPlates}
 * 纯函数算；单取来源经拿东西的容器途径交给登记表。读写端在 NeoForge 模块里，测试里用替身。
 */
package org.maiwithu.maicraft.compat.ae2.inscriber;
