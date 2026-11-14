// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.behavior.acquire.mining.ChainMiningLedger;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 汇总当前世界真正就绪可用的模组能力（{@code observe(what=self)} 的 {@code mod_features}）：
 * 联动模组看登记表此刻还开着的条目，连锁破坏还要确认当前服务端接收连锁包，Lootr 看战利品容器标签。
 */
public final class ModFeatures {

    private static final Map<String, String> FEATURE_BY_MOD_ID = Map.of(
            "ae2", "ae2",
            "create", "create",
            "ftbquests", "ftb_quests",
            "mekanism", "mekanism",
            "sable", "sable",
            "sophisticatedbackpacks", "sophisticated_backpacks",
            "touhou_little_maid", "touhou_little_maid");

    /** 进世界登记全部能力时顺带交回给世界现场复用的实例。 */
    record WorldAbilities(List<CarriedBackpack> carriedBackpacks,
            List<BreakAccelerator> breakAccelerators,
            ChainMiningLedger chainMining) {}

    private ModFeatures() {}

    /** 按字母顺序返回当前世界就绪可用的模组能力代号列表；一个都没有时返回空列表。 */
    public static List<String> activeInWorld(CompatRegistry compat, List<BreakAccelerator> breakAccelerators,
            boolean hasLootrContainers) {
        List<String> features = new ArrayList<>();
        if (compat != null) {
            for (String modId : compat.installedModIds()) {
                String feature = FEATURE_BY_MOD_ID.get(modId);
                if (feature != null) features.add(feature);
            }
        }
        if (breakAccelerators != null && breakAccelerators.stream().anyMatch(BreakAccelerator::available)) {
            features.add("chain_mining");
        }
        if (hasLootrContainers) {
            features.add("lootr");
        }
        return features.stream().distinct().sorted().toList();
    }
}
