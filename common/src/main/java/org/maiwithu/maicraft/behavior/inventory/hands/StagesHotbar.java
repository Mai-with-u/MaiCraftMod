// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 投前批量预置快捷栏动作：在一次打开背包界面内，将本轮待连扔的几样原料按所需份数分槽摆进不同的快捷栏格，
 * 每步点击等待服务端确认，全部就位后关闭背包并选中第一样所在槽位；若原料本就全在快捷栏则不开背包直接完成。
 */
public final class StagesHotbar implements Action {

    /** 每一下点击与关闭背包等待服务端确认的期限（刻）。 */
    private static final int CLICK_TIMEOUT_TICKS = 20;

    private final List<BatchHotbarPlan.Request> requests;
    private BatchHotbarPlan.Plan plan;
    private int index;
    private PendingMenuAction pending;
    private boolean openedMenu;
    private boolean closing;
    private HotbarSelection selecting;
    private Problem failure;
    private PlayerContext lastPlayer;

    /** @param requests 按投掷先后排列的原料需求列表 */
    public StagesHotbar(List<BatchHotbarPlan.Request> requests) {
        this.requests = List.copyOf(Objects.requireNonNull(requests, "requests"));
    }

    /** 与输入需求一一对应的预置快捷栏格号（0..8）；未规划或空位不足未能预置的项为 -1。 */
    public List<Integer> stagedSlots() {
        return plan == null ? List.of() : plan.hotbarSlots();
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (failure != null) return ActionStatus.failed(failure);
        if (player == null || !player.isCurrent() || player.localPlayer() == null) return ActionStatus.running();
        lastPlayer = player;
        if (selecting != null) return selecting.tick(context);
        if (pending != null) return pollPending(player);
        if (plan == null) {
            plan = buildPlan(player.localPlayer());
            if (plan.clicks().isEmpty()) return selectFirstOrDone(player.localPlayer());
        }
        return stepClickOrClose(player);
    }

    // 推进等待中的点击或关包确认：确认成功后再继续下一下，偏离或超时如实收尾并释放界面。
    private ActionStatus pollPending(PlayerContext player) {
        var menus = player.menuActions();
        if (!pending.terminal()) pending = menus.poll(player, pending);
        if (!pending.terminal()) return ActionStatus.running();
        if (pending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
            return fail((closing ? "关背包" : "批量预置快捷栏第 " + (index + 1) + " 下")
                    + "没得到确认：" + pending.detail());
        }
        pending = null;
        if (closing) {
            openedMenu = false;
            return selectFirstOrDone(player.localPlayer());
        }
        index++;
        return ActionStatus.progressed();
    }

    // 所有点击都在一次打开的背包界面内连续完成，全部结束后统一关闭背包再切回世界操作。
    private ActionStatus stepClickOrClose(PlayerContext player) {
        var menus = player.menuActions();
        if (!player.canInteractThisTick()) return ActionStatus.running();
        if (index >= plan.clicks().size()) {
            closing = true;
            pending = menus.close(player, CLICK_TIMEOUT_TICKS);
            return ActionStatus.running();
        }
        // 上一件事遗留的工作台、箱子等外部界面还开着时先关掉，再打开自己的背包界面，避免在非背包容器上误点。
        LocalPlayer self = player.localPlayer();
        if (self.containerMenu != self.inventoryMenu) {
            try {
                if (!menus.ensureWorldVisible(player)) return ActionStatus.running();
            } catch (RuntimeException stuck) {
                return fail("开着的界面关不掉：" + stuck.getMessage());
            }
        }
        // 调用打开背包前先记为已开屏：即使首帧还未显示完就被打断，收尾时也会按边界把可能已打开的背包关上。
        openedMenu = true;
        if (!menus.ensureVisible(player)) return ActionStatus.running();
        AbstractContainerMenu menu = self.containerMenu;
        if (index == 0 && !menu.getCarried().isEmpty()) {
            return fail("鼠标光标上还拿着东西，没法批量预置快捷栏");
        }
        BatchHotbarPlan.Click click = plan.clicks().get(index);
        var clickedMenuSlot = menu.findSlot(self.getInventory(), click.inventorySlot());
        if (clickedMenuSlot.isEmpty()) return fail("背包界面里找不到格号 " + click.inventorySlot());
        int otherMenuSlot = click.otherInventorySlot() >= 0
                ? menu.findSlot(self.getInventory(), click.otherInventorySlot()).orElse(-1) : -1;
        pending = menus.click(player, clickedMenuSlot.getAsInt(), click.button(), click.type(),
                confirmation(click, clickedMenuSlot.getAsInt(), otherMenuSlot), CLICK_TIMEOUT_TICKS);
        return ActionStatus.running();
    }

    // 关背包后选中第一样预置好的快捷栏格，使到达水边时第一样已在主手，直接瞄准出手。
    private ActionStatus selectFirstOrDone(LocalPlayer self) {
        int first = plan.hotbarSlots().stream().filter(slot -> slot >= 0).findFirst().orElse(-1);
        if (first < 0 || self.getInventory().selected == first) return ActionStatus.done();
        selecting = new HotbarSelection(first);
        return ActionStatus.progressed();
    }

    // 读取当前背包 0..35 格快照交给纯规划器算出最少点击序列。
    private BatchHotbarPlan.Plan buildPlan(LocalPlayer self) {
        Inventory inventory = self.getInventory();
        List<BatchHotbarPlan.Slot> snapshot = new ArrayList<>(BatchHotbarPlan.LAST_MAIN_SLOT + 1);
        for (int slot = 0; slot <= BatchHotbarPlan.LAST_MAIN_SLOT; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                snapshot.add(BatchHotbarPlan.Slot.empty());
            } else {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                HotbarSwapTarget.Holding holding = HotbarSwapTarget.holding(stack, itemId -> 0);
                snapshot.add(new BatchHotbarPlan.Slot(id, stack.getCount(), stack.getMaxStackSize(), holding));
            }
        }
        return BatchHotbarPlan.plan(snapshot, inventory.selected, requests);
    }

    // 核对点击后目标格、关联格与光标件数是否全部符合规划预期，状态号未变继续等，偏离立即判 DIVERGED。
    private static MenuConfirmation confirmation(BatchHotbarPlan.Click click, int menuSlot, int otherMenuSlot) {
        return (context, pendingAction) -> {
            AbstractContainerMenu menu = context.localPlayer().containerMenu;
            ItemStack slotStack = menu.getSlot(menuSlot).getItem();
            boolean slotOk = click.type() == ClickType.SWAP
                    ? slotStack.getCount() == click.slotCountAfter()
                    : countOf(slotStack, click.itemId()) == click.slotCountAfter();
            boolean otherOk = otherMenuSlot < 0
                    || countOf(menu.getSlot(otherMenuSlot).getItem(), click.itemId()) == click.otherCountAfter();
            boolean cursorOk = countOf(menu.getCarried(), click.itemId()) == click.cursorCountAfter();
            if (slotOk && otherOk && cursorOk) return MenuConfirmation.Verdict.APPLIED;
            return menu.getStateId() == pendingAction.beforeStateId()
                    ? MenuConfirmation.Verdict.PENDING : MenuConfirmation.Verdict.DIVERGED;
        };
    }

    private static int countOf(ItemStack stack, String itemId) {
        if (stack.isEmpty()) return 0;
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId) ? stack.getCount() : 0;
    }

    private ActionStatus fail(String reason) {
        failure = Problem.of(Problem.Kind.STUCK, reason);
        releaseScreen();
        return ActionStatus.failed(failure);
    }

    @Override
    public void close() {
        releaseScreen();
    }

    // 异常退出或被取消时把打开着的背包界面交由原版关上，避免挡住后续走路与投掷。
    private void releaseScreen() {
        if (!openedMenu || lastPlayer == null || lastPlayer.localPlayer() == null || lastPlayer.menuActions() == null
                || !lastPlayer.menuActions().blocksWorld(lastPlayer)) {
            return;
        }
        openedMenu = false;
        try {
            lastPlayer.menuActions().closeForTaskBoundary(lastPlayer, CLICK_TIMEOUT_TICKS, "批量预置快捷栏收尾");
        } catch (RuntimeException ignored) {
            // 这一刻交不出关界面：后续走路或丢东西前会再次确保关闭。
        }
    }

    @Override
    public Interruptibility interruptibility() {
        if (selecting != null) return selecting.interruptibility();
        return pending != null && !pending.terminal() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        if (selecting != null) return selecting.describe();
        if (closing) return "关上背包，准备连续投掷";
        return plan == null ? "打开背包，把本轮原料分槽预置进快捷栏"
                : "把本轮原料预置进快捷栏（第 " + (index + 1) + "/" + plan.clicks().size() + " 下）";
    }
}
