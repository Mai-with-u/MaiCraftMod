// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import org.maiwithu.maicraft.behavior.acquire.mining.ChainMiningLedger;
import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 采集的结果细节：本次采集实际挖了方块时，在 {@code details.chain_mining} 给出连锁统计；
 * 只是捡起地上掉落物或打展示框时未挖方块，不挂这份细节。
 */
record GatherDetails(ChainMiningLedger.Summary chainMining) implements ResultDetails {}
