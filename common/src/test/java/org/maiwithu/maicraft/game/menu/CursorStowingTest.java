// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.menu.CursorStowing.Spot;

/** 关界面前光标上的东西放回背包：先叠上同样的，再找主背包空格，快捷栏最后；一格都装不下就不放。 */
class CursorStowingTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Spot spot(int slotId, int inventoryIndex, ItemStack current) {
        return new Spot(slotId, inventoryIndex, current, 64, true);
    }

    @Test
    void 先叠到同样的东西上() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        int chosen = CursorStowing.choose(List.of(
                spot(10, 9, ItemStack.EMPTY),
                spot(11, 10, new ItemStack(Items.COBBLESTONE, 60)),
                spot(12, 11, new ItemStack(Items.COBBLESTONE, 30))), carried);
        assertEquals(12, chosen, "60 个那格叠不下 5 个，叠到 30 个那格");
    }

    @Test
    void 没有能叠的就放主背包空格_快捷栏放最后() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(40, CursorStowing.choose(List.of(
                spot(36, 0, ItemStack.EMPTY),
                spot(40, 12, ItemStack.EMPTY)), carried));
        assertEquals(36, CursorStowing.choose(List.of(
                spot(36, 0, ItemStack.EMPTY),
                spot(40, 12, new ItemStack(Items.DIRT, 64))), carried), "主背包满了才放快捷栏");
    }

    @Test
    void 容量按物品堆叠上限和格子容量里较小的算() {
        assertEquals(30, CursorStowing.choose(List.of(
                new Spot(30, 9, ItemStack.EMPTY, 16, true)),
                new ItemStack(Items.COBBLESTONE, 5)), "格子只肯收 16 件，5 件放得下");
        assertEquals(-1, CursorStowing.choose(List.of(
                new Spot(30, 9, ItemStack.EMPTY, 16, true)), new ItemStack(Items.COBBLESTONE, 20)),
                "20 件超过格子肯收的 16 件，不放");
    }

    @Test
    void 不肯收这种东西的格子跳过() {
        assertEquals(-1, CursorStowing.choose(List.of(
                new Spot(30, 9, ItemStack.EMPTY, 64, false)), new ItemStack(Items.COBBLESTONE, 5)));
    }

    @Test
    void 一格都装不下就不放_交给原版关界面() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(-1, CursorStowing.choose(List.of(
                spot(10, 9, new ItemStack(Items.DIRT, 64)),
                spot(11, 10, new ItemStack(Items.COBBLESTONE, 62))), carried));
    }

    /** 只有一份角色侧读数时的挑选：36 格主背包在前、快捷栏在后，槽位号从 9 起连续编。 */
    private static final List<Integer> SNAPSHOT_SLOT_IDS = IntStream.range(9, 45).boxed().toList();

    /** 角色侧读数的内容：前几格按给的样子摆，其余为空。 */
    private static List<ItemStack> snapshotContents(ItemStack... firstSlots) {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            slots.add(i < firstSlots.length ? firstSlots[i] : ItemStack.EMPTY);
        }
        return slots;
    }

    private static List<ItemStack> snapshotsFullOf(ItemStack stack) {
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) slots.add(stack);
        return slots;
    }

    @Test
    void 从读数挑也先叠到同种堆上() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        // 主背包位置 0 有 30 个的同种堆，快捷栏那格（列表位置 30）是 60 个的堆：叠到装得下的 30 个那格。
        List<ItemStack> contents = snapshotContents(new ItemStack(Items.COBBLESTONE, 30));
        contents.set(30, new ItemStack(Items.COBBLESTONE, 60));
        assertEquals(9, CursorStowing.fromSnapshots(SNAPSHOT_SLOT_IDS, contents, carried),
                "60 个那格叠不下 5 个，叠到 30 个那格");
    }

    @Test
    void 从读数挑同种堆在快捷栏上不占新格照样能叠() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        List<ItemStack> contents = snapshotsFullOf(new ItemStack(Items.DIRT, 64));
        contents.set(30, new ItemStack(Items.COBBLESTONE, 30));
        assertEquals(39, CursorStowing.fromSnapshots(SNAPSHOT_SLOT_IDS, contents, carried),
                "叠上已有的同种堆不算占工具位");
    }

    @Test
    void 从读数挑只剩快捷栏空格放得下时不占快捷栏_给空() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        // 主背包 27 格全满，快捷栏 9 格全空：不占工具位，让调用方另做处置。
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            contents.add(i < 27 ? new ItemStack(Items.DIRT, 64) : ItemStack.EMPTY);
        }
        assertEquals(-1, CursorStowing.fromSnapshots(SNAPSHOT_SLOT_IDS, contents, carried));
    }

    @Test
    void 从读数挑主背包空格优先于快捷栏空格() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        // 主背包位置 0 有空格，快捷栏也全空：选主背包那格。
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            contents.add(i == 0 || i >= 27 ? ItemStack.EMPTY : new ItemStack(Items.DIRT, 64));
        }
        assertEquals(9, CursorStowing.fromSnapshots(SNAPSHOT_SLOT_IDS, contents, carried));
    }

    @Test
    void 从读数挑背包全满且没有同种堆可叠_给空() {
        ItemStack carried = new ItemStack(Items.COBBLESTONE, 5);
        assertEquals(-1, CursorStowing.fromSnapshots(SNAPSHOT_SLOT_IDS,
                snapshotsFullOf(new ItemStack(Items.DIRT, 64)), carried));
    }
}
