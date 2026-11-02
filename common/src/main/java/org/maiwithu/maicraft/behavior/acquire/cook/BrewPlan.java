// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.game.player.StackKey;

/**
 * 酿一种药水的一整条：从哪种瓶子起、一步一步放什么材料、要几瓶分几组。
 * 身上已有的药水（粗制的药水、抗火药水……）接得上、够这次要的瓶数就从它接着酿，接不上从水瓶开始；
 * 几条都行时身上缺得少的那条先，一样多挑步数少的。一炉最多三瓶，要几瓶酿几瓶，不凑满，超过三瓶分几组，每组走一遍整条。
 *
 * @param start   起步的那种瓶子（带药水种类的写法）
 * @param steps   从起步到要的那种，一步一步按先后排
 * @param bottles 一共要几瓶
 */
public record BrewPlan(StackKey start, List<BrewStep> steps, int bottles) {

    /** 酿造台一炉最多几瓶：下面三个瓶位，一格一瓶。 */
    public static final int BOTTLES_PER_BREW = 3;
    /** 水瓶：普通药水瓶里装的是水，酿什么都可以从它起步，拿玻璃瓶去水边装得到。 */
    public static final StackKey WATER_BOTTLE = new StackKey(StackKey.PLAIN_POTION, "minecraft:water");
    /** 酿造台的燃料：一个烈焰粉顶二十炉。 */
    public static final String BLAZE_POWDER = "minecraft:blaze_powder";
    /** 一整条最多几步：原版最长的（水瓶 → 粗制 → 某种 → 延长或加强 → 喷溅 → 滞留）也就五步。 */
    static final int MAX_STEPS = 6;
    /** 倒着找时最多看多少条半截的路：模组加了一大堆酿造配方也不在问价时卡住。 */
    private static final int MAX_PARTIALS = 5000;

    public BrewPlan {
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("酿造的一整条至少一步");
        if (bottles < 1) throw new IllegalArgumentException("至少酿一瓶：" + bottles);
    }

    /** 最后酿出来的那种。 */
    public StackKey target() {
        return steps.getLast().to();
    }

    /** 每组几瓶：先满三瓶一组，剩下的单独一组。 */
    public List<Integer> groups() {
        List<Integer> groups = new ArrayList<>();
        for (int left = bottles; left > 0; left -= BOTTLES_PER_BREW) groups.add(Math.min(BOTTLES_PER_BREW, left));
        return groups;
    }

    /** 一共酿几炉：每组每一步一炉。 */
    public int brews() {
        return groups().size() * steps.size();
    }

    /**
     * 照这一整条做完，身上还缺几件：起步的瓶子差几瓶（水瓶要去装），每一步的材料每组一件、差几件。
     *
     * @param carried 一种东西身上有几件；问的是带种类的写法或物品 ID
     */
    public int missing(ToIntFunction<String> carried) {
        int missing = Math.max(0, bottles - carried.applyAsInt(start.written()));
        for (Map.Entry<String, Integer> need : ingredientsPerRun().entrySet()) {
            missing += Math.max(0, need.getValue() * groups().size() - carried.applyAsInt(need.getKey()));
        }
        return missing;
    }

    /** 走一遍整条每样材料用几件：同一样材料用在两步里就算两件。 */
    public Map<String, Integer> ingredientsPerRun() {
        Map<String, Integer> needs = new LinkedHashMap<>();
        for (BrewStep step : steps) needs.merge(step.ingredient(), 1, Integer::sum);
        return needs;
    }

    /**
     * 等这一炉的时候还要备的、各几件：这一组从 nextStep 起每步的材料、后面几组的瓶子和每步的材料；
     * 燃料条还够 fuelLeft 炉、比剩下的炉数少时再一个烈焰粉（还不知道燃料条时给 -1，不算燃料）。
     * 身上已有的由调用方扣掉。
     *
     * @param group    眼下是第几组（从 0 起）
     * @param nextStep 这一组下一炉是第几步（从 0 起）；正在冒泡的那一步不算
     */
    public Map<String, Integer> stillToUse(int group, int nextStep, int fuelLeft) {
        Map<String, Integer> want = new LinkedHashMap<>();
        List<Integer> sizes = groups();
        for (int i = Math.max(0, nextStep); i < steps.size(); i++) want.merge(steps.get(i).ingredient(), 1, Integer::sum);
        for (int later = group + 1; later < sizes.size(); later++) {
            want.merge(start.written(), sizes.get(later), Integer::sum);
            for (BrewStep step : steps) want.merge(step.ingredient(), 1, Integer::sum);
        }
        int brewsLeft = Math.max(0, steps.size() - nextStep) + Math.max(0, sizes.size() - group - 1) * steps.size();
        if (fuelLeft >= 0 && fuelLeft < brewsLeft) want.merge(BLAZE_POWDER, 1, Integer::sum);
        return want;
    }

    /** 给人看的一整条：水瓶 +minecraft:nether_wart→ 粗制 +minecraft:magma_cream→ 抗火。 */
    public String describe() {
        StringBuilder text = new StringBuilder(start.written());
        for (BrewStep step : steps) text.append(" +").append(step.ingredient()).append("→ ").append(step.to().written());
        return text.toString();
    }

    /**
     * 从酿造表里挑一整条：倒着从要的那种往回找，每一条路上同一种不走第二遍，最多 {@link #MAX_STEPS} 步。
     * 起步的那种得是水瓶，或身上够这次要的瓶数的药水；身上不够的不从它起：一组里不混两种瓶子，
     * 后面几组也不用回头再去酿起步的那种。
     *
     * @param carried 一种东西身上有几件；问的是带种类的写法或物品 ID
     */
    public static Optional<BrewPlan> plan(List<BrewStep> table, StackKey target, int bottles,
            ToIntFunction<String> carried) {
        List<BrewPlan> found = new ArrayList<>();
        List<List<BrewStep>> partials = new ArrayList<>();
        partials.add(List.of());
        for (int looked = 0; looked < partials.size() && looked < MAX_PARTIALS; looked++) {
            List<BrewStep> partial = partials.get(looked);
            StackKey from = partial.isEmpty() ? target : partial.getFirst().from();
            if (!partial.isEmpty() && (from.equals(WATER_BOTTLE) || carried.applyAsInt(from.written()) >= bottles)) {
                found.add(new BrewPlan(from, partial, bottles));
            }
            if (partial.size() >= MAX_STEPS) continue;
            Set<StackKey> onPath = new HashSet<>();
            onPath.add(target);
            for (BrewStep step : partial) onPath.add(step.from());
            for (BrewStep step : table) {
                if (!step.to().equals(from) || onPath.contains(step.from())) continue;
                List<BrewStep> longer = new ArrayList<>(partial.size() + 1);
                longer.add(step);
                longer.addAll(partial);
                partials.add(longer);
            }
        }
        return found.stream().min(Comparator.comparingInt((BrewPlan plan) -> plan.missing(carried))
                .thenComparingInt(plan -> plan.steps().size()));
    }
}
