// SPDX-License-Identifier: GPL-3.0-only
/**
 * 丢东西与防捡回：原地扔出、按批量丢、丢完走开几步离开拾取范围、登记自己刚丢的落点绕开，
 * 以及顺路捡起地上的掉落物。可以依赖同级的 containers（腾格子时认容器）与 hands（丢前换到
 * 主手），不依赖包根的腾挪。
 */
package org.maiwithu.maicraft.behavior.inventory.drops;
