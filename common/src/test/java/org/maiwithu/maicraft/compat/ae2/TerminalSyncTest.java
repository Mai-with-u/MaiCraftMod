// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 「打开与核对」：走到点开把打开动作的原因原样交回；核对是这台的界面、等网络读数同步齐才放行；
 * 空网络多等几刻才下结论，没连上照抄界面上的原因收场并记下读数。取货等存货表、存东西只等连接状态。
 */
class TerminalSyncTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final TerminalFlowFakes.Ticks TICKS = new TerminalFlowFakes.Ticks(new TerminalFlowFakes.StubPlayer());

    /** 绑好替身界面与读数的会话：原界面换成替身的那份，读存货与连接状态都对它。 */
    private record Rig(TerminalFlowFakes.Menu menu, TerminalFlowFakes.Reads reads, TerminalFlowFakes.Access access,
            TerminalSession session) {

        static Rig rig(TerminalFlowFakes.Menu menu, TerminalFlowFakes.Reads reads) {
            TerminalFlowFakes.Access access = new TerminalFlowFakes.Access();
            access.opened = menu;
            TerminalSession session = new TerminalSession(access, new Ae2Compat(CraftOrderingTest.NO_TERMINALS,
                    reads, new NoGrid())) {
                @Override AbstractContainerMenu rawPage(PlayerContext player) {
                    return reads.page();
                }
            };
            return new Rig(menu, reads, access, session);
        }
    }

    private static List<Ae2TerminalMenu.StockEntry> iron(long stored) {
        return List.of(new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.IRON_INGOT), stored, false, false));
    }

    @Test
    void 点不开_打开动作的原因原样交回() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        rig.access().openingStatus = ActionStatus.progressed();
        TerminalSync sync = TerminalSync.waitingForStock(rig.session());
        TerminalTurn.Stay progressing = assertInstanceOf(TerminalTurn.Stay.class, sync.proceed(TICKS));
        assertInstanceOf(ActionStatus.Running.class, progressing.status());
        rig.access().openingStatus = ActionStatus.failed(Problem.of(Problem.Kind.NOT_FOUND, "走不到终端", null));
        TerminalTurn.Stay stay = assertInstanceOf(TerminalTurn.Stay.class, sync.proceed(TICKS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, stay.status());
        assertEquals("走不到终端", failed.problem().message());
    }

    @Test
    void 界面刚打开就被关上_不用放光标直接收场() {
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        Rig rig = Rig.rig(menu, new TerminalFlowFakes.Reads());
        rig.access().openingStatus = ActionStatus.done();
        TerminalSync sync = TerminalSync.waitingForStock(rig.session());
        sync.proceed(TICKS);
        menu.closed = true;
        TerminalTurn.FinishNow finish = assertInstanceOf(TerminalTurn.FinishNow.class, sync.proceed(TICKS));
        assertEquals(Problem.Kind.TARGET_GONE, finish.reason().kind());
    }

    @Test
    void 点开的不是这台的界面_收场() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        rig.access().openingStatus = ActionStatus.done();
        rig.access().recognized = false;
        TerminalSync sync = TerminalSync.waitingForStock(rig.session());
        sync.proceed(TICKS);
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, sync.proceed(TICKS));
        assertEquals(Problem.Kind.TARGET_GONE, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("不是 ae2:terminal（测试台） 的界面"));
    }

    @Test
    void 存货与连接同步齐_记成没看全_放行去取货() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        rig.reads().linked = Optional.of(true);
        rig.reads().stock = iron(10);
        rig.access().openingStatus = ActionStatus.done();
        TerminalSync sync = TerminalSync.waitingForStock(rig.session());
        sync.proceed(TICKS);
        assertInstanceOf(TerminalTurn.Synced.class, sync.proceed(TICKS));
        assertEquals(List.of("truex1没看全"), rig.access().remembered,
                "刚同步过来的多半只是第一包：先按没看全记，免得把没到的当成没有");
    }

    @Test
    void 没连上网络_照抄界面上的原因_取不了() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        rig.reads().linked = Optional.of(false);
        rig.reads().stock = List.of();
        rig.reads().why = "无线终端超出访问点范围";
        rig.access().openingStatus = ActionStatus.done();
        TerminalSync sync = TerminalSync.waitingForStock(rig.session());
        sync.proceed(TICKS);
        // 空表要先等过"可能还没同步齐"的那几刻才下结论。
        for (int i = 0; i < 10; i++) {
            assertInstanceOf(TerminalTurn.Stay.class, sync.proceed(TICKS));
            TICKS.advance();
        }
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, sync.proceed(TICKS));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("（无线终端超出访问点范围）"));
        assertTrue(wrapUp.reason().message().contains("取不了"));
        assertEquals(List.of("falsex0看全了"), rig.access().remembered, "没连上这件事记下，下次挑终端排后面");
    }

    @Test
    void 存货一直没同步过来_到期限按卡住收场() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        rig.access().openingStatus = ActionStatus.done();
        TerminalSync sync = TerminalSync.waitingForStock(rig.session());
        sync.proceed(TICKS);
        TerminalTurn last = null;
        for (int i = 0; i < 45; i++) {
            last = sync.proceed(TICKS);
            TICKS.advance();
        }
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, last);
        assertEquals(Problem.Kind.STUCK, wrapUp.reason().kind());
        assertTrue(wrapUp.reason().message().contains("网络存货一直没同步过来"));
    }

    @Test
    void 网络是空的_多等几刻才当空网络放行() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        rig.reads().linked = Optional.of(true);
        rig.reads().stock = List.of();
        rig.access().openingStatus = ActionStatus.done();
        TerminalSync sync = TerminalSync.waitingForStock(rig.session());
        sync.proceed(TICKS);
        for (int i = 0; i < 10; i++) {
            assertInstanceOf(TerminalTurn.Stay.class, sync.proceed(TICKS));
            TICKS.advance();
        }
        assertInstanceOf(TerminalTurn.Synced.class, sync.proceed(TICKS));
    }

    @Test
    void 存东西只等连接状态_连上就放行_没连上记下这台存不了() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        // 存货表一直没到也无所谓：存东西不看存货。
        rig.reads().linked = Optional.of(true);
        rig.access().openingStatus = ActionStatus.done();
        TerminalSync sync = TerminalSync.waitingForLink(rig.session());
        sync.proceed(TICKS);
        assertInstanceOf(TerminalTurn.Synced.class, sync.proceed(TICKS));

        // 另开一台没连上的：照抄原因收场并记下。
        TerminalFlowFakes.Menu menu = new TerminalFlowFakes.Menu();
        Rig down = Rig.rig(menu, new TerminalFlowFakes.Reads());
        down.reads().linked = Optional.of(false);
        down.access().openingStatus = ActionStatus.done();
        TerminalSync linkSync = TerminalSync.waitingForLink(down.session());
        linkSync.proceed(TICKS);
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, linkSync.proceed(TICKS));
        assertTrue(wrapUp.reason().message().contains("存不了"));
        assertEquals(List.of("falsex0看全了"), down.access().remembered);
    }

    @Test
    void 存东西等连接状态一直没同步过来_按卡住收场() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Menu(), new TerminalFlowFakes.Reads());
        rig.access().openingStatus = ActionStatus.done();
        TerminalSync sync = TerminalSync.waitingForLink(rig.session());
        sync.proceed(TICKS);
        TerminalTurn last = null;
        for (int i = 0; i < 45; i++) {
            last = sync.proceed(TICKS);
            TICKS.advance();
        }
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class, last);
        assertTrue(wrapUp.reason().message().contains("网络连接状态一直没同步过来"));
    }
}
