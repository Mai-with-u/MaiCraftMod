// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.kernel.result.Change;

/** 预留：留够几件按身上总数算、标签按合计、全都留着；下令的目标动了预留的从预留里扣，跟着世界存。 */
class ReservedItemsTest {

    private final FakeTags tags = new FakeTags();
    private final Map<String, Integer> carriedNow = new HashMap<>();
    private final ReservedItems reserved = new ReservedItems(tags, () -> Map.copyOf(carriedNow));

    /** 存预留的替身：记下最后一次写进去的那一份。 */
    private static final class Saved implements ReservedItems.Storage {
        List<ReservedItems.Reservation> stored = new ArrayList<>();

        @Override public List<ReservedItems.Reservation> load() {
            return List.copyOf(stored);
        }

        @Override public void save(List<ReservedItems.Reservation> reservations) {
            stored = new ArrayList<>(reservations);
        }
    }

    private static ReservedItems.Reservation keep(String item, Integer atLeast) {
        return new ReservedItems.Reservation(item, atLeast);
    }

    @Test
    void 点名物品留够写下的件数_没预留的不留() {
        reserved.set(List.of(keep("minecraft:oak_planks", 40)));
        assertEquals(40, reserved.keep("minecraft:oak_planks"));
        assertEquals(0, reserved.keep("minecraft:cobblestone"));
    }

    @Test
    void 不写件数就是全都留着() {
        reserved.set(List.of(keep("minecraft:diamond", null)));
        assertEquals(ItemsToKeep.ALL, reserved.keep("minecraft:diamond"));
    }

    @Test
    void 标签按合计留_别的几种身上有多少这一种就少留多少() {
        tags.put("minecraft:oak_planks", "minecraft:planks");
        tags.put("minecraft:spruce_planks", "minecraft:planks");
        reserved.set(List.of(keep("#minecraft:planks", 40)));
        carriedNow.put("minecraft:oak_planks", 35);
        carriedNow.put("minecraft:spruce_planks", 30);

        assertEquals(10, reserved.keep("minecraft:oak_planks"), "云杉已有 30，橡木只要留 10");
        assertEquals(5, reserved.keep("minecraft:spruce_planks"));
        assertEquals(65, reserved.carriedFor(reserved.list().get(0)), "身上件数按合计");
    }

    @Test
    void 几条重叠时取最严的那条() {
        tags.put("minecraft:oak_planks", "minecraft:planks");
        reserved.set(List.of(keep("#minecraft:planks", 10), keep("minecraft:oak_planks", 20)));
        assertEquals(20, reserved.keep("minecraft:oak_planks"));
    }

    @Test
    void 同一样再写一次换成新件数_撤掉的只算本来有的() {
        reserved.set(List.of(keep("minecraft:iron_ingot", 23)));
        reserved.set(List.of(keep("minecraft:iron_ingot", 9)));
        assertEquals(9, reserved.keep("minecraft:iron_ingot"));

        List<ReservedItems.Reservation> removed = reserved.cancel(List.of("minecraft:iron_ingot", "minecraft:coal"));
        assertEquals(List.of(keep("minecraft:iron_ingot", 9)), removed);
        assertTrue(reserved.list().isEmpty());
    }

    @Test
    void 和正要用的合起来_两笔加在一起留_有一笔全留就全留() {
        reserved.set(List.of(keep("minecraft:cobblestone", 64), keep("minecraft:diamond", null)));
        ItemsToKeep kept = reserved.plus(itemId -> itemId.equals("minecraft:cobblestone") ? 3 : 1);

        assertEquals(67, kept.keep("minecraft:cobblestone"));
        assertEquals(ItemsToKeep.ALL, kept.keep("minecraft:diamond"));
        assertEquals(1, kept.keep("minecraft:dirt"));
    }

    @Test
    void 下令的目标用掉的_先算多出来的_超出部分从预留里扣并记到最后几条变化上() {
        // 预留木板 40、身上 45：盖小屋放了 30 块，5 块是多出来的，25 块动了预留，预留剩 15。
        reserved.set(List.of(keep("minecraft:oak_planks", 40)));
        carriedNow.put("minecraft:oak_planks", 15);
        List<Change> changes = List.of(
                Change.of(Change.Kind.BLOCK_PLACED, "minecraft:oak_planks", 10),
                Change.of(Change.Kind.ITEM_GAINED, "minecraft:dirt", 4),
                Change.of(Change.Kind.BLOCK_PLACED, "minecraft:oak_planks", 20));

        int[] fromReserved = reserved.usedByOrder(changes);

        assertArrayEquals(new int[] {5, 0, 20}, fromReserved, "预留的排在最后用，从后往前记");
        assertEquals(List.of(keep("minecraft:oak_planks", 15)), reserved.list());
    }

    @Test
    void 扣到零就撤掉那一条_全都留着的不扣() {
        reserved.set(List.of(keep("minecraft:iron_ingot", 3), keep("minecraft:diamond", null)));
        carriedNow.put("minecraft:iron_ingot", 0);
        carriedNow.put("minecraft:diamond", 0);

        reserved.usedByOrder(List.of(Change.of(Change.Kind.ITEM_CONSUMED, "minecraft:iron_ingot", 3),
                Change.of(Change.Kind.ITEM_DROPPED, "minecraft:diamond", 2)));

        assertEquals(List.of(keep("minecraft:diamond", null)), reserved.list());
    }

    @Test
    void 没动到预留的不扣() {
        reserved.set(List.of(keep("minecraft:oak_planks", 20)));
        carriedNow.put("minecraft:oak_planks", 22);
        int[] fromReserved = reserved.usedByOrder(List.of(Change.of(Change.Kind.ITEM_CONSUMED, "minecraft:oak_planks", 2)));
        assertArrayEquals(new int[] {0}, fromReserved);
        assertEquals(20, reserved.keep("minecraft:oak_planks"));
    }

    @Test
    void 进世界读出那个世界存着的_每改一次写回_退世界清空() {
        Saved world = new Saved();
        world.stored.add(keep("minecraft:iron_ingot", 23));
        reserved.attach(world);
        assertEquals(23, reserved.keep("minecraft:iron_ingot"));

        reserved.set(List.of(keep("minecraft:coal", 16)));
        assertEquals(List.of(keep("minecraft:iron_ingot", 23), keep("minecraft:coal", 16)), world.stored);

        reserved.detach();
        assertEquals(0, reserved.keep("minecraft:iron_ingot"));
        reserved.set(List.of(keep("minecraft:coal", 1)));
        assertEquals(16, world.stored.get(1).atLeast(), "退了世界的改动不写进上一个世界");
    }

    @Test
    void 件数至少为一() {
        assertThrows(IllegalArgumentException.class, () -> keep("minecraft:coal", 0));
    }
}
