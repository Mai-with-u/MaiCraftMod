// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 合成终端与无线合成终端 3×3 手搓合成动作：
 * 打开界面 → 按 SlotSemantics.CRAFTING_GRID / CRAFTING_RESULT 定位槽位 →
 * 发 FillCraftingGridFromRecipePacket(recipeId, templates9, false)（严格 craftMissing=false）→
 * 等产物格就绪后发 InventoryActionPacket(CRAFT_SHIFT / CRAFT_ITEM) 取货（绝不走原版 pickup）→
 * 完工调用 clearCraftingGrid() 退网（放不下再 clearToPlayerInventory() 退包）→ 关窗。
 */
final class CraftingTermAction implements Action {

    private enum Stage { OPEN, FILL_GRID, WAIT_RESULT, TAKE_RESULT, WAIT_TAKEN, CLEAR_GRID, WAIT_CLEAR, CLOSE, DONE }

    private final MenuOpening opening;
    private final String recipeId;
    private final List<ItemStack> templates9;
    private final String wantedItemId;
    private final int wantedCount;
    private final Ae2Compat compat;
    private final Ae2MachineReads reads;

    private Stage stage = Stage.OPEN;
    private OpenedMenu menu;
    private Action closing;
    private int resultSlotIndex = -1;
    private int carriedBefore;
    private int crafted;
    private int waited;
    private boolean clearedToNetwork;
    private boolean clearedToPlayer;
    private Problem problem;

    CraftingTermAction(MenuOpening opening, String recipeId, List<ItemStack> templates9,
            String wantedItemId, int wantedCount, Ae2Compat compat, Ae2MachineReads reads) {
        this.opening = Objects.requireNonNull(opening, "opening");
        this.recipeId = recipeId;
        this.templates9 = List.copyOf(Objects.requireNonNull(templates9, "templates9"));
        this.wantedItemId = Objects.requireNonNull(wantedItemId, "wantedItemId");
        this.wantedCount = Math.max(1, wantedCount);
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    /** 按原版 CraftingRecipe 构造 9 格模板数组，适配 ShapedRecipe 的宽高排布与 ShapelessRecipe 顺序排布。 */
    static List<ItemStack> templatesFor(RecipeHolder<CraftingRecipe> holder) {
        List<ItemStack> grid = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) grid.add(ItemStack.EMPTY);
        CraftingRecipe recipe = holder.value();
        List<Ingredient> ingredients = recipe.getIngredients();
        if (recipe instanceof ShapedRecipe shaped) {
            int width = Math.min(3, shaped.getWidth());
            int height = Math.min(3, shaped.getHeight());
            for (int r = 0; r < height; r++) {
                for (int c = 0; c < width; c++) {
                    int idx = r * width + c;
                    if (idx < ingredients.size()) {
                        grid.set(r * 3 + c, firstStack(ingredients.get(idx)));
                    }
                }
            }
        } else {
            int limit = Math.min(9, ingredients.size());
            for (int i = 0; i < limit; i++) {
                grid.set(i, firstStack(ingredients.get(i)));
            }
        }
        return List.copyOf(grid);
    }

    private static ItemStack firstStack(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return ItemStack.EMPTY;
        ItemStack[] items = ingredient.getItems();
        return items.length == 0 ? ItemStack.EMPTY : items[0].copyWithCount(1);
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext player = tick.player();
        return switch (stage) {
            case OPEN -> open(tick, player);
            case FILL_GRID -> fillGrid(player);
            case WAIT_RESULT -> waitResult();
            case TAKE_RESULT -> takeResult(player);
            case WAIT_TAKEN -> waitTaken(player);
            case CLEAR_GRID -> clearGrid();
            case WAIT_CLEAR -> waitClear();
            case CLOSE -> closeMenu(tick);
            case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    private ActionStatus open(TickContext tick, PlayerContext player) {
        ActionStatus status = opening.tick(tick);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "合成终端界面没打开"));
        // 严格按语义定位 3×3 合成格与产物格，兼容有线版与无线版截然不同的槽位排列以及网络工具箱 9 槽偏移。
        List<Integer> gridSlots = compat.menuSlotsOf("CRAFTING_GRID");
        List<Integer> resultSlots = compat.menuSlotsOf("CRAFTING_RESULT");
        if (gridSlots.size() < 9 || resultSlots.isEmpty()) {
            return beginClose(Problem.of(Problem.Kind.UNSUPPORTED,
                    "当前打开的界面缺少 3×3 合成格（CRAFTING_GRID）或产物格（CRAFTING_RESULT）"));
        }
        resultSlotIndex = resultSlots.getFirst();
        carriedBefore = countCarried(player, wantedItemId);
        waited = 0;
        stage = Stage.FILL_GRID;
        return ActionStatus.progressed();
    }

    // 发 FillCraftingGridFromRecipePacket(..., false)：严禁 craftMissing=true，防止缺料时强切自动合成确认页。
    private ActionStatus fillGrid(PlayerContext player) {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isEmpty()) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClearThenClose(Problem.of(Problem.Kind.STUCK, "合成终端界面同步超时"));
        }
        if (!view.get().linked()) {
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "合成终端未连上 ME 网络"));
        }
        if (!reads.sendFillCraftingGrid(recipeId, templates9)) {
            return beginClearThenClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法发送合成终端填料包"));
        }
        waited = 0;
        stage = Stage.WAIT_RESULT;
        return ActionStatus.progressed();
    }

    private ActionStatus waitResult() {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isPresent() && wantedItemId.equals(view.get().resultItemId()) && view.get().resultCount() > 0) {
            waited = 0;
            stage = Stage.TAKE_RESULT;
            return ActionStatus.progressed();
        }
        if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClearThenClose(Problem.of(Problem.Kind.NEED_ITEM,
                "合成终端 3×3 合成格未能凑齐 " + wantedItemId + " 的材料（网络与背包均缺料）"));
    }

    // 必须走 AE2 原生 CRAFT_SHIFT / CRAFT_ITEM 包取产物，绝不走原版 pickup（CraftingTermSlot.mayPickup 恒为 false）。
    private ActionStatus takeResult(PlayerContext player) {
        carriedBefore = countCarried(player, wantedItemId);
        boolean useShift = (wantedCount - crafted) > 1;
        if (!reads.sendCraftResult(resultSlotIndex, useShift)) {
            return beginClearThenClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法发送合成终端取产物包"));
        }
        waited = 0;
        stage = Stage.WAIT_TAKEN;
        return ActionStatus.progressed();
    }

    private ActionStatus waitTaken(PlayerContext player) {
        int now = countCarried(player, wantedItemId);
        if (now > carriedBefore) {
            crafted += (now - carriedBefore);
            if (crafted >= wantedCount) {
                return beginClearThenClose(null);
            }
            // 还需继续合成下一批：先看产物格是否已由 AE2 自动补料继续亮着。
            Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
            if (view.isPresent() && wantedItemId.equals(view.get().resultItemId()) && view.get().resultCount() > 0) {
                stage = Stage.TAKE_RESULT;
            } else {
                stage = Stage.FILL_GRID;
            }
            waited = 0;
            return ActionStatus.progressed();
        }
        if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClearThenClose(crafted > 0 ? null
                : Problem.of(Problem.Kind.STUCK, "合成终端已发取产物操作，但背包未收到 " + wantedItemId));
    }

    // 完工清格：先退回 ME 网络，若网格仍有余料再退回玩家背包，绝不把材料遗留在合成格中。
    private ActionStatus clearGrid() {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isEmpty() || !view.get().gridOccupied()) {
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }
        if (!clearedToNetwork) {
            clearedToNetwork = true;
            reads.sendClearCraftingGrid(false);
            waited = 0;
            stage = Stage.WAIT_CLEAR;
            return ActionStatus.progressed();
        }
        if (!clearedToPlayer) {
            clearedToPlayer = true;
            reads.sendClearCraftingGrid(true);
            waited = 0;
            stage = Stage.WAIT_CLEAR;
            return ActionStatus.progressed();
        }
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus waitClear() {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isEmpty() || !view.get().gridOccupied()) {
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }
        if (++waited <= 15) return ActionStatus.running();
        stage = clearedToPlayer ? Stage.CLOSE : Stage.CLEAR_GRID;
        return ActionStatus.progressed();
    }

    private ActionStatus closeMenu(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        closing = null;
        return finish(problem);
    }

    private ActionStatus beginClearThenClose(Problem cause) {
        problem = cause;
        stage = Stage.CLEAR_GRID;
        waited = 0;
        return ActionStatus.progressed();
    }

    private ActionStatus beginClose(Problem cause) {
        problem = cause;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus finish(Problem cause) {
        problem = cause;
        stage = Stage.DONE;
        return cause == null ? ActionStatus.done() : ActionStatus.failed(cause);
    }

    private static int countCarried(PlayerContext player, String itemId) {
        if (player == null || player.localPlayer() == null) return 0;
        int total = 0;
        Inventory inv = player.localPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override public void pause() {
        if (closing != null) closing.pause();
        else if (menu == null) opening.pause();
    }

    @Override public void close() {
        if (closing != null) closing.close();
        if (menu != null && stage != Stage.DONE) menu.abandon();
        else if (menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

    @Override public String describe() {
        return "在合成终端 3×3 合成格手搓 " + wantedItemId + "（" + crafted + "/" + wantedCount + "）";
    }
}
