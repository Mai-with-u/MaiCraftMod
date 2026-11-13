// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.menu.TestPendingMenuActions;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 副手穿脱装备的逐刻轮询验证：发起原生副手交换后保存等待记录并逐刻轮询确认，
 * 不在等待服务端确认期间重复发交换，也不会因客户端预测腾空原格而误报缺物或死循环。
 */
class ClientGearChangesTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void takingOffOffhandPollsSamePendingSwapUntilConfirmedWithoutResubmitting() {
        // 副手拿着盾牌、主背包第 9 格空着：第 1 刻发起交换（客户端先把盾牌预测进第 9 格），
        // 第 2 刻服务端尚未确认时只轮询同一笔等待记录，第 3 刻服务端确认落袋后立即完成。
        Inventory inventory = new Inventory(null);
        inventory.offhand.set(0, new ItemStack(Items.SHIELD));
        OffhandMenus menus = new OffhandMenus(inventory);
        AtomicReference<PlayerContext> current = new AtomicReference<>(context(menus, 10));
        ClientGearChanges gear = gearChanges(current, inventory);

        Action action = gear.takeOff(GearSlotName.OFFHAND).orElseThrow();

        assertInstanceOf(ActionStatus.Running.class, action.tick(TickContext.of(10, current.get())));
        assertEquals(1, menus.swapSubmissions);
        assertEquals(9, menus.lastSwappedSlot);
        assertEquals(0, menus.pollCount);

        current.set(context(menus, 11));
        assertInstanceOf(ActionStatus.Running.class, action.tick(TickContext.of(11, current.get())));
        assertEquals(1, menus.swapSubmissions);
        assertEquals(1, menus.pollCount);

        menus.confirmOnNextPoll("副手盾牌已换入背包");
        current.set(context(menus, 12));
        assertInstanceOf(ActionStatus.Done.class, action.tick(TickContext.of(12, current.get())));
        assertEquals(1, menus.swapSubmissions);
        assertEquals(2, menus.pollCount);
        assertEquals(Items.SHIELD, inventory.getItem(9).getItem());
    }

    @Test
    void takingOffOffhandReportsFailureWhenSwapTimesOutOrDiverges() {
        // 发起交换后服务端超时没确认：轮询到终态后如实以失败收场，不再盲目重发交换。
        Inventory inventory = new Inventory(null);
        inventory.offhand.set(0, new ItemStack(Items.SHIELD));
        OffhandMenus menus = new OffhandMenus(inventory);
        AtomicReference<PlayerContext> current = new AtomicReference<>(context(menus, 20));
        ClientGearChanges gear = gearChanges(current, inventory);

        Action action = gear.takeOff(GearSlotName.OFFHAND).orElseThrow();
        assertInstanceOf(ActionStatus.Running.class, action.tick(TickContext.of(20, current.get())));

        menus.failActive(PendingMenuAction.Status.UNCERTAIN, "等满 60 刻服务端仍未确认");
        current.set(context(menus, 80));
        ActionStatus.Failed failed = assertInstanceOf(
                ActionStatus.Failed.class, action.tick(TickContext.of(80, current.get())));
        assertEquals(Problem.Kind.STUCK, failed.problem().kind());
        assertEquals(1, menus.swapSubmissions);
    }

    @Test
    void takingOffOffhandFailsWithInventoryFullWhenEverySlotIsOccupied() {
        // 主背包与快捷栏 36 格全满时取不下副手物品，当场报背包已满而不发交换。
        Inventory inventory = new Inventory(null);
        for (int slot = 0; slot <= 35; slot++) {
            inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        inventory.offhand.set(0, new ItemStack(Items.SHIELD));
        OffhandMenus menus = new OffhandMenus(inventory);
        AtomicReference<PlayerContext> current = new AtomicReference<>(context(menus, 1));
        ClientGearChanges gear = gearChanges(current, inventory);

        Action action = gear.takeOff(GearSlotName.OFFHAND).orElseThrow();
        ActionStatus.Failed failed = assertInstanceOf(
                ActionStatus.Failed.class, action.tick(TickContext.of(1, current.get())));
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertEquals(0, menus.swapSubmissions);
    }

    @Test
    void wearingOnlyShieldIntoOffhandPollsUntilConfirmedInsteadOfMisreportingMissingItem() {
        // 身上只有第 12 格一面盾牌：第 1 刻发起交换后客户端预测把第 12 格腾空、盾牌进副手，
        // 第 2 刻轮询同一笔等待记录而不会重扫背包误报缺盾牌，确认后正常完成。
        Inventory inventory = new Inventory(null);
        inventory.setItem(12, new ItemStack(Items.SHIELD));
        OffhandMenus menus = new OffhandMenus(inventory);
        AtomicReference<PlayerContext> current = new AtomicReference<>(context(menus, 30));
        ClientGearChanges gear = gearChanges(current, inventory);

        Action action = gear.wear("minecraft:shield", GearSlotName.OFFHAND).orElseThrow();
        assertInstanceOf(ActionStatus.Running.class, action.tick(TickContext.of(30, current.get())));
        assertEquals(1, menus.swapSubmissions);
        assertEquals(12, menus.lastSwappedSlot);

        current.set(context(menus, 31));
        assertInstanceOf(ActionStatus.Running.class, action.tick(TickContext.of(31, current.get())));
        assertEquals(1, menus.swapSubmissions);
        assertEquals(1, menus.pollCount);

        menus.confirmActive("盾牌已换入副手");
        current.set(context(menus, 32));
        assertInstanceOf(ActionStatus.Done.class, action.tick(TickContext.of(32, current.get())));
        assertEquals(Items.SHIELD, inventory.offhand.getFirst().getItem());
    }

    @Test
    void offhandTakeOffGivesUpAfterScreenRemainsBusyPastStepTimeout() {
        // 世界画面一直被别的界面占住、副手交换始终发不出去时，超过一步期限后如实报错退出。
        Inventory inventory = new Inventory(null);
        inventory.offhand.set(0, new ItemStack(Items.SHIELD));
        OffhandMenus menus = new OffhandMenus(inventory);
        menus.screenBusy = true;
        AtomicReference<PlayerContext> current = new AtomicReference<>(context(menus, 100));
        ClientGearChanges gear = gearChanges(current, inventory);

        Action action = gear.takeOff(GearSlotName.OFFHAND).orElseThrow();
        assertInstanceOf(ActionStatus.Running.class, action.tick(TickContext.of(100, current.get())));

        current.set(context(menus, 161));
        ActionStatus.Failed failed = assertInstanceOf(
                ActionStatus.Failed.class, action.tick(TickContext.of(161, current.get())));
        assertEquals(Problem.Kind.STUCK, failed.problem().kind());
    }

    private static ClientGearChanges gearChanges(AtomicReference<PlayerContext> contexts, Inventory inventory) {
        return new ClientGearChanges(contexts::get, new ClientMovesToMainhand(contexts::get), player -> inventory);
    }

    private static PlayerContext context(MenuActions menus, long tick) {
        return new PlayerContext() {
            @Override public LocalPlayer localPlayer() { return null; }
            @Override public ClientLevel level() { return null; }
            @Override public ClientPacketListener connection() { return null; }
            @Override public PlayerInput input() { return null; }
            @Override public InteractionSender interactionSender() { return null; }
            @Override public MenuActions menuActions() { return menus; }
            @Override public long clientTick() { return tick; }
            @Override public boolean isCurrent() { return true; }
            @Override public boolean canInteractThisTick() { return true; }
            @Override public boolean tryClaimInteraction() { return true; }
        };
    }

    /** 模拟 DefaultMenuActions 的单笔菜单操作约束：上一笔未结清时再次提交会抛 IllegalStateException。 */
    private static final class OffhandMenus implements MenuActions {
        private final Inventory inventory;
        private PendingMenuAction active;
        private String pendingConfirmDetail;
        boolean screenBusy;
        int swapSubmissions;
        int lastSwappedSlot = -1;
        int pollCount;

        OffhandMenus(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public PendingMenuAction swapInventoryToOffhand(PlayerContext context, int inventorySlot, int timeoutTicks) {
            if (screenBusy || active != null && !active.terminal()) {
                throw new IllegalStateException("前一项菜单操作还没结束或画面正忙");
            }
            swapSubmissions++;
            lastSwappedSlot = inventorySlot;
            // 模拟客户端点击瞬间先在本地对调槽位与副手，服务端确认需后续 poll 才到达。
            ItemStack inSlot = inventory.getItem(inventorySlot);
            ItemStack inOffhand = inventory.offhand.getFirst();
            inventory.setItem(inventorySlot, inOffhand);
            inventory.offhand.set(0, inSlot);
            active = TestPendingMenuActions.pendingClick(context, inventorySlot, timeoutTicks);
            return active;
        }

        @Override
        public PendingMenuAction poll(PlayerContext context, PendingMenuAction pending) {
            pollCount++;
            if (pendingConfirmDetail != null) {
                TestPendingMenuActions.finish(pending, PendingMenuAction.Status.CONFIRMED_APPLIED, pendingConfirmDetail);
                pendingConfirmDetail = null;
            }
            return pending;
        }

        void confirmOnNextPoll(String detail) {
            this.pendingConfirmDetail = detail;
        }

        void confirmActive(String detail) {
            TestPendingMenuActions.finish(active, PendingMenuAction.Status.CONFIRMED_APPLIED, detail);
        }

        void failActive(PendingMenuAction.Status status, String detail) {
            TestPendingMenuActions.finish(active, status, detail);
        }

        @Override public boolean hasPendingTransaction() { return active != null && !active.terminal(); }
        @Override public boolean ensureWorldVisible(PlayerContext context) { return !screenBusy; }
        @Override public boolean ensureVisible(PlayerContext context) { return true; }
        @Override public void interactionSubmitted(PlayerContext context) {}
        @Override public PendingMenuAction click(PlayerContext context, int slot, int button, ClickType clickType,
                MenuConfirmation confirmation, int timeoutTicks) { return null; }
        @Override public PendingMenuAction swapInventoryToHotbar(PlayerContext context, int sourceInventorySlot,
                int hotbarSlot, int timeoutTicks) { return null; }
        @Override public PendingMenuAction placeRecipe(PlayerContext context, RecipeHolder<?> recipe, boolean shift,
                MenuConfirmation confirmation, int timeoutTicks) { return null; }
        @Override public PendingMenuAction close(PlayerContext context, int timeoutTicks) { return null; }
        @Override public PendingMenuAction closeForTaskBoundary(PlayerContext context, int timeoutTicks,
                String boundaryReason) { return null; }
    }
}
