// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 上次看到的库存：角色上次在一处（一台终端、一只背包）看到的存货——各有多少件、还剩几格空位，
 * 按看到的地方分开记，再看到就盖掉旧的。里面的东西一直会被搬动，记下的数只当线索，
 * 到了跟前以现场看到的为准；隔了 {@link #FRESH_FOR} 就当没看过。只在这次启动内有效，不存盘。
 *
 * <p>各处的东西怎么读出来（终端的存货表、背包的格子）各有各的读法，留在联动包：
 * 联动只把读到的件数与看到的时刻喂进来，时效与按处记账用这一份。
 */
public final class SeenStock<K> {

    /** 记下的读数多久内还算数。里面的东西一直在变，五分钟前看到的只能当大概；类别：玩家常识。 */
    public static final Duration FRESH_FOR = Duration.ofMinutes(5);

    /** 一次看到的存货：物品 ID → 件数（只记有货的）、还剩几格空位、读没读得到、看到的时刻。 */
    public record Sighting(Map<String, Long> stacksByItem, int freeSlots, boolean readable, Instant seenAt) {
        public Sighting {
            Objects.requireNonNull(stacksByItem, "stacksByItem");
            Objects.requireNonNull(seenAt, "seenAt");
            stacksByItem = Map.copyOf(stacksByItem);
            freeSlots = Math.max(0, freeSlots);
        }

        /** 只记存货、不记空位的写法，按读得到算。 */
        public Sighting(Map<String, Long> stacksByItem, Instant seenAt) {
            this(stacksByItem, 0, true, seenAt);
        }

        /** 想要的东西有几件：wanted 按物品 ID 回答算不算想要的（标签由调用方查好）。 */
        public long count(Predicate<String> wanted) {
            return SeenStock.count(stacksByItem, wanted);
        }
    }

    /** 想要的东西在一份存货里有几件；多处看到后各自数还是合起来数，都数这一份。 */
    public static long count(Map<String, ? extends Number> stacksByItem, Predicate<String> wanted) {
        long total = 0;
        for (Map.Entry<String, ? extends Number> entry : stacksByItem.entrySet()) {
            if (wanted.test(entry.getKey())) total += entry.getValue().longValue();
        }
        return total;
    }

    private final Map<K, Sighting> sightings = new LinkedHashMap<>();

    /** 看到一处只记存货的读数（空位不计、按读得到算），盖掉这处以前的记录。 */
    public void saw(K key, Map<String, Long> stacksByItem, Instant when) {
        saw(key, stacksByItem, 0, when);
    }

    /** 看到一处的读数（还剩几格空位）记一笔，盖掉这处以前的记录。 */
    public void saw(K key, Map<String, Long> stacksByItem, int freeSlots, Instant when) {
        Objects.requireNonNull(key, "key");
        sightings.put(key, new Sighting(stacksByItem, freeSlots, true, when));
    }

    /** 这一处带无限格升级等读不出常理存货的情况：只记"看过、读不到"，五分钟内不再当没看过反复去开。 */
    public void sawUnreadable(K key, Instant when) {
        Objects.requireNonNull(key, "key");
        sightings.put(key, new Sighting(Map.of(), 0, false, when));
    }

    /** 这一处不在了或这次读数没记成：忘掉它的记录，免得照着过时旧数不去看。 */
    public void forget(K key) {
        sightings.remove(key);
    }

    /** 这一处五分钟内看到的读数；没看过或过了 {@link #FRESH_FOR} 给空。 */
    public Optional<Sighting> fresh(K key, Instant now) {
        Sighting sighting = sightings.get(key);
        if (sighting == null || expired(sighting, now)) return Optional.empty();
        return Optional.of(sighting);
    }

    /** 五分钟内看过的全部读数（含读不到的，调用方按 readable 自己挑），按看到的先后排。 */
    public Map<K, Sighting> freshAll(Instant now) {
        Map<K, Sighting> fresh = new LinkedHashMap<>();
        for (Map.Entry<K, Sighting> entry : sightings.entrySet()) {
            if (!expired(entry.getValue(), now)) fresh.put(entry.getKey(), entry.getValue());
        }
        return fresh;
    }

    /** 五分钟内看过几处（含读不到的：读不到的也算看过，免得再当没看过去赌）。 */
    public int freshCount(Instant now) {
        return freshAll(now).size();
    }

    private static boolean expired(Sighting sighting, Instant now) {
        return sighting.seenAt().plus(FRESH_FOR).isBefore(now);
    }
}
