// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 「下单等成品」把下单那边的决定换成会话的走向：背包装不下不下单、没样板照旧说没有了、
 * 自己之前下的还在做就接着等并每秒报一次进展；等不到成品时取到了也按失败交回。
 */
class WaitsForCraftTest {

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final TerminalFlowFakes.Ticks TICKS = new TerminalFlowFakes.Ticks(new TerminalFlowFakes.StubPlayer());

    /** 绑好替身终端的取货会话与问它下单的一段。 */
    private record Rig(TerminalFlowFakes.Reads reads, TakeSession session, WaitsForCraft craft) {

        static Rig rig(TerminalFlowFakes.Reads reads) {
            TerminalFlowFakes.Access access = new TerminalFlowFakes.Access();
            TakeSession session = new TakeSession(access, new Ae2Compat(CraftOrderingTest.NO_TERMINALS,
                    reads, new NoGrid()), TerminalFlowFakes.services(TerminalFlowFakes.StubPlayer::new),
                    new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 8, "工具准备")) {
                @Override AbstractContainerMenu rawPage(PlayerContext player) {
                    return reads.page();
                }
            };
            return new Rig(reads, session, new WaitsForCraft(new Ae2Compat(CraftOrderingTest.NO_TERMINALS,
                    reads, new NoGrid()), session, null));
        }
    }

    /** 网络里铁锭那一条：stored 件、craftable 标着有没有样板。 */
    private static List<Ae2TerminalMenu.StockEntry> iron(long stored, boolean craftable) {
        return List.of(new Ae2TerminalMenu.StockEntry(7, new ItemStack(Items.IRON_INGOT), stored, craftable, false));
    }

    @Test
    void 背包一件都装不下_不下单_引擎腾出地方还能接着来() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Reads());
        rig.reads().stock = iron(0, true);
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class,
                rig.craft().onNoStock(rig.reads().page(), rig.reads().stock, 0, 8, sample -> 0));
        assertEquals(Problem.Kind.INVENTORY_FULL, wrapUp.reason().kind());
        assertFalse(wrapUp.evenIfTaken());
        assertFalse(rig.craft().involved(), "没下单，总期限不放宽");
    }

    @Test
    void 没样板_照旧说网络里没有了() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Reads());
        rig.reads().stock = iron(0, false);
        TerminalTurn.WrapUp wrapUp = assertInstanceOf(TerminalTurn.WrapUp.class,
                rig.craft().onNoStock(rig.reads().page(), rig.reads().stock, 0, 8, sample -> 64));
        assertEquals(Problem.Kind.NEED_ITEM, wrapUp.reason().kind());
        assertEquals("ae2:terminal（测试台） 的网络里没有minecraft:iron_ingot了", wrapUp.reason().message());
    }

    @Test
    void 自己之前下的还在做_接着等并每秒报一次进展() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Reads());
        rig.reads().pending = true;
        rig.reads().stock = iron(0, true);
        assertInstanceOf(TerminalTurn.Stay.class,
                rig.craft().onNoStock(rig.reads().page(), rig.reads().stock, 0, 8, sample -> 64));
        assertTrue(rig.craft().involved(), "在等之前下的这一单，总期限放宽");
        assertTrue(rig.craft().waiting());
        // 等待有上限管着，这里只每秒报一次进展：开头那一问已经数了一刻，再数十八刻都在做，第二十刻报一次真实进展。
        TerminalTurn beat = null;
        for (int i = 0; i < 18; i++) {
            beat = rig.craft().onNoStock(rig.reads().page(), rig.reads().stock, 0, 8, sample -> 64);
            assertFalse(((ActionStatus.Running) ((TerminalTurn.Stay) beat).status()).progressed());
        }
        beat = rig.craft().onNoStock(rig.reads().page(), rig.reads().stock, 0, 8, sample -> 64);
        assertTrue(((ActionStatus.Running) ((TerminalTurn.Stay) beat).status()).progressed());
    }

    @Test
    void 没在下单时_下单那几页的推进只是照常等着() {
        Rig rig = Rig.rig(new TerminalFlowFakes.Reads());
        assertInstanceOf(TerminalTurn.Stay.class, rig.craft().proceed(TICKS));
        assertFalse(rig.craft().waiting());
    }
}
