// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 挑工具：对着要挖的一格，从主手和背包里挑最合适的一把。纯判断，只读给进来的事实。
 *
 * <p>像玩家一样按这个顺序想：
 * <ol>
 * <li>主手已经握着带精准采集或时运、又对路的工具，不换走——它会改掉落，握着它的人多半就是要这个效果；
 *     掉落敏感的方块（矿石、玻璃、树叶这类，认不出的模组方块也算）也不把带这两种附魔的换上来，掉落要不要、要哪种由调用方定。</li>
 * <li>主手挖了不掉落、有工具能掉落（徒手挖石头、拿剑挖矿），换能掉落的。</li>
 * <li>都能掉落时越省越好：耐久少、不心疼的在前（木、石、铁、钻石），没附魔的在前，珍稀的垫底；一样省才比挖得快。
 *     主手已是对路的工具时，背包里有更省的也对路就换下来——石镐够用的活儿不摸铁镐。</li>
 * <li>徒手也掉落的（泥土、沙子），算时间账：按这一趟预计挖的格数，换一把能省出明显时间才换，挖两下就不折腾。</li>
 * </ol>
 * 调用方预留着的工具不挑出来用；主手已经握着的，预留了也照用。快坏的（剩的耐久不够这一批）不挑。
 */
public final class DigToolChoice {

    /** 换一把工具至少要省出这么多刻才值得：一秒。少于这个，挖两下泥土就不折腾。类别：玩家常识。 */
    public static final int SWAP_WORTH_TICKS = 20;
    /** 没有耐久的工具（模组的电钻这类）排在钻石之后：不磨损，但多半珍贵。 */
    private static final int UNDAMAGEABLE_COST = 5000;
    /** 带附魔或标了稀有度的排在所有普通工具之后。 */
    private static final int PRECIOUS_COST = 100_000;

    private DigToolChoice() {}

    /**
     * 背包里一格东西挖这一格的样子。
     *
     * @param slot           背包格号（0~8 快捷栏，9~35 主背包）
     * @param empty          这一格是空的
     * @param speed          它挖这一格的速度倍数（原版问出来的，不对路的工具和空手是 1）
     * @param drops          拿它挖这一格掉不掉东西（够不够格）
     * @param damageable     有没有耐久
     * @param maxDurability  满耐久；没有耐久为 0
     * @param durabilityLeft 还剩多少耐久；没有耐久为 0
     * @param silkOrFortune  带精准采集或时运
     * @param precious       带附魔或标了稀有度
     * @param kept           调用方预留着的
     */
    public record Candidate(int slot, boolean empty, float speed, boolean drops, boolean damageable, int maxDurability,
            int durabilityLeft, boolean silkOrFortune, boolean precious, boolean kept) {

        /** 空着的一格。 */
        public static Candidate emptySlot(int slot) {
            return new Candidate(slot, true, 1.0f, false, false, 0, 0, false, false, false);
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

    /** 结论三态：主手已是合适的、该换哪一格、没有合适的（徒手挖一格要多久）。 */
    public sealed interface Verdict {
        /** 主手已是合适的：不动。徒手也掉、换了省不出时间时同样是它。 */
        record Keep() implements Verdict {}

        /** 换到这一格。 */
        record Swap(int slot) implements Verdict {}

        /** 没有合适的：身上没有一把对路的；附上主手挖一格多少刻，现做一把划不划算由调用方算。 */
        record None(int handTicks) implements Verdict {}
    }

    /**
     * 挑一把。
     *
     * @param slots             背包前 36 格的样子，按格号排；主手那一格也在里面
     * @param selected          主手是快捷栏第几格
     * @param block             要挖的这一格
     * @param expectedBlocks    这一趟预计还要挖几格同样的（估不出给 1）
     * @param minimumDurability 这一批至少要剩多少耐久；不要求给 0
     */
    public static Verdict choose(List<Candidate> slots, int selected, Block block, int expectedBlocks,
            int minimumDurability) {
        Candidate main = selected >= 0 && selected < slots.size() ? slots.get(selected) : Candidate.emptySlot(selected);
        boolean mainProper = usable(main, minimumDurability) && (main.drops() || !block.needsCorrectTool());
        // 主手握着改掉落的那把、又对路：不换走。
        if (mainProper && main.silkOrFortune()) return new Verdict.Keep();
        List<Candidate> pool = slots.stream()
                .filter(candidate -> candidate.slot() != selected)
                .filter(candidate -> usable(candidate, minimumDurability) && !candidate.kept())
                .filter(candidate -> !(block.dropSensitive() && candidate.silkOrFortune()))
                .toList();
        List<Candidate> proper = pool.stream().filter(candidate -> candidate.drops() || !block.needsCorrectTool()).toList();
        Comparator<Candidate> cheapestThenFastest = Comparator.comparingInt(DigToolChoice::cost)
                .thenComparingInt(candidate -> ticksPerBlock(block, candidate.speed(), true));
        if (mainProper) {
            // 主手对路：背包里有更省的也对路就换下来，没有就不动。
            return proper.stream().filter(candidate -> cost(candidate) < cost(main)).min(cheapestThenFastest)
                    .<Verdict>map(candidate -> new Verdict.Swap(candidate.slot())).orElse(new Verdict.Keep());
        }
        boolean mainDrops = !block.needsCorrectTool() || main.drops();
        int mainTicks = ticksPerBlock(block, usable(main, minimumDurability) ? main.speed() : 1.0f, mainDrops);
        Optional<Candidate> best = proper.stream().min(cheapestThenFastest);
        if (best.isEmpty()) return new Verdict.None(mainTicks);
        // 主手挖了不掉落：换能掉落的，不看省多少时间。
        if (!mainDrops) return new Verdict.Swap(best.get().slot());
        // 徒手也掉落：换了能省出明显时间才换。
        long saved = (long) (mainTicks - ticksPerBlock(block, best.get().speed(), true)) * Math.max(1, expectedBlocks);
        return saved >= SWAP_WORTH_TICKS ? new Verdict.Swap(best.get().slot()) : new Verdict.Keep();
    }

    /**
     * 挖一格要多少刻（原版的破坏时间）：硬度 × 30 ÷ 速度倍数（掉东西时），掉不了东西是硬度 × 100 ÷ 速度倍数。
     * 一下就没的方块是 0。
     */
    public static int ticksPerBlock(Block block, float speed, boolean drops) {
        if (block.hardness() <= 0) return 0;
        double ticks = block.hardness() * (drops ? 30.0 : 100.0) / Math.max(speed, 0.0001f);
        return (int) Math.ceil(ticks);
    }

    // 能拿来挖：不是空格、比空手快、耐久撑得住这一批（剩 1 点的不用，免得挖着挖着断在手里）。
    private static boolean usable(Candidate candidate, int minimumDurability) {
        if (candidate.empty() || candidate.speed() <= 1.0f) return false;
        if (!candidate.damageable()) return true;
        return candidate.durabilityLeft() > 1 && candidate.durabilityLeft() >= minimumDurability;
    }

    // 越省越小：耐久少的不心疼排前（金、木、石、铁、钻石……按满耐久），没耐久的模组工具其次，带附魔、稀有的垫底。
    private static int cost(Candidate candidate) {
        int base = candidate.damageable() ? candidate.maxDurability() : UNDAMAGEABLE_COST;
        return candidate.precious() ? PRECIOUS_COST + base : base;
    }
}
