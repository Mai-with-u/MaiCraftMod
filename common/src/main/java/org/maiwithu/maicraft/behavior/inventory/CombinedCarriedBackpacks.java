// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 多个随身背包接缝的组合聚合：当同时装着多种随身容器联动（例如精妙背包与 AE2 便携存储元件）时，
 * 腾背包不再只看登记顺序里的第一只，而是把全部随身容器的空槽求和，存放时按登记顺序逐个尝试，
 * 直到有一只接手并成功存进，或全部都放不下再如实回报。
 */
public final class CombinedCarriedBackpacks implements CarriedBackpack {

    private final List<CarriedBackpack> backpacks;

    private CombinedCarriedBackpacks(List<CarriedBackpack> backpacks) {
        this.backpacks = List.copyOf(backpacks);
    }

    /**
     * 把登记的多个随身背包实现合成一个入口：空表给空、单个直接原样返回、两个及以上包成顺序组合。
     */
    public static Optional<CarriedBackpack> of(List<CarriedBackpack> backpacks) {
        if (backpacks == null || backpacks.isEmpty()) return Optional.empty();
        List<CarriedBackpack> cleaned = backpacks.stream().filter(Objects::nonNull).toList();
        if (cleaned.isEmpty()) return Optional.empty();
        if (cleaned.size() == 1) return Optional.of(cleaned.getFirst());
        return Optional.of(new CombinedCarriedBackpacks(cleaned));
    }

    // 所有随身容器剩余空槽求和：第一只没背或已满时，第二只的空槽仍会被腾背包计划看见。
    @Override public int freeSlots() {
        long total = 0;
        for (CarriedBackpack backpack : backpacks) {
            total += Math.max(0, backpack.freeSlots());
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    // 顺序挑第一只有空位且愿意接手这堆物品的随身容器；若执行中途放不下，自动接着试后面的随身容器。
    @Override public Optional<Action> store(BackpackStack stack, TaskRecords records) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(records, "records");
        for (int i = 0; i < backpacks.size(); i++) {
            CarriedBackpack candidate = backpacks.get(i);
            if (candidate.freeSlots() <= 0) continue;
            Optional<Action> started = candidate.store(stack, records);
            if (started.isPresent()) {
                return Optional.of(new SequentialStore(i, started.get(), stack, records));
            }
        }
        return Optional.empty();
    }

    // 汇总全部随身容器的观察摘要，保持各联动的登记顺序。
    @Override public List<CarriedPack> carried() {
        List<CarriedPack> all = new ArrayList<>();
        for (CarriedBackpack backpack : backpacks) {
            all.addAll(backpack.carried());
        }
        return List.copyOf(all);
    }

    /**
     * 顺序存放动作：先推进当前接手的那只随身容器；如果那只实际打不开、已满或过滤拒收导致失败，
     * 收尾当前动作后继续问下一只有空槽的随身容器，直到有一只存成或全部试完后报出最后一次失败原因。
     */
    private final class SequentialStore implements Action {

        private int currentIndex;
        private Action currentAction;
        private final BackpackStack stack;
        private final TaskRecords records;

        SequentialStore(int firstIndex, Action firstAction, BackpackStack stack, TaskRecords records) {
            this.currentIndex = firstIndex;
            this.currentAction = firstAction;
            this.stack = stack;
            this.records = records;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (currentAction == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "随身背包都放不下这堆物品"));
            }
            ActionStatus status = currentAction.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            if (status instanceof ActionStatus.Done) {
                closeCurrent();
                return status;
            }
            Problem problem = ((ActionStatus.Failed) status).problem();
            closeCurrent();
            // 当前这只没存成：按顺序找下一只有空槽且接手这堆物品的随身容器接着试。
            for (int next = currentIndex + 1; next < backpacks.size(); next++) {
                CarriedBackpack candidate = backpacks.get(next);
                if (candidate.freeSlots() <= 0) continue;
                Optional<Action> action = candidate.store(stack, records);
                if (action.isPresent()) {
                    currentIndex = next;
                    currentAction = action.get();
                    return ActionStatus.progressed();
                }
            }
            return ActionStatus.failed(problem);
        }

        @Override public void pause() {
            if (currentAction != null) currentAction.pause();
        }

        @Override public void close() {
            closeCurrent();
        }

        @Override public Interruptibility interruptibility() {
            return currentAction == null ? Interruptibility.WORKING : currentAction.interruptibility();
        }

        @Override public String describe() {
            return currentAction == null ? "放进随身背包" : currentAction.describe();
        }

        private void closeCurrent() {
            if (currentAction != null) {
                currentAction.close();
                currentAction = null;
            }
        }
    }
}
