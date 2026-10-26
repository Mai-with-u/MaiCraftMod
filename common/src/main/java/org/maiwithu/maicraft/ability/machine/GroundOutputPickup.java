// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 捡起机器弹到地上的产物：朝上的锯往前弹、粉碎轮从中间那格往下漏，东西落在机器旁边那一块。
 * 一件件走过去让原版拾取吸进包（和捡挖下来的矿同一个捡法），先捡离得近的；那一块地上要的东西都没了就收手。
 * 收了几件以背包前后清点为准；捡不到的那一件放下，写进说明，不在一件东西上耗着。
 */
final class GroundOutputPickup implements OutputLook {

    private final AABB area;
    private final String itemId;
    private final Supplier<PlayerContext> contexts;
    /** 捡指定的那一件（游戏实体编号）的动作怎么建：走过去、等它从地上消失并确认是自己收的。 */
    private final IntFunction<Action> pickUpOne;
    private final Set<Integer> skipped = new HashSet<>();
    private final List<String> notes = new ArrayList<>();
    private Action current;
    private int currentId;
    private int seen = -1;
    private int carriedBefore = -1;
    private int taken;

    GroundOutputPickup(AABB area, String itemId, Supplier<PlayerContext> contexts, IntFunction<Action> pickUpOne) {
        this.area = Objects.requireNonNull(area, "area");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.pickUpOne = Objects.requireNonNull(pickUpOne, "pickUpOne");
    }

    // 先数一遍地上有几件、背包里原有几件；再一件件捡，捡完（或剩下的都捡不到）按背包清点收场。
    @Override public ActionStatus tick(TickContext tick) {
        if (seen < 0) {
            seen = drops().stream().mapToInt(drop -> drop.getItem().getCount()).sum();
            carriedBefore = carried();
        }
        if (current == null) {
            Optional<ItemEntity> next = nearest();
            if (next.isEmpty()) {
                taken = Math.max(0, carried() - carriedBefore);
                return ActionStatus.done();
            }
            currentId = next.get().getId();
            current = pickUpOne.apply(currentId);
        }
        ActionStatus status = current.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        if (status instanceof ActionStatus.Failed failed) {
            notes.add("地上一件 " + itemId + " 没捡到：" + failed.problem().message());
        }
        // 捡到了、没捡到都不再碰这一件：没捡到的换下一件，别让一件够不着的挡住后面。
        skipped.add(currentId);
        current.close();
        current = null;
        return ActionStatus.progressed();
    }

    // 那一块地上还躺着、这一趟没放下过的要的东西里，离角色最近的一件。
    private Optional<ItemEntity> nearest() {
        LocalPlayer player = player();
        if (player == null) return Optional.empty();
        return drops().stream().filter(drop -> !skipped.contains(drop.getId()))
                .min(Comparator.comparingDouble(drop -> drop.distanceToSqr(player)));
    }

    private List<ItemEntity> drops() {
        LocalPlayer player = player();
        if (player == null) return List.of();
        return player.clientLevel.getEntitiesOfClass(ItemEntity.class, area,
                drop -> drop.isAlive() && idOf(drop.getItem()).equals(itemId));
    }

    private int carried() {
        LocalPlayer player = player();
        if (player == null) return 0;
        var inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) total += stack.getCount();
        }
        return total;
    }

    private LocalPlayer player() {
        PlayerContext current = contexts.get();
        return current == null ? null : current.localPlayer();
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public int seen() {
        return Math.max(0, seen);
    }

    @Override public int taken() {
        return taken;
    }

    @Override public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.WORKING : current.interruptibility();
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    // 被叫先到安全处再停：正走过去捡的那一件交给它先停稳，没在走就现在能停。
    @Override public boolean stopSafely() {
        return current != null && current.stopSafely();
    }

    @Override public boolean stoppedSafely() {
        return current == null || current.stoppedSafely();
    }

    // 收尾：走到一半的那一件一并收尾；已经进包的照清点算数。
    @Override public void close() {
        if (current != null) current.close();
        if (carriedBefore >= 0) taken = Math.max(0, carried() - carriedBefore);
    }

    @Override public String describe() {
        return "捡起机器旁边地上的 " + itemId;
    }
}
