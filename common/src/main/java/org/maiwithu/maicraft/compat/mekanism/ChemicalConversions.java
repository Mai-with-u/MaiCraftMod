// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 通用机械的化学品转化，纯函数：配方要的灌注物这类化学品背包带不进机器，机器从附加格吃物品再换成它
 * （一块煤换 10 份碳、一个钻石粉换 10 份钻石）。这里挑投哪样物品、算投几件，投的时候按机器里已有的扣掉几件。
 *
 * <p>挑法：身上带够的先用，其次身上有一些的，再其次原版物品（煤、红石这类最好拿），一件换得少的优先
 * （原料本身，不是压成块、浓缩过的），最后按 ID。不预判拿不拿得到：拿不到由备料如实说缺什么。
 */
final class ChemicalConversions {

    /**
     * 这次投哪样：要的化学品、投的物品、一件换多少、投几件、还能用哪几样。
     *
     * @param chemicalId   配方要的化学品，例如 mekanism:carbon
     * @param itemId       投的物品，例如 minecraft:coal
     * @param perItem      一件换多少
     * @param count        投几件：要的总量除以一件换多少，向上取整
     * @param alternatives 也能换这种化学品的别的物品
     */
    record Plan(String chemicalId, String itemId, long perItem, int count, List<String> alternatives) {
        Plan {
            Objects.requireNonNull(chemicalId, "chemicalId");
            Objects.requireNonNull(itemId, "itemId");
            alternatives = List.copyOf(alternatives);
        }
    }

    /** 投的时候少投几件、为什么。 */
    record Deduction(int skip, String note) {
    }

    private ChemicalConversions() {
    }

    /**
     * 挑一样物品换 amount 份 chemicalId；没有能换它的物品给空。
     *
     * @param conversions 能换成这种化学品的物品与一件换多少（化学品转化配方）
     * @param carried     身上每样物品有几件
     */
    static Optional<Plan> plan(String chemicalId, List<MekanismReads.Conversion> conversions,
            Map<String, Integer> carried, long amount) {
        Map<String, Long> perItem = new LinkedHashMap<>();
        for (MekanismReads.Conversion conversion : conversions) {
            if (conversion.amount() > 0) perItem.putIfAbsent(conversion.itemId(), conversion.amount());
        }
        if (perItem.isEmpty()) return Optional.empty();
        long wanted = Math.max(1, amount);
        Comparator<String> order = Comparator
                .comparing((String id) -> carried.getOrDefault(id, 0) < needed(wanted, perItem.get(id)))
                .thenComparing(id -> -carried.getOrDefault(id, 0))
                .thenComparing(id -> !id.startsWith("minecraft:"))
                .thenComparing(perItem::get)
                .thenComparing(Comparator.naturalOrder());
        List<String> ranked = new ArrayList<>(perItem.keySet());
        ranked.sort(order);
        String chosen = ranked.getFirst();
        return Optional.of(new Plan(chemicalId, chosen, perItem.get(chosen), needed(wanted, perItem.get(chosen)),
                ranked.subList(1, ranked.size())));
    }

    private static int needed(long amount, long perItem) {
        return (int) Math.min(Integer.MAX_VALUE, (amount + perItem - 1) / perItem);
    }

    /** 给 LLM 看的一句：要什么、投什么、一件换多少、投几件、还能用哪几样。 */
    static String describe(Plan plan, long amount) {
        String others = plan.alternatives().isEmpty() ? "" : "；也能用 " + String.join("、", plan.alternatives());
        return "配方要 " + plan.chemicalId() + " ×" + amount + "（化学品，背包带不进机器）：往附加格投 " + plan.itemId()
                + "，一件换 " + plan.perItem() + "，投 " + plan.count() + " 件" + others + "；投的时候读得到机器里已有的就扣掉";
    }

    /**
     * 界面开着时按机器储罐里已有的扣掉几件：已有这种化学品够几件的量就少投几件；储罐里装着别的化学品时照投，
     * 并写明储罐只装一种、这种换不进去（不替 LLM 倒掉别的）；读不到储罐照全量投并说读不到。
     *
     * @param inMachine 机器储罐里每种化学品有多少；读不到（界面不是这台机器的）为空
     */
    static Deduction deduct(String chemicalId, long perItem, int asked, Optional<Map<String, Long>> inMachine) {
        if (inMachine.isEmpty()) {
            return new Deduction(0, "机器里原有多少 " + chemicalId + " 读不到，按配方要的 " + asked + " 件全投");
        }
        long have = inMachine.get().getOrDefault(chemicalId, 0L);
        if (have > 0) {
            int skip = (int) Math.min(asked, have / Math.max(1, perItem));
            return new Deduction(skip, "机器里原有 " + chemicalId + " ×" + have
                    + (skip > 0 ? "，少投 " + skip + " 件，省下的留在背包里" : "，不够一件的量，照投"));
        }
        List<String> others = new ArrayList<>();
        inMachine.get().forEach((id, amount) -> {
            if (!id.equals(chemicalId) && amount > 0) others.add(id + " ×" + amount);
        });
        if (others.isEmpty()) return new Deduction(0, "");
        return new Deduction(0, "机器里装着 " + String.join("、", others) + "，没有要的 " + chemicalId
                + "：储罐被别的化学品占着时 " + chemicalId + " 换不进去，要等它用完；照投");
    }
}
