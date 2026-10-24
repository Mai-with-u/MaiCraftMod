// SPDX-License-Identifier: GPL-3.0-only
/**
 * 拿东西的引擎与要货单：一次拿东西的请求、逐来源问价与挑选、边做边重新清点，以及没拿到的
 * 经过（缺多少、为什么）与原料取舍的纯判断。引擎依赖的只读接缝（物品注册表、标签、角色位置）也在这里。
 */
package org.maiwithu.maicraft.behavior.acquire.engine;
