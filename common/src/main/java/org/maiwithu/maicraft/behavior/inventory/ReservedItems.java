// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 预留：调用方用 reserve 写下"身上这几样各留够几件"。主人交代一次就记着，不用每派一次活重说一遍——
 * 一直有效，跟着这个世界存下来，不跟着某一个目标走。
 *
 * <p>角色自己做主时（现做工具、垫脚、趁空吃、腾背包……）预留的一件不动；下令的目标要用时先用不留的，
 * 不够才动预留的，动了几件就从预留里扣几件（{@link #usedByOrder}）。只在这两种情况下变：
 * Mod 不替调用方判断"攒够了""用不着了"。
 *
 * <p>留够几件按身上的总数算（主背包、快捷栏与副手）；不写件数就是这一样全都留着；写 # 标签就是挂这个标签的
 * 几种加起来留够；几条重叠时都要满足。身上还没有的也能先预留：攒铁时先写"铁锭留 23"，挖回来一块留一块。
 * 只在客户端线程读写。
 */
public final class ReservedItems implements ItemsToKeep {

    /**
     * 一条预留。
     *
     * @param item    物品 ID，或 # 开头的物品标签
     * @param atLeast 身上要留够几件；为 null 是这一样全都留着
     */
    public record Reservation(String item, Integer atLeast) {
        public Reservation {
            Objects.requireNonNull(item, "item");
            new WantedItem(item);
            if (atLeast != null && atLeast < 1) throw new IllegalArgumentException("预留至少留 1 件：" + atLeast);
        }

        /** 这件物品归不归这条预留管。 */
        boolean covers(String itemId, Set<String> itemTags) {
            return new WantedItem(item).matches(itemId, itemTags);
        }

        boolean tag() {
            return item.startsWith("#");
        }
    }

    /** 存预留的地方：跟着世界走，进世界读出、每改一次写回。 */
    public interface Storage {
        /** 没进世界：什么都不存，读出来也是空的。 */
        Storage NONE = new Storage() {
            @Override public List<Reservation> load() { return List.of(); }
            @Override public void save(List<Reservation> reservations) {}
        };

        List<Reservation> load();

        void save(List<Reservation> reservations);
    }

    private final ReadsItemTags tags;
    /** 身上每种物品一共几件（主背包、快捷栏与副手）；读不到当刻的角色时为空。 */
    private final Supplier<Map<String, Integer>> carried;
    private Storage storage = Storage.NONE;
    /** 物品或标签 → 那一条预留；先写的排前面。 */
    private Map<String, Reservation> reservations = Map.of();

    public ReservedItems(ReadsItemTags tags, Supplier<Map<String, Integer>> carried) {
        this.tags = Objects.requireNonNull(tags, "tags");
        this.carried = Objects.requireNonNull(carried, "carried");
    }

    /** 进世界：换成这个世界存着的那一份。 */
    public void attach(Storage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        Map<String, Reservation> loaded = new LinkedHashMap<>();
        for (Reservation reservation : storage.load()) loaded.put(reservation.item(), reservation);
        reservations = loaded.isEmpty() ? Map.of() : loaded;
    }

    /** 退世界：上一个世界的预留不带到下一个世界。 */
    public void detach() {
        storage = Storage.NONE;
        reservations = Map.of();
    }

    /** 现在的整份预留，先写的排前面。 */
    public List<Reservation> list() {
        return List.copyOf(reservations.values());
    }

    /** 写下这几样各留够几件：同一样再写一次就换成新的件数，没提到的不变。 */
    public void set(Collection<Reservation> entries) {
        Map<String, Reservation> next = new LinkedHashMap<>(reservations);
        for (Reservation entry : entries) next.put(entry.item(), entry);
        replace(next);
    }

    /** 这几样不再留；返回真撤掉的那几条（本来就没预留的不算）。 */
    public List<Reservation> cancel(Collection<String> items) {
        Map<String, Reservation> next = new LinkedHashMap<>(reservations);
        List<Reservation> removed = new ArrayList<>();
        for (String item : items) {
            Reservation gone = next.remove(item);
            if (gone != null) removed.add(gone);
        }
        replace(next);
        return removed;
    }

    /** 全部撤掉；返回撤掉的那几条。 */
    public List<Reservation> cancelAll() {
        List<Reservation> removed = list();
        replace(new LinkedHashMap<>());
        return removed;
    }

    /**
     * 这件物品身上至少留几件：几条预留都管它时取最严的那条。点名物品的就是写下的件数；
     * 标签的按合计算——同一标签下别的几种身上已经有多少，这一种就少留多少。
     */
    @Override
    public int keep(String itemId) {
        if (reservations.isEmpty()) return 0;
        Set<String> itemTags = tags.tagsOf(itemId);
        Map<String, Integer> carriedNow = null;
        int floor = 0;
        for (Reservation reservation : reservations.values()) {
            if (!reservation.covers(itemId, itemTags)) continue;
            if (reservation.atLeast() == null) return ALL;
            int need = reservation.atLeast();
            if (reservation.tag()) {
                if (carriedNow == null) carriedNow = carried.get();
                need = Math.max(0, need - othersUnder(reservation, itemId, carriedNow));
            }
            floor = Math.max(floor, need);
        }
        return floor;
    }

    /** 这条预留管的东西身上一共几件（标签按合计）。 */
    public int carriedFor(Reservation reservation) {
        return carriedUnder(reservation, carried.get());
    }

    /**
     * 下令的目标结束了：按它的变化算动了几件预留的，从预留里扣掉，返回每条变化里有几件是预留的（与 changes 一一对应）。
     * 目标开始时身上比预留多出来的那些先算作不留的，超出那部分才是动了预留；预留的件数排在最后用，
     * 所以从后往前记到变化上。全都留着的不扣；扣到零就撤掉那一条。
     */
    public int[] usedByOrder(List<Change> changes) {
        int[] fromReserved = new int[changes.size()];
        if (reservations.isEmpty() || changes.isEmpty()) return fromReserved;
        Map<String, Integer> after = carried.get();
        Map<String, Reservation> next = new LinkedHashMap<>(reservations);
        boolean changed = false;
        for (Reservation reservation : reservations.values()) {
            if (reservation.atLeast() == null) continue;
            int out = 0;
            for (Change change : changes) out += outgoing(change, reservation);
            if (out <= 0) continue;
            int before = carriedUnder(reservation, after) + out;
            int free = Math.max(0, before - reservation.atLeast());
            int used = Math.min(reservation.atLeast(), Math.max(0, out - free));
            if (used == 0) continue;
            markFromEnd(changes, reservation, used, fromReserved);
            int left = reservation.atLeast() - used;
            if (left > 0) next.put(reservation.item(), new Reservation(reservation.item(), left));
            else next.remove(reservation.item());
            changed = true;
        }
        if (changed) replace(next);
        return fromReserved;
    }

    // 一条变化让这条预留管的东西净出了背包几件：用掉、存走、丢掉、放下算出，拿到算进（负数）。
    private int outgoing(Change change, Reservation reservation) {
        if (!reservation.covers(change.what(), tags.tagsOf(change.what()))) return 0;
        return switch (change.kind()) {
            case ITEM_CONSUMED, ITEM_STORED, ITEM_DROPPED, BLOCK_PLACED -> change.count();
            case ITEM_GAINED -> -change.count();
            default -> 0;
        };
    }

    // 预留的件数是最后用掉的：从最后一条出背包的变化往前，把动了的件数记上去。
    private void markFromEnd(List<Change> changes, Reservation reservation, int used, int[] fromReserved) {
        int left = used;
        for (int i = changes.size() - 1; i >= 0 && left > 0; i--) {
            int out = outgoing(changes.get(i), reservation);
            if (out <= 0) continue;
            int here = Math.min(out, left);
            fromReserved[i] += here;
            left -= here;
        }
    }

    private int carriedUnder(Reservation reservation, Map<String, Integer> carriedNow) {
        int total = 0;
        for (var entry : carriedNow.entrySet()) {
            if (reservation.covers(entry.getKey(), tags.tagsOf(entry.getKey()))) total += entry.getValue();
        }
        return total;
    }

    private int othersUnder(Reservation reservation, String itemId, Map<String, Integer> carriedNow) {
        int total = 0;
        for (var entry : carriedNow.entrySet()) {
            if (!entry.getKey().equals(itemId) && reservation.covers(entry.getKey(), tags.tagsOf(entry.getKey()))) {
                total += entry.getValue();
            }
        }
        return total;
    }

    private void replace(Map<String, Reservation> next) {
        reservations = next.isEmpty() ? Map.of() : next;
        storage.save(list());
    }
}
