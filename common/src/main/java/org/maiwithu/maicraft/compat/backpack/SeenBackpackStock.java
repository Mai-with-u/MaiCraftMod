// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 上次开包读到的存货与剩余空位：角色开过一次随身背包（无论取东西还是腾地方往里存），
 * 就记下那一刻主存储里各有多少东西、还剩几格通用空槽。
 *
 * <p>下次拿东西问价、腾格子算空间、或者看自身观察时都读同一本账；包里的东西会变，
 * 记下的数五分钟内算数，取放时以打开看到的现场为准，隔久了就当没看过。
 * 身上不止一只包时按结构化栏位定位分开记，只在这次进世界期间有效，不存盘。
 */
public final class SeenBackpackStock {

    /** 记下的开包读数多久内还算数。包里的东西会变，五分钟前看到的只能当大概；类别：玩家常识。 */
    public static final Duration FRESH_FOR = Duration.ofMinutes(5);

    /** 每只随身背包（按栏位、分组与槽位定位）上次开包的读数。 */
    private final Map<BackpackSlot.Location, Sighting> sightings = new HashMap<>();

    /** 开包读到了这只包主存储的内容与剩余空槽数后记一笔，盖掉这只包以前的记录。 */
    public void saw(BackpackSlot.Location location, Map<String, Integer> stacksByItem, int freeSlots, Instant when) {
        Objects.requireNonNull(location, "location");
        sightings.put(location, new Sighting(
                Map.copyOf(new LinkedHashMap<>(stacksByItem)), Math.max(0, freeSlots), true, when));
    }

    /** 原版主背包格号的便捷记法（空槽数不计）：单测直接按主背包格号喂读数时用。 */
    public void saw(int mainSlot, Map<String, Integer> stacksByItem, Instant when) {
        saw(BackpackSlot.Location.mainSlot(mainSlot), stacksByItem, 0, when);
    }

    /** 记下这只包带着无限格升级等读不出常理主存储的情况：五分钟内不再拿它算空槽反复去开。 */
    public void markUnreadable(BackpackSlot.Location location, Instant when) {
        Objects.requireNonNull(location, "location");
        sightings.put(location, new Sighting(Map.of(), 0, false, when));
    }

    /** 单只包在五分钟内的记录；没看过或已过期给空。 */
    public Optional<Sighting> freshOf(BackpackSlot.Location location, Instant now) {
        Sighting sighting = sightings.get(location);
        if (sighting == null || isExpired(sighting, now)) {
            return Optional.empty();
        }
        return Optional.of(sighting);
    }

    /** 五分钟内看过的全部可读背包加起来的存货；一只都没看过或都过了 {@link #FRESH_FOR} 给空。 */
    public Optional<Sighting> fresh(Instant now) {
        return freshIn(null, now);
    }

    /** 此刻在身的这几只包里，五分钟内看过的可读背包加起来的存货；卸掉不在身上的旧记录不掺进来。 */
    public Optional<Sighting> fresh(Set<BackpackSlot.Location> present, Instant now) {
        Objects.requireNonNull(present, "present");
        return freshIn(present, now);
    }

    private Optional<Sighting> freshIn(Set<BackpackSlot.Location> present, Instant now) {
        Map<String, Integer> total = new LinkedHashMap<>();
        int totalFree = 0;
        Instant oldest = null;
        for (Map.Entry<BackpackSlot.Location, Sighting> entry : sightings.entrySet()) {
            if (present != null && !present.contains(entry.getKey())) continue;
            Sighting sighting = entry.getValue();
            if (!sighting.readable() || isExpired(sighting, now)) continue;
            sighting.stacksByItem().forEach((itemId, count) -> total.merge(itemId, count, Integer::sum));
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
        for (Map.Entry<BackpackSlot.Location, Sighting> entry : sightings.entrySet()) {
            if (present != null && !present.contains(entry.getKey())) continue;
            if (!isExpired(entry.getValue(), now)) count++;
        }
        return count;
    }

    /** 开包中途界面丢了、读数没记成：清掉这只包的旧数，别照着过时旧数不去看。 */
    public void forget(BackpackSlot.Location location) {
        sightings.remove(location);
    }

    /** 原版主背包格号的清除便捷入口。 */
    public void forget(int mainSlot) {
        forget(BackpackSlot.Location.mainSlot(mainSlot));
    }

    private static boolean isExpired(Sighting sighting, Instant now) {
        return sighting.seenAt().plus(FRESH_FOR).isBefore(now);
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
            int total = 0;
            for (Map.Entry<String, Integer> entry : stacksByItem.entrySet()) {
                if (wanted.test(entry.getKey())) total += entry.getValue();
            }
            return total;
        }
    }
}
