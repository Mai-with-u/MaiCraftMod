// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 终端取货差异点的离线替身测试：只看一眼看完就收场、光标上挂着别的东西停手、现货没了下不下单按许可与样板定、
 * 背包放不下不硬点；收场把光标上的东西放进背包记账，装不下放回网络；装流体先备空桶。
 * 整组取与取一件那两下经模组协议发、等游戏确认，替身发不出去，覆盖靠实机（与原黑盒口径一致）。
 */
class TakeMovesTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final TerminalFlowFakes.Ticks TICKS = new TerminalFlowFakes.Ticks(new TerminalFlowFakes.StubPlayer());

    /** 绑好替身终端、要 8 个铁锭的取货差异点。 */
    private record Rig(TerminalFlowFakes.Menu menu, TerminalFlowFakes.Reads reads, TerminalFlowFakes.Access access,
            TakeSession session, TakeMoves moves) {

        static Rig rig(TerminalFlowFakes.Menu menu, TerminalFlowFakes.Reads reads, boolean lookOnly,
                boolean allowCrafting) {
            return rig(menu, reads, lookOnly, allowCrafting, "minecraft:iron_ingot");
        }

        static Rig rig(TerminalFlowFakes.Menu menu, TerminalFlowFakes.Reads reads, boolean lookOnly,
                boolean allowCrafting, String wantedItemId) {
            // 读写端与界面会话认同一份原界面：读存货、读光标都对着它。
            reads.sharePage(menu.raw);
            TerminalFlowFakes.Access access = new TerminalFlowFakes.Access();
            access.opened = menu;
            TakeSession session = new TakeSession(access, new Ae2Compat(CraftOrderingTest.NO_TERMINALS,
                    reads, new NoGrid()), TerminalFlowFakes.services(TerminalFlowFakes.StubPlayer::new),
                    new ItemRequest(WantedItem.ofItem(wantedItemId), 8, "工具准备")) {
                @Override AbstractContainerMenu rawPage(PlayerContext player) {
                    return menu.raw;
                }
            };
            // 差异点只在界面点开之后跑：先认下替身界面。
            session.bindMenu();
            WaitsForCraft craft = new WaitsForCraft(session.compat(), session, null);
            return new Rig(menu, reads, access, session, new TakeMoves(session, craft, lookOnly, allowCrafting));
        }

        /** 反复问下一步直到这一样盘完（放下收场）或交出有进展的一步，返回那个答案。 */
        MenuTakeRound.Step settle() {
            MenuTakeRound.Step step = null;
            for (int i = 0; i < 200; i++) {
                Optional<MenuContent.Reading> reading = session().reading();
                if (reading.isEmpty()) return step;
                step = moves().next(reading.get(), TICKS);
                TICKS.advance();
                if (step == MenuTakeRound.Step.giveUp()) return step;
                if (step instanceof MenuTakeRound.Step.Wait(ActionStatus status)
                        && status instanceof ActionStatus.Running(boolean progressed) && progressed) {
                    return step;
                }
            }
            return step;
        }

        /** 反复放光标若干刻（点出去、等同步、确认各占一刻）。 */
        void clearCursor(int ticks) {
            for (int i = 0; i < ticks; i++) {
                moves().beforeClose(TICKS);
                TICKS.advance();
            }
        }
    }

    private static ItemStack iron(int count) {
        return new ItemStack(Items.IRON_INGOT, count);
    }

    private static List<Ae2TerminalMenu.StockEntry> ironInNetwork(long stored) {
        return List.of(new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.IRON_INGOT), stored, false, false));
    }

    @Test
    void 只看一眼_存货记成看全了就收场_什么都不拿() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = ironInNetwork(10);
        Rig rig = Rig.rig(menu, reads, true, true);
        assertInstanceOf(MenuTakeRound.Step.GiveUp.class, rig.settle());
        assertEquals(null, rig.moves().wrapReason(), "只看一眼没有失败原因");
        assertEquals(List.of("truex1看全了"), rig.access().remembered);
        assertEquals(0, menu.carried(iron(1)), "看完什么都不拿");
    }

    @Test
    void 光标上挂着不是这次要取的东西_先停手() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = ironInNetwork(10);
        menu.carry(new ItemStack(Items.GOLD_INGOT, 4));
        Rig rig = Rig.rig(menu, reads, false, true);
        assertInstanceOf(MenuTakeRound.Step.GiveUp.class, rig.settle());
        assertEquals(Problem.Kind.STUCK, rig.moves().wrapReason().kind());
        assertTrue(rig.moves().wrapReason().message().contains("不是这次要取的"));
    }

    @Test
    void 坠落中取落地料_现货没有_不在空中下单() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of();
        Rig rig = Rig.rig(menu, reads, false, false);
        assertInstanceOf(MenuTakeRound.Step.GiveUp.class, rig.settle());
        assertEquals(Problem.Kind.NEED_ITEM, rig.moves().wrapReason().kind());
        assertEquals("ae2:terminal（测试台） 的网络里没有minecraft:iron_ingot了", rig.moves().wrapReason().message());
    }

    @Test
    void 现货不够_网络里有样板_交给下单等成品的一段() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of(new Ae2TerminalMenu.StockEntry(7, iron(1), 0, true, false));
        Rig rig = Rig.rig(menu, reads, false, true);
        // 还缺、有样板：等存货表稳下来后交给下单那边，答案是把状态原样交回（去下单那几页是下一步的事）。
        MenuTakeRound.Step step = rig.settle();
        assertInstanceOf(MenuTakeRound.Step.Wait.class, step);
        assertInstanceOf(ActionStatus.Running.class, ((MenuTakeRound.Step.Wait) step).status());
        assertTrue(((ActionStatus.Running) ((MenuTakeRound.Step.Wait) step).status()).progressed(),
                "交下去算有进展，不算原地打转");
    }

    @Test
    void 背包放不下_不硬点_按背包放不下收场() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        for (int i = 0; i < 36; i++) menu.player.set(i, new ItemStack(Items.GOLD_BLOCK, 64));
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = ironInNetwork(64);
        Rig rig = Rig.rig(menu, reads, false, true);
        assertInstanceOf(MenuTakeRound.Step.GiveUp.class, rig.settle());
        assertEquals(Problem.Kind.INVENTORY_FULL, rig.moves().wrapReason().kind());
        assertEquals(0, menu.carried(iron(1)), "一下都没点，没取到铁锭");
        assertEquals(36 * 64, menu.carried(new ItemStack(Items.GOLD_BLOCK)), "背包原封不动");
    }

    @Test
    void 收场把光标上的东西放进背包_确认了记账() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        menu.carry(iron(5));
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), false, true);
        // 放下的一笔逐刻点：点出去、等同步、确认，随后光标空了这一步就做完（去关界面）。
        rig.clearCursor(5);
        assertEquals(ActionStatus.done(), rig.moves().beforeClose(TICKS), "光标放好了，这一步做完");
        assertEquals(5, menu.carried(iron(1)), "光标上的 5 个铁锭放进了背包");
        Map<String, Integer> confirmed = rig.session().settlement().confirmedByItem();
        assertEquals(5, confirmed.get("minecraft:iron_ingot"), "只记确认过的量");
    }

    @Test
    void 收场背包放不下_放回网络_点不出去时等下一刻() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        for (int i = 0; i < 36; i++) menu.player.set(i, new ItemStack(Items.GOLD_BLOCK, 64));
        menu.carry(iron(5));
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), false, true);
        // 模组协议的操作发不出去：放回网络的那一下一直等，不冒充放好了。
        for (int i = 0; i < 5; i++) {
            assertInstanceOf(ActionStatus.Running.class, rig.moves().beforeClose(TICKS));
            TICKS.advance();
        }
        assertFalse(rig.moves().unsafeToStop(), "没点出去就不算有一笔在等确认");
    }

    @Test
    void 装流体_网络里和背包里都没有空桶_说清先备一只() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of(new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.WATER_BUCKET), 10, false, true));
        Rig rig = Rig.rig(menu, reads, false, true, "minecraft:water_bucket");
        assertInstanceOf(MenuTakeRound.Step.GiveUp.class, rig.settle());
        assertEquals(Problem.Kind.NEED_ITEM, rig.moves().wrapReason().kind());
        assertTrue(rig.moves().wrapReason().message().contains("都没有空桶"));
        assertEquals("先备一只空桶（三块铁锭）再来装", rig.moves().wrapReason().suggestion());
    }

    @Test
    void 装流体_背包里的空桶先放进网络_一直没看到就如实说() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        menu.player.set(0, new ItemStack(Items.BUCKET, 1));
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of(new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.WATER_BUCKET), 10, false, true));
        Rig rig = Rig.rig(menu, reads, false, true, "minecraft:water_bucket");
        // 第一刻就把空桶放进网络（替身里就是那一格交出去），随后等它在存货里出现，等不到如实收场。
        MenuTakeRound.Step offered = rig.moves().next(rig.session().reading().orElseThrow(), TICKS);
        assertInstanceOf(MenuTakeRound.Step.Wait.class, offered);
        assertTrue(menu.player.get(0).isEmpty(), "空桶交给了网络");
        TICKS.advance();
        assertInstanceOf(MenuTakeRound.Step.GiveUp.class, rig.settle());
        assertEquals(Problem.Kind.STUCK, rig.moves().wrapReason().kind());
        assertTrue(rig.moves().wrapReason().message().contains("一直没看到"));
    }

    @Test
    void 手上没有在等的下一下时_不算停不得_也没有没能确认的账() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), false, true);
        assertFalse(rig.moves().unsafeToStop());
        assertTrue(rig.session().settlement().unconfirmedFacts().isEmpty());
    }
}
