// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 取货一轮：按要货单逐样盘、取不动的跳过继续、全盘完才关界面、关前交收场记忆；
 * 点出去了却没能确认的交互一句一条记下，界面中途没了也照实交回。
 */
class MenuTakeRoundTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 替身箱子：容器侧 27 格（槽位号 0–26），角色侧 36 格（27–62）；可以摆"点得出去但搬不动"的 jam。 */
    private static final class FakeBox implements OpenedMenu {
        final List<ItemStack> box = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        private ItemStack cursor = ItemStack.EMPTY;
        /** 点得出去但东西原地不动的那种：真实游戏里背包塞满、服务端收下点击却搬不动就是这样。 */
        int jamMoves;
        boolean closed;

        FakeBox() {
            for (int i = 0; i < 27; i++) box.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> boxIds = new ArrayList<>();
            List<SlotSnapshot> boxSnapshots = new ArrayList<>();
            for (int i = 0; i < box.size(); i++) {
                boxIds.add(i);
                boxSnapshots.add(snapshot(box.get(i)));
            }
            List<Integer> playerIds = new ArrayList<>();
            List<Integer> playerIndices = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                playerIds.add(27 + i);
                playerIndices.add(i);
                playerSnapshots.add(snapshot(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(CHANNEL, SLOTS, boxIds, playerIds, playerIndices,
                    boxSnapshots, playerSnapshots));
        }

        private static SlotSnapshot snapshot(ItemStack item) {
            return item.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(item);
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return cursor.isEmpty(); }
        @Override public SlotSnapshot cursor() { return cursor.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(cursor); }

        // 快速移动：点得出去但 jam 着时收下点击、东西不动；发得出去才把这一格整堆挪进角色背包的第一个空格。
        @Override public boolean quickMove(int slotId) {
            if (jamMoves > 0) {
                jamMoves--;
                return true;
            }
            ItemStack moving = box.get(slotId);
            for (int i = 0; i < player.size(); i++) {
                if (player.get(i).isEmpty()) {
                    player.set(i, moving.copy());
                    box.set(slotId, ItemStack.EMPTY);
                    break;
                }
            }
            return true;
        }

        // 普通点击按原版规矩结算：空光标左键整份拿起、右键拿半堆；拿着东西左键整份放进（同种并堆，
        // 别的交换）、右键放一个，放不进就不动。
        @Override public boolean click(int slotId, int button) {
            List<ItemStack> side = slotId < 27 ? box : player;
            int index = slotId < 27 ? slotId : slotId - 27;
            ItemStack slot = side.get(index);
            if (cursor.isEmpty()) {
                if (slot.isEmpty()) return true;
                if (button == 0) {
                    cursor = slot;
                    side.set(index, ItemStack.EMPTY);
                } else {
                    int taken = (slot.getCount() + 1) / 2;
                    cursor = slot.copyWithCount(taken);
                    side.set(index, slot.copyWithCount(slot.getCount() - taken));
                }
            } else if (button == 0) {
                if (slot.isEmpty()) {
                    side.set(index, cursor);
                    cursor = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(slot, cursor)) {
                    int fitting = Math.min(cursor.getCount(), slot.getMaxStackSize() - slot.getCount());
                    slot.grow(fitting);
                    cursor.shrink(fitting);
                } else {
                    side.set(index, cursor);
                    cursor = slot;
                }
            } else if (slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, cursor)
                    && slot.getCount() < slot.getMaxStackSize()) {
                if (slot.isEmpty()) {
                    side.set(index, cursor.copyWithCount(1));
                } else {
                    slot.grow(1);
                }
                cursor.shrink(1);
            }
            if (cursor.isEmpty()) cursor = ItemStack.EMPTY;
            return true;
        }

        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "关上替身箱子"; }
            };
        }

        @Override public void abandon() {
            closed = true;
        }

        int carried(ItemStack kind) {
            return player.stream().filter(item -> ItemStack.isSameItem(item, kind)).mapToInt(ItemStack::getCount).sum();
        }
    }

    private static final MenuChannel CHANNEL = new MenuChannel() {
        @Override public boolean stillOpen() { return true; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean click(int slot, int button) { return true; }
        @Override public boolean quickMove(int slot) { return true; }
        @Override public boolean selectTradeOffer(int offerIndex) { return true; }
        @Override public void requestClose() {}
    };

    private static final MenuSlots SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "minecraft:generic_9x3"; }
        @Override public int slotCount() { return 63; }
        @Override public boolean playerBacked(int slot) { return slot >= 27; }
        @Override public int playerInventoryIndex(int slot) { return slot - 27; }
    };

    /** 只有刻号的推进现场。 */
    private static final class Ticks implements TickContext {
        private long tick = 1000;

        @Override public long gameTick() { return tick; }

        @Override public PlayerContext player() {
            throw new IllegalStateException("替身一轮取货不应碰到角色对象");
        }

        void advance() {
            tick++;
        }
    }

    /** 要货单里的一样：按物品认，缺几件按要的数算；收场记忆与收场结论由测试接住。 */
    private static final class TestWant implements MenuTakeRound.Want {
        private final String label;
        private final Predicate<SlotSnapshot> matcher;
        private final int wanted;
        private int startCarried;
        boolean gaveUpNoted;

        TestWant(String label, Item item, int wanted) {
            this.label = label;
            this.matcher = snapshot -> !snapshot.isEmpty() && snapshot.stack().is(item);
            this.wanted = wanted;
        }

        @Override public String label() { return label; }

        @Override public Predicate<SlotSnapshot> matcher() { return matcher; }

        // 缺几件按身上真的多了多少算，与来源的口径一致。
        @Override public void firstReading(MenuContent.Reading reading) {
            startCarried = carriedNow(reading);
        }

        @Override public int stillNeeded(MenuContent.Reading reading) {
            return Math.max(0, wanted - (carriedNow(reading) - startCarried));
        }

        private int carriedNow(MenuContent.Reading reading) {
            int total = 0;
            for (SlotSnapshot snapshot : reading.playerSnapshots()) {
                if (!snapshot.isEmpty() && matcher.test(snapshot)) total += snapshot.count();
            }
            return total;
        }

        @Override public void gaveUp() {
            gaveUpNoted = true;
        }

        // 对不上账的一笔之后照箱子收场：搬走多少说不清，交给引擎清点。
        @Override public boolean settleAfterDiverge() {
            return true;
        }
    }

    /** 收场记忆与收场结论的接住处：记录关箱前最后的读数，收场结论固定为完成；可选用例给接缝塞剧本。 */
    private static final class TestEndings implements MenuTakeRound.Endings {
        Optional<MenuContent.Reading> remembered = Optional.empty();
        boolean finishedAfterClosed;
        boolean finishedAfterMenuGone;
        boolean tookAnythingAtFinish;
        /** 关界面前那一步的剧本：先回这么多次"还在做"，再回做完。 */
        int beforeCloseWaits;
        Optional<MenuContent.Reading> beforeCloseReading = Optional.empty();
        /** 全盘完不关界面直接交回。 */
        boolean handBack;

        @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
            remembered = lastReading;
        }

        @Override public ActionStatus beforeClose(TickContext tick, MenuContent.Reading reading) {
            beforeCloseReading = Optional.of(reading);
            if (beforeCloseWaits > 0) {
                beforeCloseWaits--;
                return ActionStatus.running();
            }
            return ActionStatus.done();
        }

        @Override public boolean finishWithoutClosing() {
            return handBack;
        }

        @Override public ActionStatus finishAfterClosed(boolean tookAnything) {
            finishedAfterClosed = true;
            tookAnythingAtFinish = tookAnything;
            return ActionStatus.done();
        }

        @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) {
            finishedAfterMenuGone = true;
            tookAnythingAtFinish = tookAnything;
            return ActionStatus.done();
        }
    }

    /** 一轮取货开好：替身箱子、要货单与收场接住处都在测试手里。 */
    private record Round(FakeBox box, MenuTakeRound round, TestEndings endings, Ticks ticks) {
    }

    private static Round roundOf(FakeBox box, List<MenuTakeRound.Want> wants) {
        TestEndings endings = new TestEndings();
        return new Round(box, new MenuTakeRound(box, wants, endings), endings, new Ticks());
    }

    /** 推进到收场为止：给足刻数，一轮取货几十刻内该完。 */
    private static ActionStatus runToFinish(Round round) {
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 400 && status instanceof ActionStatus.Running; i++) {
            status = round.round().tick(round.ticks(), round.box().reading());
            round.ticks().advance();
        }
        return status;
    }

    @Test
    void 取不动的那样跳过_别样照拿_最后才关箱() {
        // 铁锭那几下周转不动（点出去了身上一直没多），煤照拿：铁锭这一趟先放下，关箱前两样都有了结论。
        FakeBox box = new FakeBox();
        box.box.set(2, new ItemStack(Items.IRON_INGOT, 5));
        box.box.set(5, new ItemStack(Items.COAL, 4));
        box.jamMoves = 1;
        TestWant iron = new TestWant("从箱子拿铁锭", Items.IRON_INGOT, 5);
        TestWant coal = new TestWant("从箱子拿煤", Items.COAL, 3);
        Round round = roundOf(box, List.of(iron, coal));
        ActionStatus status = runToFinish(round);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(0, box.carried(new ItemStack(Items.IRON_INGOT)), "取不动的铁锭一件没拿");
        assertEquals(3, box.carried(new ItemStack(Items.COAL)), "煤照拿");
        assertTrue(box.closed, "两样都有了结论才关箱");
        List<String> facts = round.round().unconfirmedFacts();
        assertEquals(1, facts.size(), "只有铁锭那一笔没能确认");
        assertTrue(facts.get(0).contains("从箱子拿铁锭"), facts.get(0));
        assertTrue(facts.get(0).contains("搬 5 件"), facts.get(0));
        assertTrue(facts.get(0).contains("没能确认结果"), facts.get(0));
        assertTrue(round.endings().finishedAfterClosed, "关上之后收场");
        assertTrue(round.endings().tookAnythingAtFinish, "煤拿到了，整场不算空手");
    }

    @Test
    void 全都取不动也照常关箱_一格都没点() {
        // 背包那一侧塞满：两样都盘不出能点的笔，一件不点、不记没能确认，箱子照常关上。
        FakeBox box = new FakeBox();
        box.box.set(2, new ItemStack(Items.IRON_INGOT, 5));
        box.box.set(5, new ItemStack(Items.COAL, 4));
        for (int i = 0; i < 36; i++) box.player.set(i, new ItemStack(Items.GOLD_BLOCK, 64));
        TestWant iron = new TestWant("从箱子拿铁锭", Items.IRON_INGOT, 5);
        TestWant coal = new TestWant("从箱子拿煤", Items.COAL, 3);
        Round round = roundOf(box, List.of(iron, coal));
        ActionStatus status = runToFinish(round);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(0, box.carried(new ItemStack(Items.IRON_INGOT)));
        assertEquals(0, box.carried(new ItemStack(Items.COAL)));
        assertTrue(box.closed, "全都取不动也照常关箱");
        assertTrue(round.round().unconfirmedFacts().isEmpty(), "一下都没点出去，谈不上没能确认");
        assertTrue(iron.gaveUpNoted, "取不动的那样有了交代理由的机会");
        assertTrue(coal.gaveUpNoted);
        assertTrue(round.endings().finishedAfterClosed);
    }

    @Test
    void 关箱前把此刻的界面内容交给收场记忆() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 6));
        Round round = roundOf(box, List.of(new TestWant("从箱子拿铁锭", Items.IRON_INGOT, 5)));
        assertInstanceOf(ActionStatus.Done.class, runToFinish(round));

        assertTrue(round.endings().remembered.isPresent(), "关箱前交了读数");
        int left = 0;
        for (SlotSnapshot snapshot : round.endings().remembered.get().containerSnapshots()) {
            if (!snapshot.isEmpty() && snapshot.stack().is(Items.IRON_INGOT)) left += snapshot.count();
        }
        assertEquals(1, left, "记忆里是搬完之后箱里剩的数");
    }

    @Test
    void 点到一半界面没了_点出去的那几下交回没能确认_照界面没了收场() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        box.jamMoves = Integer.MAX_VALUE;
        Round round = roundOf(box, List.of(new TestWant("从箱子拿铁锭", Items.IRON_INGOT, 64)));
        // 第一刻盘下一笔，第二刻点出去一下，随后界面被合上（被打断或服务端关的）。
        round.round().tick(round.ticks(), box.reading());
        round.ticks().advance();
        round.round().tick(round.ticks(), box.reading());
        round.ticks().advance();
        box.closed = true;
        ActionStatus status = runToFinish(round);

        assertInstanceOf(ActionStatus.Done.class, status);
        List<String> facts = round.round().unconfirmedFacts();
        assertEquals(1, facts.size());
        assertTrue(facts.get(0).contains("点了 1 下"), facts.get(0));
        assertTrue(facts.get(0).contains("界面关上"), facts.get(0));
        assertTrue(facts.get(0).contains("没能等到结算结果"), facts.get(0));
        assertTrue(round.endings().finishedAfterMenuGone, "界面没了按界面没了收场");
        assertTrue(round.endings().remembered.isEmpty(), "界面没了，收场记忆拿到空读数");
    }

    @Test
    void 收尾时手上还有一笔_点出去的那几下交回_界面请游戏关上() {
        FakeBox box = new FakeBox();
        box.box.set(4, new ItemStack(Items.IRON_INGOT, 64));
        box.jamMoves = Integer.MAX_VALUE;
        Round round = roundOf(box, List.of(new TestWant("从箱子拿铁锭", Items.IRON_INGOT, 64)));
        round.round().tick(round.ticks(), box.reading());
        round.ticks().advance();
        round.round().tick(round.ticks(), box.reading());
        round.ticks().advance();
        // 收尾：还没收场的先撒手，点出去的一下交回没能确认，界面请游戏关上。
        round.round().close(true);

        List<String> facts = round.round().unconfirmedFacts();
        assertEquals(1, facts.size());
        assertTrue(facts.get(0).contains("收尾时"), facts.get(0));
        assertTrue(box.closed, "界面被请游戏关上");
    }
}
