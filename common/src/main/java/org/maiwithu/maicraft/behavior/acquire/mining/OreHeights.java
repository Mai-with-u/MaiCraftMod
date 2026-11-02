// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 主世界与下界的矿按高度怎么分布（原版 1.21.1 的生成规则）：长程挖矿往下挖到哪一层、在哪一层横着挖，按它定。
 * 和资料 game_mechanics/ore-heights、随包技能 survival-mining 写的是同一份数。表里没有的矿（多是模组矿）不知道在哪层，
 * 不下探，照旧只挖看得见的。
 */
public final class OreHeights {

    /** 主世界。 */
    public static final String OVERWORLD = "minecraft:overworld";
    /** 下界。 */
    public static final String NETHER = "minecraft:the_nether";

    /**
     * 一种矿的生成范围。
     *
     * @param name      给人看的名字，例如"铁矿"
     * @param dimension 在哪个维度生成
     * @param minY      最低在哪层生成
     * @param peakY     往下挖就挖到这一层：哪层最多就是哪层；各层一样多的矿记范围顶，在它上面才往下挖
     * @param maxY      最高在哪层生成
     * @param even      各层一样多（下界金矿、下界石英）：在范围里哪层都原地横挖，不说哪层最多
     */
    public record Band(String name, String dimension, int minY, int peakY, int maxY, boolean even) {

        /** 有一层最多的矿：在那层之上往下挖到那层。 */
        static Band peaked(String name, String dimension, int minY, int peakY, int maxY) {
            return new Band(name, dimension, minY, peakY, maxY, false);
        }

        /** 各层一样多的矿：在范围以上才往下挖到范围顶，范围里原地横挖。 */
        static Band evenly(String name, String dimension, int minY, int maxY) {
            return new Band(name, dimension, minY, maxY, maxY, true);
        }

        /** 说给 LLM 听的分布，例如"铁矿在 Y -64 到 320 生成，Y=16 最多"。 */
        public String describe() {
            return name + "在 Y " + minY + " 到 " + maxY + " 生成，" + (even ? "各层一样多" : "Y=" + peakY + " 最多");
        }
    }

    private static final Band COAL = Band.peaked("煤矿", OVERWORLD, 0, 95, 320);
    private static final Band COPPER = Band.peaked("铜矿", OVERWORLD, -16, 48, 112);
    private static final Band IRON = Band.peaked("铁矿", OVERWORLD, -64, 16, 320);
    private static final Band GOLD = Band.peaked("金矿", OVERWORLD, -64, -16, 32);
    private static final Band REDSTONE = Band.peaked("红石矿", OVERWORLD, -64, -58, 15);
    private static final Band LAPIS = Band.peaked("青金石矿", OVERWORLD, -64, 0, 64);
    private static final Band DIAMOND = Band.peaked("钻石矿", OVERWORLD, -64, -59, 16);
    // 远古残骸：大团在 Y 8–24 三角分布、Y16 最多，小团在 Y 8–119 均匀；站在 Y15 横挖，通道占 15、16 两层，正是最多的两层。
    // 它一格都不贴着空气生成，洞壁上看不见，只能挖通道让它露出来。
    private static final Band ANCIENT_DEBRIS = Band.peaked("远古残骸", NETHER, 8, 15, 119);
    // 下界金矿、下界石英：Y 10–117 均匀生成，哪层都一样，不用往下挖。
    private static final Band NETHER_GOLD = Band.evenly("下界金矿", NETHER, 10, 117);
    private static final Band NETHER_QUARTZ = Band.evenly("下界石英矿", NETHER, 10, 117);

    /** 矿石方块（含深层版本）各在哪个范围。 */
    private static final Map<String, Band> BY_ORE = Map.ofEntries(
            Map.entry("minecraft:coal_ore", COAL), Map.entry("minecraft:deepslate_coal_ore", COAL),
            Map.entry("minecraft:copper_ore", COPPER), Map.entry("minecraft:deepslate_copper_ore", COPPER),
            Map.entry("minecraft:iron_ore", IRON), Map.entry("minecraft:deepslate_iron_ore", IRON),
            Map.entry("minecraft:gold_ore", GOLD), Map.entry("minecraft:deepslate_gold_ore", GOLD),
            Map.entry("minecraft:redstone_ore", REDSTONE), Map.entry("minecraft:deepslate_redstone_ore", REDSTONE),
            Map.entry("minecraft:lapis_ore", LAPIS), Map.entry("minecraft:deepslate_lapis_ore", LAPIS),
            Map.entry("minecraft:diamond_ore", DIAMOND), Map.entry("minecraft:deepslate_diamond_ore", DIAMOND),
            Map.entry("minecraft:ancient_debris", ANCIENT_DEBRIS),
            Map.entry("minecraft:nether_gold_ore", NETHER_GOLD),
            Map.entry("minecraft:nether_quartz_ore", NETHER_QUARTZ));

    private OreHeights() {}

    /** 这种矿石方块的生成范围；不在表里为空。 */
    public static Optional<Band> of(String oreBlockId) {
        return Optional.ofNullable(BY_ORE.get(oreBlockId));
    }

    /**
     * 站在 feetY 要这种矿时在哪一层横着挖：在最多那层（各层一样多的是范围顶）上面就往下挖到那层；
     * 已经在那层或以下、还在生成范围里就在原地横着挖；在生成范围以下（要往上爬才到）为空，不挖。
     */
    public static OptionalInt workingY(Band band, int feetY) {
        if (feetY > band.peakY()) return OptionalInt.of(band.peakY());
        if (feetY >= band.minY()) return OptionalInt.of(feetY);
        return OptionalInt.empty();
    }
}
