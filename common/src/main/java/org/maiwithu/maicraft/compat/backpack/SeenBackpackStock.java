// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.maiwithu.maicraft.behavior.worldmemory.SeenStock;

/**
 * 上次开包读到的存货与剩余空位：角色开过一次随身背包（无论取东西还是腾地方往里存），
 * 就记下那一刻主存储里各有多少东西、还剩几格通用空槽。时效与按包记账用行为层的 {@link SeenStock}；
 * 这里只把开包读到的格子数喂进去，再把按包分记与几只包合起来看的读法留在联动这一侧。
 *
 * <p>下次拿东西问价、腾格子算空间、或者看自身观察时都读同一本账；包里的东西会变，
 * 记下的数只当线索，取放时以打开看到的现场为准。身上不止一只包时按结构化栏位定位分开记。
 */
public final class SeenBackpackStock {

    /** 每只随身背包（按栏位、分组与槽位定位）上次开包的读数。 */
    private final SeenStock<BackpackSlot.Location> seen = new SeenStock<>();

    /** 开包读到了这只包主存储的内容与剩余空槽数后记一笔，盖掉这只包以前的记录。 */
    public void saw(BackpackSlot.Location location, Map<String, Integer> stacksByItem, int freeSlots, Instant when) {
        Objects.requireNonNull(location, "location");
        seen.saw(location, toLongs(stacksByItem), freeSlots, when);
    }

    /** 原版主背包格号的便捷记法（空槽数不计）：单测直接按主背包格号喂读数时用。 */
    public void saw(int mainSlot, Map<String, Integer> stacksByItem, Instant when) {
        saw(BackpackSlot.Location.mainSlot(mainSlot), stacksByItem, 0, when);
    }

    /** 记下这只包带着无限格升级等读不出常理主存储的情况：五分钟内不再拿它算空槽反复去开。 */
    public void markUnreadable(BackpackSlot.Location location, Instant when) {
        Objects.requireNonNull(location, "location");
        seen.sawUnreadable(location, when);
    }

    /** 单只包五分钟内的记录；没看过或已过期给空。 */
    public Optional<Sighting> freshOf(BackpackSlot.Location location, Instant now) {
        return seen.fresh(location, now).map(SeenBackpackStock::sightingOf);
    }

    /** 五分钟内看过的全部可读背包加起来的存货；一只都没看过或都过了时效给空。 */
    public Optional<Sighting> fresh(Instant now) {
        return freshIn(null, now);
    }

    /** 此刻在身的这几只包里，五分钟内看过的可读背包加起来的存货；卸掉不在身上的旧记录不掺进来。 */
    public Optional<Sighting> fresh(Set<BackpackSlot.Location> present, Instant now) {
        Objects.requireNonNull(present, "present");
        return freshIn(present, now);
    }

    // 几只包的读数合起来：件数相加、空位相加，看到的时刻取最早的一笔（越久越只能当大概）。
    private Optional<Sighting> freshIn(Set<BackpackSlot.Location> present, Instant now) {
        Map<String, Integer> total = new LinkedHashMap<>();
        int totalFree = 0;
        Instant oldest = null;
        for (Map.Entry<BackpackSlot.Location, SeenStock.Sighting> entry : seen.freshAll(now).entrySet()) {
            if (present != null && !present.contains(entry.getKey())) continue;
            SeenStock.Sighting sighting = entry.getValue();
            if (!sighting.readable()) continue;
            sighting.stacksByItem().forEach((itemId, count) -> total.merge(itemId, count.intValue(), Integer::sum));
            totalFree += sighting.freeSlots();
            oldest = oldest == null || sighting.seenAt().isBefore(oldest) ? sighting.seenAt() : oldest;
        }
        return oldest == null ? Optional.empty() : Optional.of(new Sighting(total, totalFree, true, oldest));
    }

    /** 五分钟内看过几只可读的包。 */
    public int freshCount(Instant now) {
        return freshCountIn(null, now);
    }

    /** 此刻在身的这几只包里，五分钟内看过几只（含已确认为不可读的，避免把不可读的再当成"没看过"去赌）。 */
    public int freshCount(Set<BackpackSlot.Location> present, Instant now) {
        Objects.requireNonNull(present, "present");
        return freshCountIn(present, now);
    }

    private int freshCountIn(Set<BackpackSlot.Location> present, Instant now) {
        int count = 0;
        for (Map.Entry<BackpackSlot.Location, SeenStock.Sighting> entry : seen.freshAll(now).entrySet()) {
            if (present != null && !present.contains(entry.getKey())) continue;
            count++;
        }
        return count;
    }

    /** 开包中途界面丢了、读数没记成：清掉这只包的旧数，别照着过时旧数不去看。 */
    public void forget(BackpackSlot.Location location) {
        seen.forget(location);
    }

    /** 原版主背包格号的清除便捷入口。 */
    public void forget(int mainSlot) {
        forget(BackpackSlot.Location.mainSlot(mainSlot));
    }

    // 行为层按 long 记件数，开包读到的件数本来就是 int：交回调用方时换回去。
    private static Sighting sightingOf(SeenStock.Sighting sighting) {
        Map<String, Integer> stacks = new LinkedHashMap<>();
        sighting.stacksByItem().forEach((itemId, count) -> stacks.put(itemId, count.intValue()));
        return new Sighting(stacks, sighting.freeSlots(), sighting.readable(), sighting.seenAt());
    }

    private static Map<String, Long> toLongs(Map<String, Integer> stacksByItem) {
        Map<String, Long> longs = new LinkedHashMap<>();
        stacksByItem.forEach((itemId, count) -> longs.put(itemId, count == null ? 0L : count.longValue()));
        return longs;
    }

    /**
     * 一次开包读到的主存储快照。
     *
     * @param stacksByItem 物品 ID → 主存储里的件数，只记有货的
     * @param freeSlots    主存储里没设记忆筛选的通用空槽数
     * @param readable     主存储是否按常理可读（带无限格升级等读数异常时为 false）
     * @param seenAt       看到的时刻
     */
    public record Sighting(Map<String, Integer> stacksByItem, int freeSlots, boolean readable, Instant seenAt) {

        public Sighting {
            Objects.requireNonNull(stacksByItem, "stacksByItem");
            Objects.requireNonNull(seenAt, "seenAt");
            stacksByItem = Map.copyOf(stacksByItem);
            freeSlots = Math.max(0, freeSlots);
        }

        /** 兼容只关心存货与时刻的构造。 */
        public Sighting(Map<String, Integer> stacksByItem, Instant seenAt) {
            this(stacksByItem, 0, true, seenAt);
        }

        /** 想要的东西包里有几件：wanted 按物品 ID 回答算不算想要的（标签由调用方查好）。 */
        public int count(Predicate<String> wanted) {
            long total = SeenStock.count(stacksByItem, wanted);
            return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }
    }
}
