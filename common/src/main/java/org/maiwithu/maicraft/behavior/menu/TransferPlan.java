// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * 搬运计划：一次搬运要点哪几下的纯计算，不含点击本身。
 *
 * <p>整堆走快速移动（一笔点击，目标格由游戏挑）；要指定目标格的整堆先整份拿起再整份放下，
 * 这是唯一允许交换目标格的情形。尾数不能靠快速移动——尾数搬运用快速移动会把整堆多取走——
 * 而是拆成二分堆：右键每次拿走源格向上取整的半堆，凑够要的量就整份放进目标格，
 * 拿多了先一个一个放、放够再把手里剩的退回源格。例如从 64 个里取 49 个，放进目标格的是 32、16、1 三份。
 *
 * <p>执行纪律：每笔点击前核对界面身份（还是认领的那份菜单）与光标（为空才从源格动手）；
 * 指定数量的搬运只允许把目标格往多了堆同一种物品，遇到装不下的物品格不交换、换一格。
 */
public final class TransferPlan {

    /** 点击发生在哪一侧的槽位上。 */
    public enum Where { SOURCE, TARGET }

    /** 一步点击的原版鼠标操作。 */
    public enum Kind {
        /** 快速移动（Shift+左键）整堆走向另一侧，目标格由游戏挑。 */
        QUICK_MOVE,
        /** 光标为空时左键源格：整份拿起。 */
        TAKE_ALL,
        /** 光标为空时右键源格：拿走向上取整的半堆。 */
        TAKE_HALF,
        /** 光标拿着物品时左键目标格：整份放下（与目标格物品相同时并入，不同时是交换）。 */
        GIVE_ALL,
        /** 光标拿着物品时右键目标格：放下一个。 */
        GIVE_ONE,
        /** 光标拿着物品时右键源格：退回一个。 */
        PUT_BACK_ONE,
        /** 光标拿着物品时左键源格：余量整份退回源格。 */
        PUT_BACK_ALL
    }

    /** 一步点击：在源格或目标格上做一次原版鼠标操作，items 是这一步移动的物品数。 */
    public record Step(Where where, Kind kind, int items, String note) {
    }

    private final boolean maySwapTarget;
    private final List<Step> steps;

    private TransferPlan(boolean maySwapTarget, List<Step> steps) {
        this.maySwapTarget = maySwapTarget;
        this.steps = steps;
    }

    /** 是否允许交换目标格；只有整堆操作为真，指定数量的搬运永远不许拿目标格上不相干的东西换位。 */
    public boolean maySwapTarget() {
        return maySwapTarget;
    }

    /** 按顺序要做的点击。 */
    public List<Step> steps() {
        return steps;
    }

    /** 整堆快速移动：一笔点击把整堆送到另一侧，不指定落在哪一格，也不交换任何格子。 */
    public static TransferPlan quickMoveWholeStack() {
        return new TransferPlan(false, List.of(
                new Step(Where.SOURCE, Kind.QUICK_MOVE, Integer.MAX_VALUE, "整堆快速移动到另一侧")));
    }

    /** 整堆搬到指定的目标格：整份拿起再整份放下；目标格上是别的东西时允许交换，这是交换的唯一许可。 */
    public static TransferPlan swapWholeStack() {
        return new TransferPlan(true, List.of(
                new Step(Where.SOURCE, Kind.TAKE_ALL, Integer.MAX_VALUE, "整份拿起"),
                new Step(Where.TARGET, Kind.GIVE_ALL, Integer.MAX_VALUE, "整份放进目标格，允许交换")));
    }

    /**
     * 精确数量的尾数搬运：从源格拿走正好 amount 件，放进目标格。
     *
     * <p>数量等于源格件数（整格拿走）也走这份计划：源格只有一件、要搬的也是这一件时，
     * 拿半堆恰好拿空源格、整份放进目标格，与「整堆够用整堆拿」是同一件事，不再拦下。
     * 源格装了精妙背包堆叠升级这类超叠槽位时（例如单格 128 个圆石、光标上限 64，或单格叠着多把石镐、光标上限 1），
     * 源格当前件数允许大于光标单次可抓上限，计划按光标单次上限分轮抓取，多拿的尾数照常退回原本就装得下的源格。
     *
     * @param sourceCount 动手前源格里的件数
     * @param amount      要搬的件数，不超过源格里的件数（更多时搬不了，超出源格装着的东西）
     * @param cursorMax   鼠标光标单次可抓上限（物品自身的 {@code getMaxStackSize()}，圆石为 64、工具为 1）
     */
    public static TransferPlan exactAmount(int sourceCount, int amount, int cursorMax) {
        if (sourceCount < 1 || amount < 1 || amount > sourceCount) {
            throw new IllegalArgumentException(
                    "尾数搬运要求 1 ≤ 数量 ≤ 源格件数：源格 " + sourceCount + " 件，要搬 " + amount + " 件");
        }
        if (cursorMax < 1) {
            throw new IllegalArgumentException("鼠标光标单次可抓上限至少为 1：" + cursorMax);
        }
        List<Step> steps = new ArrayList<>();
        int source = sourceCount;
        int remaining = amount;
        // 先处理超叠槽位（源格件数大于光标单次上限）的整轮抓取与半堆逼近，再在光标可一次抓起的范围内用最少点击策略收尾。
        while (remaining > 0 && source > cursorMax) {
            if (remaining >= cursorMax) {
                source -= cursorMax;
                remaining -= cursorMax;
                steps.add(new Step(Where.SOURCE, Kind.TAKE_ALL, cursorMax, "按光标上限抓满一轮"));
                steps.add(new Step(Where.TARGET, Kind.GIVE_ALL, cursorMax, "整份放进目标格"));
                continue;
            }
            // 精妙背包超叠槽空手点右键时先按物品最大堆叠截断到光标上限，再折半向上取整（如 128 圆石点右键实抓 32 个）。
            int half = (cursorMax + 1) / 2;
            source -= half;
            steps.add(new Step(Where.SOURCE, Kind.TAKE_HALF, half, "拿走半堆"));
            if (half <= remaining) {
                remaining -= half;
                steps.add(new Step(Where.TARGET, Kind.GIVE_ALL, half, "整份放进目标格"));
            } else {
                // 拿多了按 min(待放, 待退) 选更省点击的一侧：少放就逐个放进目标再整份退回源格，少退就逐个退回源格再整份放入目标。
                int excess = half - remaining;
                if (remaining < excess) {
                    for (int i = 0; i < remaining; i++) {
                        steps.add(new Step(Where.TARGET, Kind.GIVE_ONE, 1, "放一个"));
                    }
                    steps.add(new Step(Where.SOURCE, Kind.PUT_BACK_ALL, excess, "余量整份退回源格"));
                } else {
                    for (int i = 0; i < excess; i++) {
                        steps.add(new Step(Where.SOURCE, Kind.PUT_BACK_ONE, 1, "退回源格一个"));
                    }
                    steps.add(new Step(Where.TARGET, Kind.GIVE_ALL, remaining, "整份放进目标格"));
                }
                source += excess;
                remaining = 0;
            }
        }
        if (remaining > 0) {
            steps.addAll(minimalStepsWithinCursor(source, remaining));
        }
        return new TransferPlan(false, List.copyOf(steps));
    }

    /**
     * 在单次光标可抓起的普通槽位（1 ≤ amount ≤ source ≤ cursorMax）内计算点击次数最少的搬运步骤：
     * 比较「整抓后逐放再整退（k+2 击）」「整抓后逐退再整放（n-k+2 击）」与「半抓折半逼近」，取总点击最少的方案；
     * 例如从 8 件里取 1 件只需 3 击（左键抓 8 → 右键放 1 → 左键把余下 7 件放回空源格），不再退 7 次单件。
     */
    private static List<Step> minimalStepsWithinCursor(int source, int amount) {
        int half = (source + 1) / 2;
        // 刚好等于半堆（含 1 取 1、16 取 8）：右键半抓加左键整放，2 击直达且不触发整格抓空。
        if (amount == half) {
            return List.of(
                    new Step(Where.SOURCE, Kind.TAKE_HALF, half, "拿走半堆"),
                    new Step(Where.TARGET, Kind.GIVE_ALL, half, "整份放进目标格"));
        }
        // 整格拿走（2 取 2 等）：左键整抓加左键整放，2 击直达。
        if (amount == source) {
            return List.of(
                    new Step(Where.SOURCE, Kind.TAKE_ALL, source, "整份拿起"),
                    new Step(Where.TARGET, Kind.GIVE_ALL, source, "整份放进目标格"));
        }
        int excess = source - amount;
        // 方案 A/B：左键抓整叠，按 min(amount, source - amount) 决定逐个放目标再整退，还是逐个退源格再整放。
        List<Step> best = amount <= excess
                ? takeAllGiveOnesPutBackAll(source, amount)
                : takeAllPutBackOnesGiveAll(source, amount);
        // 方案 C：右键先抓半堆。若半堆多于要的量且差额更小，半抓后逐个退回源格再整放目标；若半堆少于要的量，半放后递归余量。
        if (half > amount && (half - amount) + 2 < best.size()) {
            best = takeHalfPutBackOnesGiveAll(half, amount);
        } else if (half < amount) {
            List<Step> tail = minimalStepsWithinCursor(source - half, amount - half);
            if (2 + tail.size() < best.size()) {
                List<Step> combined = new ArrayList<>(2 + tail.size());
                combined.add(new Step(Where.SOURCE, Kind.TAKE_HALF, half, "拿走半堆"));
                combined.add(new Step(Where.TARGET, Kind.GIVE_ALL, half, "整份放进目标格"));
                combined.addAll(tail);
                best = combined;
            }
        }
        return best;
    }

    // 左键抓整叠 → 目标格右键逐个放 amount 次 → 左键把余量整份放回已抓空的源格（共 amount + 2 击）。
    private static List<Step> takeAllGiveOnesPutBackAll(int source, int amount) {
        int excess = source - amount;
        List<Step> steps = new ArrayList<>(amount + 2);
        steps.add(new Step(Where.SOURCE, Kind.TAKE_ALL, source, "整份拿起"));
        for (int i = 0; i < amount; i++) {
            steps.add(new Step(Where.TARGET, Kind.GIVE_ONE, 1, "放一个"));
        }
        steps.add(new Step(Where.SOURCE, Kind.PUT_BACK_ALL, excess, "余量整份退回源格"));
        return steps;
    }

    // 左键抓整叠 → 源格右键逐个退回余量 → 目标格左键整份放下（共 (source - amount) + 2 击）。
    private static List<Step> takeAllPutBackOnesGiveAll(int source, int amount) {
        int excess = source - amount;
        List<Step> steps = new ArrayList<>(excess + 2);
        steps.add(new Step(Where.SOURCE, Kind.TAKE_ALL, source, "整份拿起"));
        for (int i = 0; i < excess; i++) {
            steps.add(new Step(Where.SOURCE, Kind.PUT_BACK_ONE, 1, "退回源格一个"));
        }
        steps.add(new Step(Where.TARGET, Kind.GIVE_ALL, amount, "整份放进目标格"));
        return steps;
    }

    // 右键抓半堆 → 源格右键逐个退回多抓的几件 → 目标格左键整份放下（共 (half - amount) + 2 击）。
    private static List<Step> takeHalfPutBackOnesGiveAll(int half, int amount) {
        int excess = half - amount;
        List<Step> steps = new ArrayList<>(excess + 2);
        steps.add(new Step(Where.SOURCE, Kind.TAKE_HALF, half, "拿走半堆"));
        for (int i = 0; i < excess; i++) {
            steps.add(new Step(Where.SOURCE, Kind.PUT_BACK_ONE, 1, "退回源格一个"));
        }
        steps.add(new Step(Where.TARGET, Kind.GIVE_ALL, amount, "整份放进目标格"));
        return steps;
    }
}
