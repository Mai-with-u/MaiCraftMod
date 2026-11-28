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

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuWork;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 合成终端与无线合成终端 3×3 手搓合成动作：
 * 打开界面 → 按 SlotSemantics.CRAFTING_GRID / CRAFTING_RESULT 定位槽位 →
 * 发 FillCraftingGridFromRecipePacket(recipeId, templates9, false)（严格 craftMissing=false）→
 * 等产物格就绪后：仅在剩余需求达到整组（64 件）时发 CRAFT_SHIFT 直进背包，
 * 否则发 CRAFT_ITEM 拿单次成品到光标 → 点背包空位/可堆叠格放下并核对入包 →
 * 完工调用 clearCraftingGrid() 退网（放不下再 clearToPlayerInventory() 退包）→ 关窗。
 */
final class CraftingTermAction extends Ae2MenuWork {

    /** 仅当剩余需求至少满一整组时才使用 CRAFT_SHIFT，防止要 1~2 件却把网内材料搓成 64 件。F 游戏事实。 */
    static final int SHIFT_CRAFT_THRESHOLD = 64;

    /** 完工清格的等待上限（刻），比同步等待短：清格退料只是收尾。 */
    private static final int CLEAR_WAIT_TICKS = 15;

    private enum Step {
        ENTER, FILL_GRID, WAIT_RESULT, TAKE_RESULT, WAIT_TAKEN,
        PLACE_CURSOR, WAIT_PLACED, CLEAR_GRID, WAIT_CLEAR
    }

    private final String recipeId;
    private final List<ItemStack> templates9;
    private final String wantedItemId;
    private final int wantedCount;
    private final Ae2Compat compat;
    private final Ae2MachineReads reads;

    private Step step = Step.ENTER;
    private int resultSlotIndex = -1;
    private boolean lastUsedShift;
    private int carriedBefore;
    private int cursorCountBefore;
    private int crafted;
    private int clearWaited;
    private boolean clearedToNetwork;
    private boolean clearedToPlayer;
    /** 走清格收场时带上的结局：做成时为空，做不下去时是原因。 */
    private Problem closingProblem;

    CraftingTermAction(MenuOpening opening, String recipeId, List<ItemStack> templates9,
            String wantedItemId, int wantedCount, Ae2Compat compat, Ae2MachineReads reads) {
        super(opening, "合成终端界面没打开");
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
                    if (idx < ingredients.size()) grid.set(r * 3 + c, firstStack(ingredients.get(idx)));
                }
            }
        } else {
            int limit = Math.min(9, ingredients.size());
            for (int i = 0; i < limit; i++) grid.set(i, firstStack(ingredients.get(i)));
        }
        return List.copyOf(grid);
    }

    private static ItemStack firstStack(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return ItemStack.EMPTY;
        ItemStack[] items = ingredient.getItems();
        return items.length == 0 ? ItemStack.EMPTY : items[0].copyWithCount(1);
    }

    @Override protected ActionStatus work(TickContext tick) {
        PlayerContext player = tick.player();
        return switch (step) {
            case ENTER -> enter(player);
            case FILL_GRID -> fillGrid();
            case WAIT_RESULT -> waitResult();
            case TAKE_RESULT -> takeResult(player);
            case WAIT_TAKEN -> waitTaken(player);
            case PLACE_CURSOR -> placeCursor();
            case WAIT_PLACED -> waitPlaced();
            case CLEAR_GRID -> clearGrid();
            case WAIT_CLEAR -> waitClear();
        };
    }

    // 界面开好先认槽位：按语义定位 3×3 合成格与产物格，免疫有线/无线槽序差异及网络工具箱动态 9 槽偏移。
    private ActionStatus enter(PlayerContext player) {
        List<Integer> gridSlots = compat.menuSlotsOf("CRAFTING_GRID");
        List<Integer> resultSlots = compat.menuSlotsOf("CRAFTING_RESULT");
        if (gridSlots.size() < 9 || resultSlots.isEmpty()) {
            return closeWith(Problem.of(Problem.Kind.UNSUPPORTED,
                    "当前打开的界面缺少 3×3 合成格（CRAFTING_GRID）或产物格（CRAFTING_RESULT）"));
        }
        resultSlotIndex = resultSlots.getFirst();
        carriedBefore = countInPlayer(player);
        step = Step.FILL_GRID;
        return ActionStatus.progressed();
    }

    // 发 FillCraftingGridFromRecipePacket(..., false)：严禁 craftMissing=true，防止缺料时强切确认页。
    private ActionStatus fillGrid() {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isEmpty()) {
            if (!syncWaitElapsed()) return ActionStatus.running();
            return beginClearThenClose(Problem.of(Problem.Kind.STUCK, "合成终端界面同步超时"));
        }
        if (!view.get().linked()) {
            return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "合成终端未连上 ME 网络"));
        }
        if (!reads.sendFillCraftingGrid(recipeId, templates9)) {
            return beginClearThenClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法发送合成终端填料包"));
        }
        resetWait();
        step = Step.WAIT_RESULT;
        return ActionStatus.progressed();
    }

    private ActionStatus waitResult() {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isPresent() && wantedItemId.equals(view.get().resultItemId()) && view.get().resultCount() > 0) {
            resetWait();
            step = Step.TAKE_RESULT;
            return ActionStatus.progressed();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return beginClearThenClose(Problem.of(Problem.Kind.NEED_ITEM,
                "合成终端 3×3 合成格未能凑齐 " + wantedItemId + " 的材料（网络与背包均缺料）"));
    }

    // 必须走 AE2 原生 CRAFT_SHIFT / CRAFT_ITEM 包取产物，绝不走原版 pickup（CraftingTermSlot.mayPickup 恒为 false）。
    private ActionStatus takeResult(PlayerContext player) {
        carriedBefore = countInPlayer(player);
        lastUsedShift = (wantedCount - crafted) >= SHIFT_CRAFT_THRESHOLD;
        if (!reads.sendCraftResult(resultSlotIndex, lastUsedShift)) {
            return beginClearThenClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法发送合成终端取产物包"));
        }
        resetWait();
        step = Step.WAIT_TAKEN;
        return ActionStatus.progressed();
    }

    // CRAFT_SHIFT 直接进入玩家背包；CRAFT_ITEM 成品落在鼠标光标，需监测光标后点背包槽放进背包。
    private ActionStatus waitTaken(PlayerContext player) {
        if (!lastUsedShift) {
            SlotSnapshot cursor = menu().cursor();
            if (!cursor.isEmpty() && wantedItemId.equals(cursor.key())) {
                cursorCountBefore = cursor.count();
                resetWait();
                step = Step.PLACE_CURSOR;
                return ActionStatus.progressed();
            }
        }
        int now = countInPlayer(player);
        if (now > carriedBefore) {
            crafted += (now - carriedBefore);
            return afterBatchPlaced();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return beginClearThenClose(crafted > 0 ? null
                : Problem.of(Problem.Kind.STUCK, "合成终端已发取产物操作，但光标和背包均未收到 " + wantedItemId));
    }

    // 将光标上由 CRAFT_ITEM 拿到的成品点进玩家背包（优先同物未满堆叠格，其次空格），防止关窗丢在地上。
    private ActionStatus placeCursor() {
        SlotSnapshot cursor = menu().cursor();
        if (cursor.isEmpty()) return afterBatchPlaced();
        Optional<MenuContent.Reading> reading = menu().reading();
        if (reading.isEmpty()) {
            if (!syncWaitElapsed()) return ActionStatus.running();
            return beginClearThenClose(Problem.of(Problem.Kind.STUCK, "读取玩家背包槽位超时"));
        }
        int destSlot = findDestinationPlayerSlot(reading.get(), cursor);
        if (destSlot < 0) {
            return beginClearThenClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "背包已满，无法把合成出的 " + wantedItemId + " 从光标放进背包"));
        }
        if (!menu().click(destSlot, 0)) {
            if (!syncWaitElapsed()) return ActionStatus.running();
            return beginClearThenClose(Problem.of(Problem.Kind.STUCK, "无法点击背包槽放下光标上的合成成品"));
        }
        cursorCountBefore = cursor.count();
        resetWait();
        step = Step.WAIT_PLACED;
        return ActionStatus.progressed();
    }

    private ActionStatus waitPlaced() {
        SlotSnapshot cursor = menu().cursor();
        int nowCount = cursor.isEmpty() ? 0 : cursor.count();
        if (nowCount < cursorCountBefore) {
            crafted += (cursorCountBefore - nowCount);
            if (nowCount > 0) {
                // 原堆叠格已满仍有余量留在光标：再找下一个空格继续放完。
                cursorCountBefore = nowCount;
                resetWait();
                step = Step.PLACE_CURSOR;
                return ActionStatus.progressed();
            }
            return afterBatchPlaced();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return beginClearThenClose(Problem.of(Problem.Kind.STUCK, "点击背包槽后光标上的成品一直未放下"));
    }

    private ActionStatus afterBatchPlaced() {
        if (crafted >= wantedCount) return beginClearThenClose(null);
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isPresent() && wantedItemId.equals(view.get().resultItemId()) && view.get().resultCount() > 0) {
            step = Step.TAKE_RESULT;
        } else {
            step = Step.FILL_GRID;
        }
        resetWait();
        return ActionStatus.progressed();
    }

    static int findDestinationPlayerSlot(MenuContent.Reading reading, SlotSnapshot cursor) {
        int maxStack = Math.max(1, cursor.stack().getMaxStackSize());
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot slot = reading.playerSnapshots().get(i);
            if (!slot.isEmpty() && slot.sameIdentity(cursor) && slot.count() < maxStack) {
                return reading.playerSlotIds().get(i);
            }
        }
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            if (reading.playerSnapshots().get(i).isEmpty()) {
                return reading.playerSlotIds().get(i);
            }
        }
        return -1;
    }

    // 完工清格：先退回 ME 网络，若网格仍有余料再退回玩家背包，绝不把材料遗留在合成格中。
    private ActionStatus clearGrid() {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isEmpty() || !view.get().gridOccupied()) {
            return closeWith(closingProblem);
        }
        if (!clearedToNetwork) {
            clearedToNetwork = true;
            reads.sendClearCraftingGrid(false);
            clearWaited = 0;
            step = Step.WAIT_CLEAR;
            return ActionStatus.progressed();
        }
        if (!clearedToPlayer) {
            clearedToPlayer = true;
            reads.sendClearCraftingGrid(true);
            clearWaited = 0;
            step = Step.WAIT_CLEAR;
            return ActionStatus.progressed();
        }
        return closeWith(closingProblem);
    }

    private ActionStatus waitClear() {
        Optional<Ae2MachineReads.CraftingTermMenuView> view = reads.openCraftingTermMenu();
        if (view.isEmpty() || !view.get().gridOccupied()) {
            return closeWith(closingProblem);
        }
        if (++clearWaited <= CLEAR_WAIT_TICKS) return ActionStatus.running();
        if (clearedToPlayer) return closeWith(closingProblem);
        step = Step.CLEAR_GRID;
        return ActionStatus.progressed();
    }

    // 收场前先清合成格：结局记下，清完再按结局关窗收尾。
    private ActionStatus beginClearThenClose(Problem cause) {
        closingProblem = cause;
        step = Step.CLEAR_GRID;
        clearWaited = 0;
        return ActionStatus.progressed();
    }

    private int countInPlayer(PlayerContext player) {
        if (player != null && player.localPlayer() != null) {
            int total = 0;
            Inventory inv = player.localPlayer().getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(wantedItemId)) {
                    total += stack.getCount();
                }
            }
            return total;
        }
        return menu().reading().map(r -> {
            int total = 0;
            for (SlotSnapshot snap : r.playerSnapshots()) {
                if (!snap.isEmpty() && wantedItemId.equals(snap.key())) total += snap.count();
            }
            return total;
        }).orElse(0);
    }

    @Override protected String doing() {
        return "在合成终端 3×3 合成格手搓 " + wantedItemId + "（" + crafted + "/" + wantedCount + "）";
    }
}
