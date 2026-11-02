// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 锻造台上要升级的那件只穿在身上：背包里没有、盔甲四格里正穿着一件配方收的，先经背包界面脱下来放进背包，再照常升级。
 * 升级好的留在背包里，不替角色穿回去——拿东西是往背包里凑，穿回去清点身上就数不到这一件，会当成没拿到；
 * 不脱的话会去另做一件（钻石胸甲要八颗钻石），身上那件原样穿着。背包里有就用背包里的，不动身上穿的。
 */
public final class TakesOffWornGear {

    /** 会被拿去升级的只有盔甲四格：主手拿的本来就在快捷栏里，算背包；副手的也算身上已有的。 */
    private static final List<GearSlotName> ARMOR = List.of(
            GearSlotName.HEAD, GearSlotName.CHEST, GearSlotName.LEGS, GearSlotName.FEET);

    private final ReadsEquipment equipment;
    private final GearChanges gear;
    private final ReadsItemTags tags;

    public TakesOffWornGear(ReadsEquipment equipment, GearChanges gear, ReadsItemTags tags) {
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.gear = Objects.requireNonNull(gear, "gear");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    /**
     * 照这条配方做 times 次，背包里还缺、身上正穿着的那几件在哪几格：一格脱一件，背包里够的那样不脱。
     *
     * @param carried 一样原料背包与副手里有几件，和来源备料用的是同一个数法
     */
    List<GearSlotName> wornToTakeOff(WorkstationRecipe recipe, int times,
            ToIntFunction<WorkstationRecipe.IngredientStack> carried) {
        List<GearSlotName> slots = new ArrayList<>();
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            int missing = ingredient.count() * times - carried.applyAsInt(ingredient);
            for (GearSlotName slot : ARMOR) {
                if (missing <= 0) break;
                if (slots.contains(slot)) continue;
                Optional<BackpackStack> worn = equipment.slot(slot);
                if (worn.isPresent() && ingredient.accepts(worn.get().itemId(), tags.tagsOf(worn.get().itemId()))) {
                    slots.add(slot);
                    missing--;
                }
            }
        }
        return slots;
    }

    /** 估算时用：还缺的原料里，这几格身上穿着的能顶上的去掉，剩下的才是真要去弄的。 */
    List<ItemRequest> notWorn(List<ItemRequest> missing, List<GearSlotName> slots) {
        List<String> wornItems = new ArrayList<>();
        for (GearSlotName slot : slots) equipment.slot(slot).ifPresent(stack -> wornItems.add(stack.itemId()));
        List<ItemRequest> left = new ArrayList<>();
        for (ItemRequest request : missing) {
            int covered = 0;
            for (var iterator = wornItems.iterator(); iterator.hasNext() && covered < request.count(); ) {
                String itemId = iterator.next();
                if (request.wanted().matches(itemId, tags.tagsOf(itemId))) {
                    iterator.remove();
                    covered++;
                }
            }
            if (covered < request.count()) left.add(request.just(request.count() - covered));
        }
        return left;
    }

    /**
     * 先把这几格脱下来放进背包、每格记一笔，再按背包里现在的东西建动手的那一串：先脱后建，备料才数得到脱下来的那件。
     * 脱不下来（背包没空格、游戏没认）就以那个问题收场，不接着去另做一件。
     */
    Action thenRun(List<GearSlotName> slots, TaskRecords records, Supplier<Action> run) {
        return new TakeOffThenRun(List.copyOf(slots), records, run);
    }

    /** 逐格脱下、核对那一格空了再脱下一格，都脱完才建后面那一串并交给它推进。 */
    private final class TakeOffThenRun implements Action {
        private final List<GearSlotName> slots;
        private final TaskRecords records;
        private final Supplier<Action> run;
        private int next;
        private Action takingOff;
        private String wornItem;
        private Action chosen;

        private TakeOffThenRun(List<GearSlotName> slots, TaskRecords records, Supplier<Action> run) {
            this.slots = slots;
            this.records = records;
            this.run = run;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (chosen != null) return chosen.tick(context);
            if (next >= slots.size()) {
                chosen = run.get();
                return ActionStatus.progressed();
            }
            GearSlotName slot = slots.get(next);
            if (takingOff == null) {
                Optional<BackpackStack> worn = equipment.slot(slot);
                // 身上这一格已经空了（被别的事先脱了）：没什么可脱，看下一格。
                if (worn.isEmpty()) {
                    next++;
                    return ActionStatus.progressed();
                }
                wornItem = worn.get().itemId();
                Optional<Action> action = gear.takeOff(slot);
                if (action.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                            "要拿去锻造台升级的" + wornItem + "穿在身上（" + slot.paramName() + "），这会儿脱不下来"));
                }
                takingOff = action.get();
            }
            ActionStatus status = takingOff.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            takingOff.close();
            takingOff = null;
            if (status instanceof ActionStatus.Failed failed) return failed;
            // 动作做完了这一格还穿着：没等到游戏确认，不当成脱下了，也不去另做一件。
            if (equipment.slot(slot).isPresent()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "要拿去锻造台升级的" + wornItem + "没能从身上（" + slot.paramName() + "）脱下来，背包可能没空格"));
            }
            if (records != null) {
                records.change(new Change(Change.Kind.OTHER, wornItem, 1, "从身上（" + slot.paramName()
                        + "）脱下来拿去锻造台升级；升级好的放在背包里，要穿用 equip"));
            }
            next++;
            return ActionStatus.progressed();
        }

        @Override public void pause() {
            if (chosen != null) chosen.pause();
            else if (takingOff != null) takingOff.pause();
        }

        @Override public void close() {
            if (chosen != null) chosen.close();
            else if (takingOff != null) takingOff.close();
        }

        @Override public Interruptibility interruptibility() {
            if (chosen != null) return chosen.interruptibility();
            return takingOff != null ? takingOff.interruptibility() : Interruptibility.BETWEEN_ACTIONS;
        }

        @Override public String describe() {
            if (chosen != null) return chosen.describe();
            return "把身上穿着的" + (wornItem == null ? "那件" : wornItem) + "脱下来拿去锻造台升级";
        }
    }
}
