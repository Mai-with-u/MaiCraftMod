// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 穿卸装备的任务：穿上时交给穿卸接缝做完再核对栏位；卸下时先腾出一格地方，
 * 再逐格取下放包。每一个栏位都分开确认：取下了记一条变化，没等到确认记进
 * unconfirmed，还在身上的记进 still_worn——卸了三格、剩一格穿着，就是部分完成，
 * 不再冒充全部卸完。一个栏位只动手一次，没卸下来也不原地反复拽。
 */
final class EquipTask extends PhasedTask<EquipTask.Phase> {

    /** 任务的进度：卸下时"腾地方 → 取下一格"交替进行；穿上只有动手一步。 */
    enum Phase { FREE_SPACE, HANDLING }

    private final EquipInput input;
    private final ReadsEquipment equipment;
    private final Optional<InventorySpace> space;
    private final Optional<GearChanges> gear;
    /** 这次已经处理过的栏位。 */
    private final Set<GearSlotName> processed = new HashSet<>();
    /** 动手了但没能确认结果的栏位。 */
    private final List<GearSlotName> unconfirmedSlots = new ArrayList<>();
    /** 确认完成了的栏位数。 */
    private int settled;
    /** 动作当场失败（界面没开上、那件没找到）的栏位：穿上时它们没穿上，要列进还差的。 */
    private final List<GearSlotName> failedSlots = new ArrayList<>();
    /** 最近一次动作失败的原因：一个栏位都没弄成时整件事按它失败。 */
    private Problem lastFailure;
    /** 当前动手的栏位，以及动手前栏位里是什么（卸下记账用）。 */
    private GearSlotName currentSlot;
    private String wornAtStart;

    EquipTask(EquipInput input, ReadsEquipment equipment, Optional<InventorySpace> space,
            Optional<GearChanges> gear) {
        super(input.unequip() ? "卸下装备" : "穿上装备",
                input.unequip() ? Phase.FREE_SPACE : Phase.HANDLING,
                new ProgressTracker(100, 20L * 60 * 2));
        this.input = input;
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.space = Objects.requireNonNull(space, "space");
        this.gear = Objects.requireNonNull(gear, "gear");
    }

    @Override
    protected Action enter(Phase phase) {
        // 卸下前先腾一格：腾的时候存了丢了什么、点了没能确认的，由腾地方的动作直接记进本任务的结果。
        if (phase == Phase.FREE_SPACE) {
            return space.map(room -> room.makeRoom(1, "卸下装备", Set.of(), input.permissions(), records()))
                    .orElse(null);
        }
        currentSlot = nextUnprocessedSlot().orElse(null);
        if (currentSlot == null) return null;
        // 动手前抄下栏位里原来是什么，取下成功后变化记录有账可对。
        wornAtStart = equipment.slot(currentSlot)
                .map(stack -> stack.itemId())
                .orElse(currentSlot.paramName());
        return gear.flatMap(changes -> input.unequip()
                        ? changes.takeOff(currentSlot)
                        : changes.wear(input.itemId(), currentSlot))
                .orElse(null);
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        if (phase == Phase.FREE_SPACE) {
            return makeRoom(context);
        }
        if (currentSlot == null) {
            return settle();
        }
        if (action() == null) {
            return Next.fail(new Problem(Problem.Kind.UNSUPPORTED,
                    (input.unequip() ? "取下装备" : "穿上装备") + "的界面操作还没接上，做不了", null));
        }
        GearSlotName slot = currentSlot;
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> afterHandled(slot);
            case ActionStatus.Failed failed -> {
                processed.add(slot);
                failedSlots.add(slot);
                lastFailure = failed.problem();
                // 试过的动作按栏位说：手部是"拿到主手"，护甲是"穿上 头上"。
                recordAttempt(input.actionLabel(slot), failed.problem().message());
                yield nextSlotOrSettle();
            }
        };
    }

    // 卸下前先保证有一格空位放取下的东西；腾地方的模型没接上就直接试着取下，装不装得下由游戏自己结算。
    private Next<Phase> makeRoom(TickContext context) {
        if (action() == null) {
            return Next.go(Phase.HANDLING, "没有腾背包的模型可用，直接试着取下");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> Next.go(Phase.HANDLING, "腾出地方了");
            // 腾不动要动贵重品：把事实如实带回，不悄悄丢玩家的东西。
            case ActionStatus.Failed failed -> Next.fail(new Problem(Problem.Kind.INVENTORY_FULL,
                    "背包腾不出放卸下装备的地方：" + failed.problem().message(),
                    "同意动贵重品，或先整理背包"));
        };
    }

    // 一个栏位动手完了：核对栏位现在的内容，卸下的看空没空，穿上的看是不是这件。
    private Next<Phase> afterHandled(GearSlotName slot) {
        processed.add(slot);
        boolean empty = equipment.slot(slot).isEmpty();
        if (input.unequip()) {
            if (empty) {
                settled++;
                recordChange(new Change(Change.Kind.ITEM_GAINED, wornAtStart, 1,
                        "从 " + slot.displayName() + " 取下放进了背包"));
            } else {
                // 动作"做完"了栏位还有东西：没等到确认，收尾时按仍穿着如实列出。
                unconfirmedSlots.add(slot);
                recordUnconfirmed(new Change(Change.Kind.OTHER, wornAtStart, 1,
                        "从 " + slot.displayName() + " 取下的操作没能确认"));
            }
        } else if (!empty && equipment.slot(slot).get().itemId().equals(input.itemId())) {
            settled++;
            // 穿好的说法跟着动作词走：手部是"拿到了主手"，护甲是"穿在了 头上"。
            recordChange(new Change(Change.Kind.OTHER, input.itemId(), 1, input.wornNote(slot)));
        } else {
            // 穿的动作做完了，栏位里却不是这件：没等到确认，不冒充穿上了。
            unconfirmedSlots.add(slot);
            recordUnconfirmed(new Change(Change.Kind.OTHER, input.itemId(), 1, input.unconfirmedNote(slot)));
        }
        return nextSlotOrSettle();
    }

    private Next<Phase> nextSlotOrSettle() {
        return nextUnprocessedSlot().isPresent()
                ? Next.go(input.unequip() ? Phase.FREE_SPACE : Phase.HANDLING, "下一个栏位")
                : settle();
    }

    private Next<Phase> settle() {
        List<GearSlotName> worn = stillWornNow();
        String label = input.actionWord();
        // 这次动的是单个手部栏位：成功句就是动作本身（"拿到主手"），不再带护甲那样的"（N 个栏位）"计数。
        boolean singleHand = EquipInput.handSlot(input.slot());
        // 一个栏位都没弄成、又都是动作当场失败：按失败收场带上原因，不把"0 个栏位"说成完成。
        if (settled == 0 && unconfirmedSlots.isEmpty() && lastFailure != null) {
            return Next.fail(lastFailure);
        }
        // 穿上时动作失败的栏位没穿上（卸下失败的栏位还穿着，由 stillWornNow 列出）。
        List<GearSlotName> notWorn = input.unequip() ? List.of() : List.copyOf(failedSlots);
        if (unconfirmedSlots.isEmpty() && worn.isEmpty() && notWorn.isEmpty()) {
            return Next.done(TaskResult.done(singleHand ? label : label + "完成（" + settled + " 个栏位）"));
        }
        if (!notWorn.isEmpty()) {
            // 没做成的收尾跟着动作词走：手部是"没拿到"，护甲是"没穿上"。
            String notDoneTail = singleHand ? " 没拿到" : " 没穿上";
            List<String> left = new ArrayList<>(notWorn.stream().map(input::actionLabel).toList());
            unconfirmedSlots.forEach(s -> left.add(input.confirmLabel(s)));
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            label + "做了一部分：" + String.join("、",
                                    notWorn.stream().map(GearSlotName::displayName).toList()) + notDoneTail)
                    .problem(lastFailure).remaining(left).build());
        }
        if (worn.isEmpty()) {
            return Next.done(TaskResult.builder(TaskResult.Status.PARTIAL,
                            label + "的操作没能全部确认，做过什么见 unconfirmed")
                    .remaining(unconfirmedSlots.stream().map(input::confirmLabel).toList())
                    .build());
        }
        List<String> wornNames = worn.stream().map(GearSlotName::displayName).toList();
        TaskResult.Builder builder = TaskResult.builder(TaskResult.Status.PARTIAL,
                label + "做了一部分：还穿着 " + String.join("、", wornNames));
        builder.remaining(worn.stream().map(s -> "仍穿着 " + s.displayName()).toList());
        builder.details(new EquipDetails(wornNames));
        return Next.done(builder.build());
    }

    // 这次没卸完、现在还穿在身上的栏位。
    private List<GearSlotName> stillWornNow() {
        List<GearSlotName> worn = new ArrayList<>();
        if (input.unequip()) {
            for (GearSlotName slot : input.slots()) {
                if (equipment.slot(slot).isPresent()) worn.add(slot);
            }
        }
        return worn;
    }

    private Optional<GearSlotName> nextUnprocessedSlot() {
        return input.slots().stream()
                .filter(slot -> !processed.contains(slot))
                // 卸下时已经空着的栏位不用动手：整套卸下时中间那几格本来就是空的。
                .filter(slot -> !input.unequip() || equipment.slot(slot).isPresent())
                .findFirst();
    }

    @Override
    public boolean resumesAnywhere() {
        // 穿卸随身装备不挑地点，被自卫撤离后接着完成剩余栏位。
        return true;
    }

    /** 没卸下来、仍穿着的栏位（中文说法，如 头上）；只有卸下且部分完成时非空。 */
    record EquipDetails(List<String> stillWorn) implements ResultDetails {
        public EquipDetails {
            stillWorn = List.copyOf(stillWorn);
        }
    }
}
