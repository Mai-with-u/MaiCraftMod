// SPDX-License-Identifier: GPL-3.0-only
/**
 * 各物品来源：实现 {@code spi} 里的 {@code ItemSource}——箱子、合成、烧炼、采掘、收获、
 * 狩猎、交易、收回自己放下的东西、拿桶装水、往水里扔料，各自问价与动手。开箱取货的取货计划与点击
 * 实现也在这里，容器类来源共用。主背包与副手里已有的不算来源，由取物引擎直接清点。
 */
package org.maiwithu.maicraft.behavior.acquire.sources;
