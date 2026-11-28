// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.menu.TestPendingMenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 背包 2×2 合成取成品的离线单测：验证满包取货遇服务端未回包（UNCERTAIN）或确认后实际未获得成品时，
 * 立即按背包已满收手关闭背包，绝不把未确认当作进展重复点击死循环；同时确认正常取成品记账与关屏不受影响。
 */
class InventoryCraftingTest {

    private static RecipeHolder<?> torchHolder;
    private static WorkstationRecipe torchRecipe;

    @BeforeAll
    static void 引导注册表与火把配方() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ShapelessRecipe shapeless = new ShapelessRecipe("", CraftingBookCategory.MISC,
                new ItemStack(Items.TORCH, 4),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.COAL), Ingredient.of(Items.STICK)));
        torchHolder = new RecipeHolder<>(ResourceLocation.parse("minecraft:torch"), shapeless);
        torchRecipe = new WorkstationRecipe("minecraft:torch", WorkstationRecipe.Kind.CRAFTING,
                WantedItem.ofItem("minecraft:torch"), 4,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:coal"), 1, List.of()),
                        new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:stick"), 1, List.of())),
                true);
    }

    @Test
    void 满包取成品遇未确认立即按背包已满收手关屏_绝不重复点击() {
        // 产出格已有 4 支火把，背包已满时 QUICK_MOVE 遭服务端静默拒移，等待超时后返回 UNCERTAIN 且背包未增：
        // 必须立刻按 INVENTORY_FULL 收手并调用任务边界关背包，只点 1 次绝不空转。
        FakeView view = new FakeView();
        view.slots.put(InventoryMenu.RESULT_SLOT, new ItemStack(Items.TORCH, 4));
        FakeMenus menus = new FakeMenus(PendingMenuAction.Status.UNCERTAIN, () -> {});
        PlayerContext player = context(menus);
        List<Change> changes = new ArrayList<>();
        InventoryCrafting crafting = new InventoryCrafting(torchHolder, torchRecipe, 2, () -> player,
                recording(changes), view);

        // 第 1 刻打开背包界面；第 2 刻点击产出格；第 3 刻轮询到 UNCERTAIN 立刻按背包满失败收手。
        assertInstanceOf(ActionStatus.Running.class, crafting.tick(TickContext.of(1, player)));
        assertInstanceOf(ActionStatus.Running.class, crafting.tick(TickContext.of(2, player)));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                crafting.tick(TickContext.of(3, player)));

        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("背包可能满了"), failed.problem().message());
        assertEquals(1, menus.clickCalls, "遇到 UNCERTAIN 后必须立刻收手，不得反复重点击");
        assertEquals(1, menus.boundaryCloseCalls, "收手时必须请求关闭背包界面");
        assertTrue(changes.isEmpty(), "未收到成品不应误记物品增加");
    }

    @Test
    void 取成品确认后实际一件未进包_立即按背包已满收手关屏() {
        // 即使点击终态为 CONFIRMED_APPLIED，只要背包里实际多出的成品件数 <= 0，也立即按背包已满收手关屏。
        FakeView view = new FakeView();
        view.slots.put(InventoryMenu.RESULT_SLOT, new ItemStack(Items.TORCH, 4));
        FakeMenus menus = new FakeMenus(PendingMenuAction.Status.CONFIRMED_APPLIED, () -> {});
        PlayerContext player = context(menus);
        InventoryCrafting crafting = new InventoryCrafting(torchHolder, torchRecipe, 1, () -> player, null, view);

        assertInstanceOf(ActionStatus.Running.class, crafting.tick(TickContext.of(1, player)));
        assertInstanceOf(ActionStatus.Running.class, crafting.tick(TickContext.of(2, player)));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                crafting.tick(TickContext.of(3, player)));

        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertEquals(1, menus.clickCalls);
        assertEquals(1, menus.boundaryCloseCalls);
    }

    @Test
    void 取成品确认生效且背包收到成品_正常记账并关屏完成() {
        // 正常场景：QUICK_MOVE 后产出格清空、背包增加 4 支火把且服务端确认生效，记一笔 ITEM_GAINED 后关背包完成。
        FakeView view = new FakeView();
        view.slots.put(InventoryMenu.RESULT_SLOT, new ItemStack(Items.TORCH, 4));
        FakeMenus menus = new FakeMenus(PendingMenuAction.Status.CONFIRMED_APPLIED, () -> {
            view.slots.put(InventoryMenu.RESULT_SLOT, ItemStack.EMPTY);
            view.carried.merge("minecraft:torch", 4, Integer::sum);
        });
        PlayerContext player = context(menus);
        List<Change> changes = new ArrayList<>();
        InventoryCrafting crafting = new InventoryCrafting(torchHolder, torchRecipe, 1, () -> player,
                recording(changes), view);

        ActionStatus status = ActionStatus.running();
        for (long tick = 1; tick <= 10 && status instanceof ActionStatus.Running; tick++) {
            status = crafting.tick(TickContext.of(tick, player));
        }
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, menus.clickCalls);
        assertEquals(1, menus.closeCalls, "做够件数后应正常关闭背包");
        assertEquals(0, menus.boundaryCloseCalls);
        assertEquals(1, changes.size());
        assertEquals(Change.Kind.ITEM_GAINED, changes.getFirst().kind());
        assertEquals(4, changes.getFirst().count());
    }

    private static TaskRecords recording(List<Change> changes) {
        return new TaskRecords() {
            @Override public void change(Change change) { changes.add(change); }
            @Override public void unconfirmed(Change change) {}
            @Override public void attempt(String tried, String whatHappened) {}
        };
    }

    private static PlayerContext context(MenuActions menus) {
        return new PlayerContext() {
            @Override public LocalPlayer localPlayer() { return null; }
            @Override public ClientLevel level() { return null; }
            @Override public ClientPacketListener connection() { return null; }
            @Override public PlayerInput input() { return null; }
            @Override public InteractionSender interactionSender() { return null; }
            @Override public MenuActions menuActions() { return menus; }
            @Override public long clientTick() { return 100; }
            @Override public boolean isCurrent() { return true; }
            @Override public boolean canInteractThisTick() { return true; }
            @Override public boolean tryClaimInteraction() { return true; }
        };
    }

    /** 只读背包与合成格替身：离线控制产出格物品、背包库存件数与配方解锁态。 */
    private static final class FakeView implements InventoryCrafting.InventoryView {
        final Map<Integer, ItemStack> slots = new HashMap<>();
        final Map<String, Integer> carried = new HashMap<>();

        @Override public boolean hasPlayer(PlayerContext player) { return player != null; }
        @Override public boolean onInventoryMenu(PlayerContext player) { return true; }
        @Override public ItemStack slotItem(PlayerContext player, int slot) {
            return slots.getOrDefault(slot, ItemStack.EMPTY);
        }
        @Override public int carriedCount(PlayerContext player, String itemId) {
            return carried.getOrDefault(itemId, 0);
        }
        @Override public boolean recipeUnlocked(PlayerContext player, RecipeHolder<?> holder) { return true; }
    }

    /** 菜单动作替身：记录点击与关屏次数，按脚本把产出格点击推进到指定终态。 */
    private static final class FakeMenus implements MenuActions {
        private final PendingMenuAction.Status clickResult;
        private final Runnable onPollClick;
        private PendingMenuAction active;
        int clickCalls;
        int closeCalls;
        int boundaryCloseCalls;

        FakeMenus(PendingMenuAction.Status clickResult, Runnable onPollClick) {
            this.clickResult = clickResult;
            this.onPollClick = onPollClick;
        }

        @Override public boolean hasPendingTransaction() { return active != null && !active.terminal(); }
        @Override public boolean ensureWorldVisible(PlayerContext context) { return true; }
        @Override public boolean ensureVisible(PlayerContext context) { return true; }
        @Override public void interactionSubmitted(PlayerContext context) {}

        @Override public PendingMenuAction click(PlayerContext context, int slot, int button, ClickType clickType,
                MenuConfirmation confirmation, int timeoutTicks) {
            clickCalls++;
            active = TestPendingMenuActions.pendingClick(context, slot, timeoutTicks);
            return active;
        }

        @Override public PendingMenuAction swapInventoryToHotbar(PlayerContext context, int sourceInventorySlot,
                int hotbarSlot, int timeoutTicks) { return null; }

        @Override public PendingMenuAction placeRecipe(PlayerContext context, RecipeHolder<?> recipe, boolean shift,
                MenuConfirmation confirmation, int timeoutTicks) { return null; }

        @Override public PendingMenuAction close(PlayerContext context, int timeoutTicks) {
            closeCalls++;
            active = TestPendingMenuActions.pendingClick(context, -1, timeoutTicks);
            return active;
        }

        @Override public PendingMenuAction closeForTaskBoundary(PlayerContext context, int timeoutTicks,
                String boundaryReason) {
            boundaryCloseCalls++;
            PendingMenuAction closed = TestPendingMenuActions.pendingClick(context, -1, timeoutTicks);
            TestPendingMenuActions.finish(closed, PendingMenuAction.Status.CONFIRMED_APPLIED, boundaryReason);
            return closed;
        }

        @Override public PendingMenuAction poll(PlayerContext context, PendingMenuAction pending) {
            if (!pending.terminal()) {
                if (closeCalls > 0) {
                    TestPendingMenuActions.finish(pending, PendingMenuAction.Status.CONFIRMED_APPLIED, "已关上背包");
                } else {
                    onPollClick.run();
                    TestPendingMenuActions.finish(pending, clickResult, "脚本终态：" + clickResult);
                }
            }
            return pending;
        }
    }
}
