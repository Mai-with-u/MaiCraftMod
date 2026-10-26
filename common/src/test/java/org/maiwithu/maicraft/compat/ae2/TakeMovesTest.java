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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 「逐笔搬」离线替身测试：只看一眼看完就收场、光标上挂着别的东西停手、现货没了下不下单按许可与样板定、
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

    /** 绑好替身终端、要 8 个铁锭的逐笔搬一段。 */
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
            // 逐笔搬只在界面点开之后跑：先认下替身界面。
            session.bindMenu();
            WaitsForCraft craft = new WaitsForCraft(session.compat(), session, null);
            return new Rig(menu, reads, access, session, new TakeMoves(session, craft, lookOnly, allowCrafting));
        }
    }

    private static ItemStack iron(int count) {
        return new ItemStack(Items.IRON_INGOT, count);
    }

    private static List<Ae2TerminalMenu.StockEntry> ironInNetwork(long stored) {
        return List.of(new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.IRON_INGOT), stored, false, false));
    }

    /** 反复推进直到走出 Stay(running)，返回那一段的走向。 */
    private static TerminalTurn settle(TakeMoves moves, int limit) {
        TerminalTurn turn = null;
        for (int i = 0; i < limit; i++) {
            turn = moves.proceed(TICKS);
            TICKS.advance();
            if (!(turn instanceof TerminalTurn.Stay)
                    || !(((TerminalTurn.Stay) turn).status() instanceof ActionStatus.Running)) return turn;
        }
        return turn;
    }

    @Test
    void 只看一眼_存货记成看全了就收场_什么都不拿() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = ironInNetwork(10);
        Rig rig = Rig.rig(menu, reads, true, true);
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 200));
        assertEquals(null, wrapUp.reason(), "只看一眼没有失败原因");
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
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 10));
        assertEquals(Problem.Kind.STUCK, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("不是这次要取的"));
    }

    @Test
    void 坠落中取落地料_现货没有_不在空中下单() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of();
        Rig rig = Rig.rig(menu, reads, false, false);
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 200));
        assertEquals(Problem.Kind.NEED_ITEM, wrapUp.reason().kind());
        assertEquals("ae2:terminal（测试台） 的网络里没有minecraft:iron_ingot了", wrapUp.reason().message());
    }

    @Test
    void 现货不够_网络里有样板_去下单那几页() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of(new Ae2TerminalMenu.StockEntry(7, iron(1), 0, true, false));
        Rig rig = Rig.rig(menu, reads, false, true);
        assertInstanceOf(TerminalTurn.Order.class, settle(rig.moves(), 200), "还缺、有样板：去下单");
    }

    @Test
    void 背包放不下_不硬点_按背包放不下收场() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        for (int i = 0; i < 36; i++) menu.player.set(i, new ItemStack(Items.GOLD_BLOCK, 64));
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = ironInNetwork(64);
        Rig rig = Rig.rig(menu, reads, false, true);
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 10));
        assertEquals(Problem.Kind.INVENTORY_FULL, wrapUp.reason().kind());
        assertEquals(0, menu.carried(iron(1)), "一下都没点，没取到铁锭");
        assertEquals(36 * 64, menu.carried(new ItemStack(Items.GOLD_BLOCK)), "背包原封不动");
    }

    @Test
    void 收场把光标上的东西放进背包_确认了记账() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        menu.carry(iron(5));
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), false, true);
        TerminalTurn turn = rig.moves().clearCursor(TICKS);
        // 放下的一笔逐刻点：点出去、等同步、确认，随后光标空了这段就交回"去关界面"。
        for (int i = 0; i < 5 && turn instanceof TerminalTurn.Stay; i++) {
            turn = rig.moves().clearCursor(TICKS);
            TICKS.advance();
        }
        assertInstanceOf(TerminalTurn.CloseOnly.class, turn);
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
            assertInstanceOf(TerminalTurn.Stay.class, rig.moves().clearCursor(TICKS));
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
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 200));
        assertEquals(Problem.Kind.NEED_ITEM, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("都没有空桶"));
        assertEquals("先备一只空桶（三块铁锭）再来装", wrapUp.reason().suggestion());
    }

    @Test
    void 装流体_背包里的空桶先放进网络_一直没看到就如实说() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        menu.player.set(0, new ItemStack(Items.BUCKET, 1));
        TerminalFlowFakes.Reads reads = new TerminalFlowFakes.Reads();
        reads.linked = Optional.of(true);
        reads.stock = List.of(new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.WATER_BUCKET), 10, false, true));
        Rig rig = Rig.rig(menu, reads, false, true, "minecraft:water_bucket");
        // 等存货表稳下来、认出要的是一桶流体后，第一刻把空桶放进网络（替身里就是那一格交出去），随后等它在存货里出现，等不到如实收场。
        assertInstanceOf(TerminalTurn.Stay.class, rig.moves().proceed(TICKS));
        assertTrue(menu.player.get(0).isEmpty(), "空桶交给了网络");
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, settle(rig.moves(), 100));
        assertEquals(Problem.Kind.STUCK, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("一直没看到"));
    }

    @Test
    void 等成品时被打断回来_没等到的那一下不再等_搬运的一笔撒手() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads(), false, true);
        // 手上没有在等的下一下时被打断：照常撒手，不算没能确认，也不算停不得。
        rig.moves().afterPause();
        assertFalse(rig.moves().unsafeToStop());
        assertTrue(rig.session().settlement().unconfirmedFacts().isEmpty());
    }
}
