// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.inscriber;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.game.world.ItemMatch;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 压印器投料前自愈清理动作：当压印器的顶（0）、底（1）、中间（2）、输出（3）任一工作格里占着
 * 与当前目标配方不兼容的物品（早先遗留的半成品、异种模板、中断残留物或异种输出）时，
 * 自动走过去点开压印器界面，只把冲突工作格里的物品快速移动取回角色背包，不动兼容模板与升级卡槽（4..7），腾空后关窗。
 */
public final class Ae2InscriberClear implements Action {

    /** 打开界面并清出冲突工作格、关上界面的总期限（刻）。 */
    private static final int GIVE_UP_TICKS = 20 * 20;

    private final ShownRecipe recipe;
    private final int batches;
    private final MenuOpening opening;
    private final Set<String> placedPlates;
    private OpenedMenu menu;
    private TransferRun run;
    private Action closing;
    private String clearingItem;
    private Problem failure;
    private int waited;

    public Ae2InscriberClear(ShownRecipe recipe, int batches, MenuOpening opening, Set<String> placedPlates) {
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.batches = Math.max(1, batches);
        this.opening = Objects.requireNonNull(opening, "opening");
        this.placedPlates = placedPlates;
    }

    @Override public ActionStatus tick(TickContext tick) {
        if (menu == null) return open(tick);
        if (++waited > GIVE_UP_TICKS) {
            if (run != null && !run.idle()) run.abandon();
            menu.abandon();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "打开压印器清理冲突槽位超时", null));
        }
        if (closing != null) return finishClose(tick);
        if (!run.idle()) return run.tickAction(this::onMoveSettled);
        return startNextOrClose(tick);
    }

    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElseThrow();
        run = new TransferRun(menu);
        return ActionStatus.progressed();
    }

    // 对照当前界面四个工作格的实况找出下一个与目标配方冲突的槽位，快速移动取回背包；全腾空了就关窗。
    private ActionStatus startNextOrClose(TickContext tick) {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) {
            return failure != null ? ActionStatus.failed(failure) : ActionStatus.done();
        }
        MenuContent.Reading current = reading.get();
        List<Integer> conflicts = Ae2InscriberPlates.conflictingSlotIndices(recipe, batches, liveWorkSlots(current));
        if (conflicts.isEmpty()) {
            closing = menu.closing();
            return finishClose(tick);
        }
        int workIndex = conflicts.getFirst();
        SlotSnapshot occupied = current.containerSnapshots().get(workIndex);
        int slotId = current.containerSlotIds().get(workIndex);
        clearingItem = idOf(occupied.stack());
        String targetItem = clearingItem;
        run.begin(new TransferRun.Move(
                List.of(new MenuClick(slotId, true, 0, false)),
                new TransferRun.Check.IntoBackpack(snap -> !snap.isEmpty() && idOf(snap.stack()).equals(targetItem),
                        occupied.count()),
                "从压印器取出冲突占槽物 " + targetItem));
        return run.tickAction(this::onMoveSettled);
    }

    private ActionStatus onMoveSettled(TransferRun.MoveResult result) {
        if (result instanceof TransferRun.MoveResult.Confirmed) {
            dropRememberedPlate(clearingItem);
            clearingItem = null;
            return ActionStatus.progressed();
        }
        if (result instanceof TransferRun.MoveResult.NotMoved) {
            failure = Problem.of(Problem.Kind.INVENTORY_FULL,
                    "压印器里占着不兼容的 " + clearingItem + "，但角色背包已满取不回背包",
                    "先腾出几格背包空位再运行压印器");
            closing = menu.closing();
            return ActionStatus.progressed();
        }
        if (result instanceof TransferRun.MoveResult.MenuGone) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "清理压印器冲突槽位时界面意外关闭", null));
        }
        failure = Problem.of(Problem.Kind.STUCK,
                "从压印器取出冲突占槽物 " + clearingItem + " 时两侧件数没对上", null);
        closing = menu.closing();
        return ActionStatus.progressed();
    }

    private ActionStatus finishClose(TickContext tick) {
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        return failure != null ? ActionStatus.failed(failure) : ActionStatus.done();
    }

    private void dropRememberedPlate(String clearedItemId) {
        if (placedPlates == null || clearedItemId == null) return;
        Optional<ItemStack> sample = stackOf(clearedItemId);
        placedPlates.removeIf(spec -> spec.equals(clearedItemId)
                || (sample.isPresent() && ItemMatch.matches(sample.get(), spec)));
    }

    private static Ae2Inscribers.Slots liveWorkSlots(MenuContent.Reading reading) {
        List<SlotSnapshot> snaps = reading.containerSnapshots();
        return new Ae2Inscribers.Slots(
                shownAt(snaps, 0), shownAt(snaps, 1), shownAt(snaps, 2), shownAt(snaps, 3));
    }

    private static MachineState.Shown shownAt(List<SlotSnapshot> snaps, int index) {
        if (index >= snaps.size() || snaps.get(index).isEmpty()) return null;
        SlotSnapshot snap = snaps.get(index);
        return new MachineState.Shown(idOf(snap.stack()), snap.count());
    }

    private static Optional<ItemStack> stackOf(String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        return key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new);
    }

    private static String idOf(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public Interruptibility interruptibility() {
        if (menu == null) return opening.interruptibility();
        return run != null && run.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public void pause() {
        opening.pause();
        if (menu != null) menu.abandon();
    }

    @Override public void close() {
        opening.close();
        if (closing != null) closing.close();
        if (run != null && !run.idle()) run.abandon();
        if (menu != null && closing == null) menu.abandon();
    }

    @Override public String describe() {
        return menu == null ? opening.describe() : "腾空压印器里与当前配方冲突的槽位";
    }
}
