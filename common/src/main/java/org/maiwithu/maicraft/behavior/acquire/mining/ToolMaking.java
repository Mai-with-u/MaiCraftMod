// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.inventory.hands.DigToolChoice;

/**
 * 现做工具的账：身上没有一把对路的工具时，做一把划不划算、做哪一种、身上的料够不够。纯判断。
 *
 * <p>算的是时间账，不是挖多少格才值：每格省的刻数 × 这一趟预计挖的格数 > 做一把的耗时，就做，哪怕只挖一两格。
 * 石头徒手一格 150 刻、木镐 23 刻，一格就回本；泥土每格只省几刻，十几格才回本。做哪种按最省来：木的先于石的。
 *
 * <p>料只算身上过得了"要留的东西"那道关的（预留的、正要用的不算），不为做一把工具出去砍树挖石。
 * 一把工具三件头料（木板或石质工具材料）加两根木棍；木棍一次两块木板出四根；没带工作台再要四块木板做一个
 * （做好放下、用完收回）。一根原木能出四块木板。这几样是原版配方的件数（C 玩家常识常数），不实测。
 */
public final class ToolMaking {

    /** 做一把工具的耗时：做合成台、放下、合成、收回，约百余刻。类别：玩家常识常数，不实测。 */
    public static final int MAKE_TICKS = 120;
    private static final int HEAD = 3;
    private static final int STICKS = 2;
    private static final int PLANKS_PER_STICK_CRAFT = 2;
    private static final int PLANKS_PER_TABLE = 4;
    private static final int PLANKS_PER_LOG = 4;

    private ToolMaking() {}

    /** 头料是什么：木板还是石质工具材料（圆石、深板岩圆石、黑石）。 */
    public enum Head { PLANKS, STONE }

    /**
     * 一种能现做的工具。
     *
     * @param itemId 物品 ID，例如 minecraft:wooden_pickaxe
     * @param head   头料
     * @param speed  它挖这一格的速度倍数（原版问出来的）
     * @param drops  拿它挖这一格掉不掉东西
     */
    public record Option(String itemId, Head head, float speed, boolean drops) {}

    /**
     * 身上能拿来做工具的料，都是过了要留的东西那道关之后的件数；另记被留着的有多少，挡住时说得清。
     *
     * @param planks        不留的木板
     * @param logs          不留的原木
     * @param sticks        不留的木棍
     * @param stone         不留的石质工具材料
     * @param table         身上带着工作台
     * @param keptPlanks    被留着的木板与原木（折成木板）
     * @param keptStone     被留着的石质工具材料
     */
    public record Stock(int planks, int logs, int sticks, int stone, boolean table, int keptPlanks, int keptStone) {}

    /** 结论：做哪一把、划不来、或划得来但料不够（为什么）。 */
    public sealed interface Decision {
        /** 做这一把：每格省多少刻、这一趟省多少刻。 */
        record Make(Option option, int savedPerBlock, long savedTotal) implements Decision {}

        /** 划不来：这一趟最多省这么多刻，不到做一把的耗时。 */
        record NotWorth(long bestSaving) implements Decision {}

        /** 划得来但料不够：最省的那一把是什么、缺的是什么。 */
        record NoMaterial(Option option, String why) implements Decision {}
    }

    /**
     * 算一笔账。
     *
     * @param block          要挖的这一格
     * @param handTicks      身上没有对路工具时挖一格要多少刻
     * @param expectedBlocks 这一趟预计还要挖几格（估不出给 1：挖一格就回本兜底）
     * @param options        能现做的几种，最省的排前（木的先于石的）
     */
    public static Decision decide(DigToolChoice.Block block, int handTicks, int expectedBlocks, List<Option> options,
            Stock stock) {
        long best = 0;
        Option firstShort = null;
        String firstWhy = null;
        for (Option option : options) {
            int saved = handTicks - DigToolChoice.ticksPerBlock(block, option.speed(), option.drops()
                    || !block.needsCorrectTool());
            long total = (long) saved * Math.max(1, expectedBlocks);
            best = Math.max(best, total);
            if (total <= MAKE_TICKS) continue;
            Optional<String> missing = missing(option, stock);
            if (missing.isEmpty()) return new Decision.Make(option, saved, total);
            if (firstShort == null) {
                firstShort = option;
                firstWhy = missing.get();
            }
        }
        return firstShort != null ? new Decision.NoMaterial(firstShort, firstWhy) : new Decision.NotWorth(best);
    }

    // 料够不够做这一把：头料三件、木棍两根（不够就拿两块木板现做四根）、没带工作台再四块木板。
    private static Optional<String> missing(Option option, Stock stock) {
        int planksNeeded = (stock.sticks() >= STICKS ? 0 : PLANKS_PER_STICK_CRAFT)
                + (stock.table() ? 0 : PLANKS_PER_TABLE)
                + (option.head() == Head.PLANKS ? HEAD : 0);
        int planksOnHand = stock.planks() + stock.logs() * PLANKS_PER_LOG;
        if (option.head() == Head.STONE && stock.stone() < HEAD) {
            return Optional.of("石质的料（圆石这类）要 " + HEAD + " 块，身上不留的只有 " + stock.stone() + " 块"
                    + (stock.keptStone() > 0 ? "（留着 " + stock.keptStone() + " 块）" : ""));
        }
        if (planksOnHand < planksNeeded) {
            return Optional.of("木板要 " + planksNeeded + " 块（原木一根折四块），身上不留的只有 " + planksOnHand + " 块"
                    + (stock.keptPlanks() > 0 ? "（留着 " + stock.keptPlanks() + " 块）" : ""));
        }
        return Optional.empty();
    }
}
