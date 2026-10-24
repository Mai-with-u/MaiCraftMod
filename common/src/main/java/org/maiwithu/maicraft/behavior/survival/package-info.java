// SPDX-License-Identifier: GPL-3.0-only
/**
 * 生存需求：坠落、溺水、被埋、被攻击、饥饿、天黑、夜晚、太暗……以及对附近威胁的判断。
 * 按需求域分子包：{@code breath} 换气、{@code hunger} 饥饿、{@code night} 夜晚、{@code beds} 选床、
 * {@code fall} 坠落、{@code digout} 刨出、{@code defense} 自卫、{@code recover} 捡回遗物；
 * 包根留各域共用的事实与编排——每刻一次取齐的生存处境、太暗需求（放火把的动作链在照明包）、
 * 贴边退避与捡回遗物共用的动手实现。
 *
 * <p>生存需求的临时任务做完就结束；没做成也绝不把主任务判失败。
 */
package org.maiwithu.maicraft.behavior.survival;
