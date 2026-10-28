// SPDX-License-Identifier: GPL-3.0-only
/**
 * 背包空间：按价值一级级腾地方——合并、放进随身背包、存进附近的箱子或网络，只做可逆的搬动，
 * 不替玩家往地上扔东西；腾不出时如实失败并把能处置的出路列进建议。捡拾、容器存取、换手与
 * 快捷栏在各自的子包（drops、containers、hands）；这里留着腾挪本身和它依赖的"哪些能动"：物品价值排序，
 * 以及角色自己做主花东西时全仓共用的那道关——要留的东西（调用方的预留与它的存盘、正要用的东西合起来）。
 */
package org.maiwithu.maicraft.behavior.inventory;
