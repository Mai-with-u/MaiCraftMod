// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
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
 * 锻造台上要升级的那件只穿在身上：背包里没有、盔甲四格里正穿着一件配方收的，和玩家一样脱下 → 升级 → 穿回原来那一格。
 * 原来那一格这时被别的东西占了，升级好的留在背包里并照实说；升级没成，把脱下来的原件穿回去再收场，不让角色少一件甲到处走。
 * 不脱的话会去另做一件（钻石胸甲要八颗钻石），身上那件原样穿着。背包里有就用背包里的，不动身上穿的。
 * 穿回去的那件清点时照样算拿到了：拿东西清点身上几件时连穿着的护甲一起数。
 */
public final class TakesOffWornGear {

    private final ReadsEquipment equipment;
    private final GearChanges gear;
    private final BackpackView backpack;
    private final ReadsItemTags tags;

    public TakesOffWornGear(ReadsEquipment equipment, GearChanges gear, BackpackView backpack, ReadsItemTags tags) {
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.gear = Objects.requireNonNull(gear, "gear");
        this.backpack = Objects.requireNonNull(backpack, "backpack");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    /**
     * 照这条配方做 times 次，背包里还缺、身上正穿着的那几件在哪几格：一格脱一件，背包里够的那样不脱。
     * 会被拿去升级的只有盔甲四格：主手拿的本来就在快捷栏里，算背包；副手的也算身上已有的。
     *
     * @param carried 一样原料背包与副手里有几件，和来源备料用的是同一个数法
     */
    List<GearSlotName> wornToTakeOff(WorkstationRecipe recipe, int times,
            ToIntFunction<WorkstationRecipe.IngredientStack> carried) {
        List<GearSlotName> slots = new ArrayList<>();
        for (WorkstationRecipe.IngredientStack ingredient : recipe.ingredients()) {
            int missing = ingredient.count() * times - carried.applyAsInt(ingredient);
            for (GearSlotName slot : GearSlotName.armor()) {
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
     * 那一串做完，升级好的穿回脱下来的那一格；没做成就把原件穿回去，再按那一串的问题收场。
     * 脱不下来（背包没空格、游戏没认）就以那个问题收场，不接着去另做一件。
     *
     * @param upgraded 升级好的是什么（配方产出的物品 ID）；产出是一类东西说不准时为 null，只在没做成时把原件穿回去
     */
    Action thenRun(List<GearSlotName> slots, String upgraded, TaskRecords records, Supplier<Action> run) {
        return new TakeOffThenRun(List.copyOf(slots), upgraded, records, run);
    }

    /** 逐格脱下、核对那一格空了再脱下一格；都脱完才建后面那一串并交给它推进；那一串收场后逐格穿回。 */
    private final class TakeOffThenRun implements Action {
        private final List<GearSlotName> slots;
        private final String upgraded;
        private final TaskRecords records;
        private final Supplier<Action> run;
        /** 已经脱下来的每一格原来穿着什么：穿回时按格找回去。 */
        private final Map<GearSlotName, String> takenOff = new EnumMap<>(GearSlotName.class);
        private final List<GearSlotName> wearBackOrder = new ArrayList<>();
        private int next;
        private Action takingOff;
        private String wornItem;
        private Action chosen;
        private ActionStatus endedWith;
        private int wearNext;
        private Action wearing;
        private String wearingItem;

        private TakeOffThenRun(List<GearSlotName> slots, String upgraded, TaskRecords records, Supplier<Action> run) {
            this.slots = slots;
            this.upgraded = upgraded;
            this.records = records;
            this.run = run;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (endedWith != null) return wearBack(context);
            if (chosen != null) return runUpgrade(context);
            if (next >= slots.size()) {
                chosen = run.get();
                return ActionStatus.progressed();
            }
            return takeOffNext(context);
        }

        private ActionStatus takeOffNext(TickContext context) {
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
                    return finishTakingOff(ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                            "要拿去锻造台升级的" + wornItem + "穿在身上（" + slot.paramName() + "），这会儿脱不下来")));
                }
                takingOff = action.get();
            }
            ActionStatus status = takingOff.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            takingOff.close();
            takingOff = null;
            if (status instanceof ActionStatus.Failed) return finishTakingOff(status);
            // 动作做完了这一格还穿着：没等到游戏确认，不当成脱下了，也不去另做一件。
            if (equipment.slot(slot).isPresent()) {
                return finishTakingOff(ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                        "要拿去锻造台升级的" + wornItem + "没能从身上（" + slot.paramName() + "）脱下来，背包可能没空格")));
            }
            takenOff.put(slot, wornItem);
            wearBackOrder.add(slot);
            record(wornItem, "从身上（" + slot.paramName() + "）脱下来拿去锻造台升级");
            next++;
            return ActionStatus.progressed();
        }

        // 脱到一半脱不下来：前面已经脱下的先原样穿回去，再按脱不下来的问题收场。
        private ActionStatus finishTakingOff(ActionStatus failed) {
            endedWith = failed;
            return takenOff.isEmpty() ? failed : ActionStatus.progressed();
        }

        // 推进升级那一串；收场了先不交回，进穿回那一段。
        private ActionStatus runUpgrade(TickContext context) {
            ActionStatus status = chosen.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            chosen.close();
            chosen = null;
            endedWith = status;
            return takenOff.isEmpty() ? status : ActionStatus.progressed();
        }

        /**
         * 逐格穿回：升级做成了、背包里有升级好的就穿它，否则把原件穿回去。
         * 那一格这会儿穿着别的（途中被别的事穿上了）不换下它，留在背包里照实说；穿不上也照实说，都不改变升级本身的结果。
         */
        private ActionStatus wearBack(TickContext context) {
            if (wearing != null) {
                ActionStatus status = wearing.tick(context);
                if (status instanceof ActionStatus.Running) return status;
                wearing.close();
                wearing = null;
                settleWearing(wearBackOrder.get(wearNext), status);
                wearNext++;
                return ActionStatus.progressed();
            }
            while (wearNext < wearBackOrder.size()) {
                GearSlotName slot = wearBackOrder.get(wearNext);
                String original = takenOff.get(slot);
                String item = upgradedInBackpack() ? upgraded : carriedNow(original) ? original : null;
                // 背包里升级好的、原件都不在（被别的事用掉了）：没东西可穿回，看下一格。
                if (item == null) {
                    wearNext++;
                    continue;
                }
                Optional<BackpackStack> now = equipment.slot(slot);
                // 那一格已经穿着要穿的这件（别的事先替它穿上了）：不用再穿，也不多记一笔。
                if (now.isPresent() && now.get().itemId().equals(item)) {
                    wearNext++;
                    continue;
                }
                if (now.isPresent()) {
                    record(item, "身上（" + slot.paramName() + "）这会儿穿着 " + now.get().itemId()
                            + "，" + item + " 留在背包里，要换上用 equip");
                    wearNext++;
                    continue;
                }
                Optional<Action> action = gear.wear(item, slot);
                if (action.isEmpty()) {
                    record(item, "没能穿回身上（" + slot.paramName() + "），留在背包里，要穿用 equip");
                    wearNext++;
                    continue;
                }
                wearing = action.get();
                wearingItem = item;
                return ActionStatus.progressed();
            }
            return endedWith;
        }

        // 穿完核对那一格：穿上了按升级好的还是原件各记一笔；没穿上照实说留在背包里。
        private void settleWearing(GearSlotName slot, ActionStatus status) {
            boolean on = equipment.slot(slot).map(stack -> stack.itemId().equals(wearingItem)).orElse(false);
            if (on) {
                record(wearingItem, wearingItem.equals(upgraded)
                        ? "升级好穿回身上（" + slot.paramName() + "）"
                        : "没升级成，原样穿回身上（" + slot.paramName() + "）");
                return;
            }
            String why = status instanceof ActionStatus.Failed failed ? "：" + failed.problem().message() : "";
            record(wearingItem, "没能穿回身上（" + slot.paramName() + "）" + why + "，留在背包里，要穿用 equip");
        }

        // 升级做成了、背包里有升级好的那件：只有做成了才穿它，没做成穿回原件。
        private boolean upgradedInBackpack() {
            return upgraded != null && endedWith instanceof ActionStatus.Done && carriedNow(upgraded);
        }

        private boolean carriedNow(String itemId) {
            return backpack.stacks().stream().anyMatch(stack -> stack.itemId().equals(itemId));
        }

        private void record(String item, String note) {
            if (records != null) records.change(new Change(Change.Kind.OTHER, item, 1, note));
        }

        @Override public void pause() {
            if (wearing != null) wearing.pause();
            else if (chosen != null) chosen.pause();
            else if (takingOff != null) takingOff.pause();
        }

        @Override public void close() {
            if (wearing != null) wearing.close();
            if (chosen != null) chosen.close();
            if (takingOff != null) takingOff.close();
        }

        @Override public Interruptibility interruptibility() {
            if (wearing != null) return wearing.interruptibility();
            if (chosen != null) return chosen.interruptibility();
            return takingOff != null ? takingOff.interruptibility() : Interruptibility.BETWEEN_ACTIONS;
        }

        @Override public String describe() {
            if (wearing != null) return "把" + wearingItem + "穿回身上";
            if (chosen != null) return chosen.describe();
            return "把身上穿着的" + (wornItem == null ? "那件" : wornItem) + "脱下来拿去锻造台升级";
        }
    }
}
