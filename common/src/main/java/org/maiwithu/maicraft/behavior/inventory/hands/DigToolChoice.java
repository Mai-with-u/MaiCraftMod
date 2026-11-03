// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.ArrayList;
import java.util.List;

/**
 * 挑工具：对着要挖的一格，把主手、背包里的东西和空手放在同一本账上，挑这一趟最便宜的那样东西。
 * 纯判断，只读给进来的事实。
 *
 * <p>账上算两笔：
 * <ul>
 * <li>磨损成本：挖一格磨掉多少耐久（镐斧锹 1 点、剑 2 点，问工具的方块破坏组件），按剩余耐久折算成这件东西的
 *     几分之几，再乘它的材料价值和附魔附加价值。带经验修补的按折扣算——磨掉的耐久多半会被经验补回来。</li>
 * <li>时间成本：挖一格要多少刻乘以时间单价。拿不到掉落的挖法慢一倍多，同样按它算。</li>
 * </ul>
 * 空手也进这本账：不磨耐久，只花时间。所以挖木板时拿剑不如空手，挖一格石头时钻石镐反而比石镐便宜。
 *
 * <p>先于账的几条硬规矩：
 * <ol>
 * <li>掉落敏感的方块（矿石、玻璃、树叶这类，认不出的模组方块也算）：带精准采集或时运的不参与竞争——
 *     主手握着又对路的不换走，背包里带着的也不换上来，换上去等于替人定了要哪种掉落。</li>
 * <li>调用方预留的东西不自动拿来挖；主手已经握着的照用。</li>
 * <li>这一趟要掉落、方块又要对的工具才掉时，挖不到掉落的候选不参与竞争。</li>
 * </ol>
 * 账算完，赢家不是主手时要看换手值不值：这一趟省下的超过换手代价才换；主手挖不到掉落时不看省多少，直接换。
 */
public final class DigToolChoice {

    /** 一刻值多少：时间成本 = 挖一格的刻数 × 这个价。 */
    public static final double TIME_PRICE_PER_TICK = 0.01;
    /** 换手一次折算的成本：手上这把这一趟能省下的超过它才值得换。 */
    public static final double SWAP_COST = 0.2;
    /** 有经验修补的工具，磨掉的耐久多半会被补回来，磨损成本按这个折扣算。 */
    public static final double MENDING_DISCOUNT = 0.2;
    /** 认不出材料的工具先按这个价算，别把不认识的模组工具当白菜。 */
    public static final double UNKNOWN_MATERIAL_VALUE = 5.0;
    /** 精准采集、时运会改掉落，按这个价算它对人的稀有价值。 */
    public static final double DROP_CHANGING_VALUE = 50.0;
    /** 其他附魔的附加价值。 */
    public static final double OTHER_ENCHANT_VALUE = 5.0;

    private DigToolChoice() {}

    /**
     * 背包里一格东西挖这一格的样子。
     *
     * @param slot          背包格号（0~8 快捷栏，9~35 主背包）
     * @param empty         这一格是空的
     * @param speed         它挖这一格的有效速度（工具自己的速度，带效率的已换算成加快的倍数；空手 1）
     * @param drops         拿它挖这一格掉不掉东西（够不够格）
     * @param maxDurability 满耐久；没有耐久为 0
     * @param durabilityLeft 还剩多少耐久；没有耐久为 0
     * @param wearPerBlock  挖一格磨掉多少耐久（问工具的方块破坏组件）；不磨耐久为 0
     * @param materialValue 材料价值（木 1、石 1.5、铁 10、金 15、钻石 50、下界合金 100；认不出按 {@link #UNKNOWN_MATERIAL_VALUE}）
     * @param extraValue    附魔附加价值
     * @param silkOrFortune 带精准采集或时运
     * @param mending       带经验修补
     * @param kept          调用方预留着的
     */
    public record Candidate(int slot, boolean empty, float speed, boolean drops, int maxDurability, int durabilityLeft,
            int wearPerBlock, double materialValue, double extraValue, boolean silkOrFortune, boolean mending,
            boolean kept) {

        /** 空着的一格：空手就是拿这一格挖。 */
        public static Candidate emptySlot(int slot) {
            return new Candidate(slot, true, 1.0f, false, 0, 0, 0, 0, 0, false, false, false);
        }

        /** 有没有耐久可磨。 */
        public boolean damageable() {
            return maxDurability > 0;
        }

        /** 已经磨到底了：能用也磨不了几下，不当候选。 */
        public boolean wornOut() {
            return damageable() && durabilityLeft <= 0;
        }
    }

    /**
     * 要挖的这一格。
     *
     * @param hardness         硬度（原版的破坏时间系数）；0 是一下就没
     * @param needsCorrectTool 要够格的工具才掉东西（石头、矿石这类）
     * @param dropSensitive    精准采集、时运会改它的掉落（矿石、玻璃、树叶这类；认不出的模组方块也算）
     */
    public record Block(float hardness, boolean needsCorrectTool, boolean dropSensitive) {}

    /**
     * 结论四态：主手已是合适的、该换哪一格、该腾空手用空手挖、没有够格的（空手挖一格要多久）。
     */
    public sealed interface Verdict {
        /** 主手已是合适的：不动。空手最划算而手正空着时同样是它。 */
        record Keep() implements Verdict {}

        /** 换到这一格。 */
        record Swap(int slot) implements Verdict {}

        /** 空手最划算：把手里的东西收起来，腾空主手再挖。 */
        record BareHand() implements Verdict {}

        /** 没有够格的：身上没有一样挖得下来它（要掉落时）的，附上空手挖一格多少刻，现做一把划不划算由调用方算。 */
        record None(int handTicks) implements Verdict {}
    }

    /**
     * 挑一样最便宜的。
     *
     * @param slots        背包前 36 格的样子，按格号排；主手那一格也在里面
     * @param selected     主手是快捷栏第几格
     * @param block        要挖的这一格
     * @param expectedBlocks 这一趟预计还要挖几格同样的（估不出给 1）
     * @param wantsDrop    这一趟挖下来的东西要不要：只要路通的挖法给 false，挖不到掉落的候选也能参与
     */
    public static Verdict choose(List<Candidate> slots, int selected, Block block, int expectedBlocks,
            boolean wantsDrop) {
        Candidate main = selected >= 0 && selected < slots.size() ? slots.get(selected) : Candidate.emptySlot(selected);
        // 要掉落、又要对的工具才掉的方块：挖不到掉落的候选不算数。
        boolean requiresDrop = wantsDrop && block.needsCorrectTool();
        // 主手带着改掉落的那把、又挖得下来：握着它的人就是要这个效果，不换走。
        if (block.dropSensitive() && main.silkOrFortune() && dropsWith(main, block)) {
            return new Verdict.Keep();
        }
        List<Candidate> pool = new ArrayList<>();
        for (Candidate candidate : slots) {
            if (candidate.slot() == selected || candidate.empty() || candidate.kept() || candidate.wornOut()) continue;
            if (block.dropSensitive() && candidate.silkOrFortune()) continue;
            if (requiresDrop && !dropsWith(candidate, block)) continue;
            pool.add(candidate);
        }
        Candidate hands = Candidate.emptySlot(selected);
        boolean mainHolds = !main.empty() && !main.wornOut();
        Trial bareHand = requiresDrop ? null : trial(hands, true, 1, block);
        Trial heldTool = mainHolds && (!requiresDrop || dropsWith(main, block)) ? trial(main, false, 0, block) : null;
        Trial best = better(bareHand, heldTool);
        for (Candidate candidate : pool) best = better(best, trial(candidate, false, 2, block));
        if (best == null) return new Verdict.None(ticksPerBlock(block, 1.0f, !block.needsCorrectTool()));
        // 主手拿着的最便宜、或者手正空着而空手最便宜：不动。
        if (heldTool != null && best == heldTool || best.bareHand() && !mainHolds) return new Verdict.Keep();
        // 换手的账：这一趟省下的超过换手代价才换；主手挖不到掉落时不看省多少，直接换。
        double mainCost = mainHolds ? costPerBlock(block, main) : costPerBlock(block, hands);
        boolean mainDrops = !requiresDrop || mainHolds && dropsWith(main, block);
        double saved = (mainCost - best.cost()) * Math.max(1, expectedBlocks);
        if (mainDrops && saved <= SWAP_COST) return new Verdict.Keep();
        return best.bareHand() ? new Verdict.BareHand() : new Verdict.Swap(best.slot());
    }

    /** 挖一格要多少刻（原版的破坏时间）：硬度 × 30 ÷ 速度（掉东西时），掉不了东西是硬度 × 100 ÷ 速度。一下就没的方块是 0。 */
    public static int ticksPerBlock(Block block, float speed, boolean drops) {
        if (block.hardness() <= 0) return 0;
        double ticks = block.hardness() * (drops ? 30.0 : 100.0) / Math.max(speed, 0.0001f);
        return (int) Math.ceil(ticks);
    }

    /** 一趟比较里的一项候选：拿它挖这一格的每格代价；空手也算一项。priority 越小越少动手。 */
    private record Trial(Candidate candidate, boolean bareHand, int priority, double cost) {
        int slot() {
            return candidate.slot();
        }
    }

    private static Trial trial(Candidate candidate, boolean bareHand, int priority, Block block) {
        return new Trial(candidate, bareHand, priority, costPerBlock(block, candidate));
    }

    // 更便宜的赢；一样便宜时少动手的赢（主手在前、空手其次、背包里的最后）。
    private static Trial better(Trial current, Trial candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        if (candidate.cost() < current.cost() - 1.0e-9) return candidate;
        if (current.cost() < candidate.cost() - 1.0e-9) return current;
        return candidate.priority() < current.priority() ? candidate : current;
    }

    // 挖这一格要多少成本：磨掉的耐久按剩余耐久折算，加上挖一格的时间。
    private static double costPerBlock(Block block, Candidate candidate) {
        double wear = 0;
        if (candidate.damageable() && candidate.durabilityLeft() > 0 && candidate.wearPerBlock() > 0) {
            wear = (double) candidate.wearPerBlock() / candidate.durabilityLeft()
                    * (candidate.materialValue() + candidate.extraValue())
                    * (candidate.mending() ? MENDING_DISCOUNT : 1.0);
        }
        double time = ticksPerBlock(block, candidate.speed(), dropsWith(candidate, block)) * TIME_PRICE_PER_TICK;
        return wear + time;
    }

    // 拿它挖这一格掉不掉东西：方块本来就不要对的工具时，什么都能挖下来。
    private static boolean dropsWith(Candidate candidate, Block block) {
        return !block.needsCorrectTool() || candidate.drops();
    }
}
