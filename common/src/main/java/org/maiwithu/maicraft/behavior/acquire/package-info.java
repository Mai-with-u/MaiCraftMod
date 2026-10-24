// SPDX-License-Identifier: GPL-3.0-only
/**
 * 拿到物品：问遍所有物品来源（背包、箱子、合成、烧炼、采集、交易……），选总代价最低的组合，边做边重新清点。
 *
 * <p>引擎与要货单在 {@code engine}，各物品来源在 {@code sources}，采掘在 {@code mining}，
 * 交易在 {@code trade}，工作站制作在 {@code cook}，收获与狩猎在 {@code field}。
 * 物品来源通过 {@code behavior.acquire.spi} 由行为层与联动模组共同提供，启动时明确登记，不用 ServiceLoader。
 */
package org.maiwithu.maicraft.behavior.acquire;
