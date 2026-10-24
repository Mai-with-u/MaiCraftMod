// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.behavior.menu.TransferRun.Check;
import org.maiwithu.maicraft.behavior.menu.TransferRun.MoveResult;
import org.maiwithu.maicraft.behavior.menu.TransferRun.Progress;
/** 搬运执行：一笔逐刻点完、等同步、按核对式出结论；发不出去不算点过，没能确认的不重发。 */
class TransferRunTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身界面：两侧格子与光标由测试摆布，点击都发得出去；界面可以被关上。 */
    private static final class FakeMenu implements OpenedMenu {
        final List<ItemStack> container = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        ItemStack cursor = ItemStack.EMPTY;
        boolean open = true;
        boolean ready = true;
        int clicksSent;

        @Override public Optional<MenuContent.Reading> reading() {
            if (!open) return Optional.empty();
            List<Integer> ids = new ArrayList<>();
            List<SlotSnapshot> snapshots = new ArrayList<>();
            for (int i = 0; i < container.size(); i++) ids.add(i);
            for (ItemStack stack : container) snapshots.add(snapshot(stack));
            List<Integer> playerIds = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) playerIds.add(100 + i);
            for (ItemStack stack : player) playerSnapshots.add(snapshot(stack));
            return Optional.of(new MenuContent.Reading(new MenuChannel() {
                @Override public boolean stillOpen() { return open; }
                @Override public boolean cursorCarrying() { return !cursor.isEmpty(); }
                @Override public SlotSnapshot cursor() { return cursor.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(cursor); }
                @Override public boolean click(int slot, int button) { return ready; }
                @Override public boolean quickMove(int slot) { return ready; }
                @Override public boolean selectTradeOffer(int offerIndex) { return ready; }
                @Override public void requestClose() {}
            }, new MenuSlots() {
                @Override public String menuTypeId() { return "minecraft:chest"; }
                @Override public int slotCount() { return container.size() + player.size(); }
                @Override public boolean playerBacked(int slot) { return slot >= 100; }
                @Override public int playerInventoryIndex(int slot) { return slot - 100; }
            }, List.copyOf(ids), List.copyOf(playerIds), List.copyOf(playerIds), snapshots, playerSnapshots));
        }

        @Override public boolean busy() { return false; }

        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }

        @Override public SlotSnapshot cursor() {
            return cursor.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(cursor);
        }

        @Override public boolean quickMove(int slotId) {
            clicksSent++;
            return ready;
        }

        @Override public boolean click(int slotId, int button) {
            clicksSent++;
            return ready;
        }

        @Override public void noteCursorTakenFrom(int slotId) {
        }

        @Override public Action closing() {
            throw new UnsupportedOperationException("替身不关界面");
        }

        @Override public void abandon() {
        }

        private static SlotSnapshot snapshot(ItemStack item) {
            return item.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(item);
        }
    }

    /** 推进一刻并取结论；这一刻没出结论时给空。 */
    private static Optional<MoveResult> settleTo(TransferRun run) {
        Progress progress = run.tick();
        return progress instanceof Progress.Settled settled ? Optional.of(settled.result()) : Optional.empty();
    }

    @Test
    void 整堆搬进背包等背包侧多出来才算结清() {
        FakeMenu menu = new FakeMenu();
        menu.container.add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) menu.player.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(new MenuClick(0, true, 0, false)),
                new Check.IntoBackpack(snapshot -> !snapshot.isEmpty()
                        && snapshot.stack().is(Items.COBBLESTONE), 64), "从箱子拿圆石"));
        assertEquals(Optional.empty(), settleTo(run), "点击发出去了，背包侧还没长出来：还没到结论");
        assertEquals(1, menu.clicksSent);
        // 背包侧多了 64 个：结清，报实际搬动的件数。
        menu.player.set(5, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(new MoveResult.Confirmed(64), settleTo(run).orElseThrow());
    }

    @Test
    void 发不出去的留在队头不算点过() {
        FakeMenu menu = new FakeMenu();
        menu.container.add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) menu.player.add(ItemStack.EMPTY);
        menu.ready = false;
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(new MenuClick(0, true, 0, false)),
                new Check.IntoBackpack(snapshot -> snapshot.stack().is(Items.COBBLESTONE), 64), "从箱子拿圆石"));
        assertEquals(Optional.empty(), settleTo(run), "本刻没发出去：还没到结论");
        assertEquals(1, menu.clicksSent, "发不出去也收了点击记录，但不算点过");
        menu.ready = true;
        assertEquals(Progress.Advanced.ADVANCED, run.tick());
        assertEquals(2, menu.clicksSent);
    }

    @Test
    void 纹丝不动过宽限报没动() {
        FakeMenu menu = new FakeMenu();
        menu.container.add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) menu.player.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(new MenuClick(0, true, 0, false)),
                new Check.IntoBackpack(snapshot -> snapshot.stack().is(Items.COBBLESTONE), 64), "从箱子拿圆石"));
        settleTo(run);
        for (int i = 0; i < 5; i++) assertEquals(Optional.empty(), settleTo(run), "宽限内还没到结论");
        assertEquals(MoveResult.NotMoved.NOT_MOVED, settleTo(run).orElseThrow());
        assertTrue(run.idle(), "出过结论了：可以提交下一笔");
    }

    @Test
    void 光标上的东西没放下来报对不上() {
        FakeMenu menu = new FakeMenu();
        menu.container.add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) menu.player.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(new MenuClick(0, false, 0, true)),
                new Check.IntoBackpack(snapshot -> snapshot.stack().is(Items.COBBLESTONE), 64), "从箱子拿圆石"));
        settleTo(run);
        // 点完之后光标上还拿着整堆（放不进背包）：宽限到点承认对不上，不凑数。
        menu.cursor = new ItemStack(Items.COBBLESTONE, 64);
        for (int i = 0; i < 5; i++) assertEquals(Optional.empty(), settleTo(run), "宽限内还没到结论");
        assertEquals(MoveResult.Diverged.DIVERGED, settleTo(run).orElseThrow());
    }

    @Test
    void 界面中途没了报点出去几下() {
        FakeMenu menu = new FakeMenu();
        menu.container.add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) menu.player.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(
                        new MenuClick(0, false, 0, true), new MenuClick(100, false, 1, false), new MenuClick(0, false, 0, false)),
                new Check.IntoBackpack(snapshot -> snapshot.stack().is(Items.COBBLESTONE), 5), "从箱子拿圆石"));
        assertEquals(Progress.Advanced.ADVANCED, run.tick());
        assertEquals(Progress.Advanced.ADVANCED, run.tick());
        menu.open = false;
        assertEquals(new MoveResult.MenuGone(2), settleTo(run).orElseThrow());
    }

    @Test
    void 普通槽要源格恰好减目标格恰好增() {
        FakeMenu menu = new FakeMenu();
        menu.player.add(new ItemStack(Items.COBBLESTONE, 64));
        menu.container.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(new MenuClick(100, false, 0, false)),
                new Check.Normal(100, 0, 64), "把圆石放进箱子"));
        settleTo(run);
        // 源格搬空、目标格恰好多了 64：结清。
        menu.player.set(0, ItemStack.EMPTY);
        menu.container.set(0, new ItemStack(Items.COBBLESTONE, 64));
        assertEquals(new MoveResult.Confirmed(64), settleTo(run).orElseThrow());
    }

    @Test
    void 放下光标按光标空掉与目标格增多结算() {
        FakeMenu menu = new FakeMenu();
        menu.cursor = new ItemStack(Items.COBBLESTONE, 8);
        menu.container.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(new MenuClick(0, false, 0, false)),
                new Check.PutDown(0), "把光标上的圆石放进箱子"));
        assertEquals(Progress.Advanced.ADVANCED, run.tick());
        menu.cursor = ItemStack.EMPTY;
        menu.container.set(0, new ItemStack(Items.COBBLESTONE, 8));
        assertEquals(new MoveResult.Confirmed(8), settleTo(run).orElseThrow());
    }

    @Test
    void 没能确认的绝不自己重发() {
        FakeMenu menu = new FakeMenu();
        menu.container.add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) menu.player.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(new MenuClick(0, true, 0, false)),
                new Check.IntoBackpack(snapshot -> snapshot.stack().is(Items.COBBLESTONE), 64), "从箱子拿圆石"));
        settleTo(run);
        for (int i = 0; i < 5; i++) settleTo(run);
        assertEquals(MoveResult.NotMoved.NOT_MOVED, settleTo(run).orElseThrow());
        int sent = menu.clicksSent;
        assertTrue(run.idle());
        assertThrows(IllegalStateException.class, run::tick, "没在跑的一笔不能推进");
        assertEquals(sent, menu.clicksSent, "跑搬运的不会自己再点：重试是调用方显式再提交");
    }

    @Test
    void 一笔没点完光标上拿着东西停不得() {
        FakeMenu menu = new FakeMenu();
        menu.container.add(new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 36; i++) menu.player.add(ItemStack.EMPTY);
        TransferRun run = new TransferRun(menu);
        run.begin(new TransferRun.Move(List.of(
                        new MenuClick(0, false, 0, true), new MenuClick(100, false, 1, false)),
                new Check.IntoBackpack(snapshot -> snapshot.stack().is(Items.COBBLESTONE), 1), "从箱子拿圆石"));
        assertEquals(Progress.Advanced.ADVANCED, run.tick());
        menu.cursor = new ItemStack(Items.COBBLESTONE, 64);
        assertTrue(run.unsafeToStop(), "第一下把整堆拿到了光标上，半途撒手会把尾数交给关界面的流程");
        menu.cursor = ItemStack.EMPTY;
        assertFalse(run.unsafeToStop());
    }
}
