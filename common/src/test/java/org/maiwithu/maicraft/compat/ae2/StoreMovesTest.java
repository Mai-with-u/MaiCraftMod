// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 「逐样存」离线替身测试：整格快速移动按合计少了几件记账，尾数先凑上光标再整份交回；
 * 网络放不下把光标上的点回背包再按放不下收场，样样都存不进按最后的原因收场。
 */
class StoreMovesTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final TerminalFlowFakes.Ticks TICKS = new TerminalFlowFakes.Ticks(new TerminalFlowFakes.StubPlayer());

    /** 记账口替身：收 change 与 attempts。 */
    private static final class Records implements TaskRecords {
        final List<String> changes = new ArrayList<>();
        final List<String> attempts = new ArrayList<>();

        @Override public void change(Change change) {
            changes.add(change.kind() + " " + change.what() + " x" + change.count());
        }

        @Override public void unconfirmed(Change change) {}

        @Override public void attempt(String tried, String whatHappened) {
            attempts.add(tried + "：" + whatHappened);
        }
    }

    /** 绑好替身界面的逐样存一段：要存的几样由 itemId 给出，每样存 count 件。 */
    private record Rig(TerminalFlowFakes.Menu menu, TerminalFlowFakes.Reads reads, Records records,
            StoreMoves moves) {

        static Rig rig(TerminalFlowFakes.Menu menu, TerminalFlowFakes.Reads reads, int count, String... itemIds) {
            List<BackpackStack> stacks = new ArrayList<>();
            for (String itemId : itemIds) {
                stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
            }
            Records records = new Records();
            TerminalFlowFakes.Access access = new TerminalFlowFakes.Access();
            access.opened = menu;
            TerminalSession session = new TerminalSession(access,
                    new Ae2Compat(CraftOrderingTest.NO_TERMINALS, reads, new NoGrid())) {
                @Override AbstractContainerMenu rawPage(PlayerContext player) {
                    return menu.raw;
                }
            };
            // 逐样存只在界面点开之后跑：先认下替身界面。
            session.bindMenu();
            return new Rig(menu, reads, records,
                    new StoreMoves(session, records, "ae2:terminal（测试台）", stacks, "腾背包"));
        }
    }

    private static void putInBackpack(TerminalFlowFakes.Menu menu, int slot, String itemId, int count) {
        menu.player.set(slot, switch (itemId) {
            case "minecraft:iron_ingot" -> new ItemStack(Items.IRON_INGOT, count);
            case "minecraft:gold_ingot" -> new ItemStack(Items.GOLD_INGOT, count);
            case "minecraft:diamond" -> new ItemStack(Items.DIAMOND, count);
            case "minecraft:emerald" -> new ItemStack(Items.EMERALD, count);
            default -> throw new IllegalArgumentException("替身没安排这种东西：" + itemId);
        });
    }

    /** 反复推进直到走出还在做，返回那一段的走向。 */
    private static TerminalTurn settle(StoreMoves moves, int limit) {
        TerminalTurn turn = null;
        for (int i = 0; i < limit; i++) {
            turn = moves.proceed(TICKS);
            TICKS.advance();
            if (!(turn instanceof TerminalTurn.Stay)) return turn;
        }
        return turn;
    }

    @Test
    void 整格快速移动_合计少了几件记几件() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        putInBackpack(menu, 0, "minecraft:iron_ingot", 64);
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), 64, "minecraft:iron_ingot");
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 20));
        assertNull(wrapUp.reason(), "存完了没有失败原因");
        assertEquals(64, rig.moves().stored());
        assertEquals(0, menu.carried(new ItemStack(Items.IRON_INGOT)), "背包里那种东西交给了网络");
        assertEquals(List.of("ITEM_STORED minecraft:iron_ingot x64"), rig.records().changes);
    }

    @Test
    void 尾数先凑上光标再整份交回_只存要的几件() {
        // 背包一格 8 个，只存 3 个：先整份拿上光标、右键退回 5 个，光标上正好 3 个时整份交给网络。
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.onPutBack = menu::networkTakesCursor;
        putInBackpack(menu, 0, "minecraft:iron_ingot", 8);
        Rig rig = Rig.rig(menu, reads, 3, "minecraft:iron_ingot");
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 40));
        assertNull(wrapUp.reason());
        assertEquals(3, rig.moves().stored(), "只存进要的 3 个，不是整格 8 个");
        assertEquals(5, menu.carried(new ItemStack(Items.IRON_INGOT)), "退回的 5 个还在背包里");
    }

    @Test
    void 网络放不下_背包和光标都没少_如实说存不进() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        putInBackpack(menu, 0, "minecraft:iron_ingot", 64);
        menu.jamMoves = Integer.MAX_VALUE;
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), 64, "minecraft:iron_ingot");
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 40));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("网络放不下"));
        assertEquals(0, rig.moves().stored());
        assertEquals(64, menu.carried(new ItemStack(Items.IRON_INGOT)), "东西还在背包里，没被点丢");
    }

    @Test
    void 样样都存不进_按最后一样的原因收场() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), 64, "minecraft:iron_ingot", "minecraft:gold_ingot");
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 40));
        assertEquals(Problem.Kind.NEED_ITEM, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("minecraft:gold_ingot"), "按最后一样说背包里没有了");
        assertEquals(0, rig.moves().stored());
    }

    @Test
    void 凑尾数时停不得_存完就停得() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.onPutBack = menu::networkTakesCursor;
        putInBackpack(menu, 0, "minecraft:iron_ingot", 8);
        Rig rig = Rig.rig(menu, reads, 3, "minecraft:iron_ingot");
        // 第一刻拿起整份：东西在光标上，撒手会落进流程外。
        rig.moves().proceed(TICKS);
        assertTrue(rig.moves().unsafeToStop());
        settle(rig.moves(), 40);
        assertFalse(rig.moves().unsafeToStop(), "存完交清了，照常能停");
    }

    @Test
    void 界面被合上_不用关直接结束() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), 64, "minecraft:iron_ingot");
        menu.closed = true;
        assertInstanceOf(TerminalTurn.FinishNow.class, rig.moves().proceed(TICKS));
    }

    @Test
    void 满盘连续两次快速移动无回包_提前止损并报告余下未存入件数() {
        // 背包放 4 种物品，模拟 ME 存储元件 63 种类型已满后服务端对快速移动静默不回包（UNCERTAIN）：
        // 连续两次 UNCERTAIN 且原格数量不变后立刻中止，不再点击第 3、4 样，并报出余下未存入件数。
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        putInBackpack(menu, 0, "minecraft:iron_ingot", 10);
        putInBackpack(menu, 1, "minecraft:gold_ingot", 10);
        putInBackpack(menu, 2, "minecraft:diamond", 10);
        putInBackpack(menu, 3, "minecraft:emerald", 10);
        menu.jamMoves = Integer.MAX_VALUE;
        menu.jamStatus = PendingMenuAction.Status.UNCERTAIN;
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), 10,
                "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond", "minecraft:emerald");
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 60));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, wrapUp.reason().kind());
        assertEquals(2, menu.quickMoveCalls, "连续 2 次无回包后应立即止损，不再逐格尝试剩余 2 种");
        assertTrue(wrapUp.reason().message().contains("已满/类型上限拒收"), wrapUp.reason().message());
        assertTrue(wrapUp.reason().message().contains("余下 40 件未存入"), wrapUp.reason().message());
        assertEquals(0, rig.moves().stored());
    }

    @Test
    void 偶发一次快速移动无回包后下一件成功_不误中断() {
        // 只有第 1 种物品偶发 UNCERTAIN，随后第 2、3 种正常存入：
        // 成功存入时应清零连续无回包计数，把后面两种全部存完。
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        putInBackpack(menu, 0, "minecraft:iron_ingot", 10);
        putInBackpack(menu, 1, "minecraft:gold_ingot", 10);
        putInBackpack(menu, 2, "minecraft:diamond", 10);
        menu.jamMoves = 1;
        menu.jamStatus = PendingMenuAction.Status.UNCERTAIN;
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), 10,
                "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond");
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 60));
        assertNull(wrapUp.reason(), "已有成功存入时不应误触发满盘止损");
        assertEquals(20, rig.moves().stored(), "第 2、3 种共 20 件应全部存入");
        assertEquals(3, menu.quickMoveCalls, "3 种物品都应各自尝试 1 次");
    }
}
