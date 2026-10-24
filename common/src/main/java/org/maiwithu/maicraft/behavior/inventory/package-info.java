// SPDX-License-Identifier: GPL-3.0-only
/**
 * 背包空间：按价值一级级腾地方——合并、放进随身背包、存进附近的箱子、丢最没用的，要动
 * 贵重物品才停下来问。丢与捡、容器存取、换手与快捷栏在各自的子包（drops、containers、
 * hands）；这里留着腾挪本身和它依赖的物品价值排序。
 */
package org.maiwithu.maicraft.behavior.inventory;
