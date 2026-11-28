// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2MenuWork;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 无线访问点增幅卡差额放取动作：打开 {@code ae2:wireless_access_point} 界面，
 * 只操作 {@code STORAGE} 槽（不碰无线终端绑定输入/输出格），按差额放入或取出
 * {@code ae2:wireless_booster}，关窗前把最新张数刷新到会话缓存。
 */
final class WirelessBoosterChange extends Ae2MenuWork {

    private enum Step { SYNC, ADJUST, RETURN_CURSOR }

    private final Ae2PartControl control;
    private final Ae2PartCache cache;
    private final BlockPos pos;
    private final int wanted;
    private final String boosterItemId;

    private Step step = Step.SYNC;
    private int boosterSlot = -1;
    private int returnSlot = -1;
    private int lastBoosterCount = -1;
    private int lastCursorCount = -1;
    private boolean sent;

    WirelessBoosterChange(Ae2PartControl control, Ae2PartCache cache, PlayerServices services, BlockPos pos,
                          int wanted, Permissions permissions) {
        this(control, cache, pos, wanted, Ae2PartKeys.WIRELESS_BOOSTER,
                new ClientMenuOpening(pos, null, permissions, services.menuLayouts(),
                        services.approach(), services.interactions(), services.toMainhand(), services.context()));
    }

    /** 离线测试用构造：允许注入菜单打开替身与替身增幅卡物品 ID。 */
    WirelessBoosterChange(Ae2PartControl control, Ae2PartCache cache, BlockPos pos, int wanted, String boosterItemId,
                          MenuOpening opening) {
        super(opening, "无线访问点的界面没打开");
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.pos = pos.immutable();
        this.wanted = wanted;
        this.boosterItemId = Objects.requireNonNull(boosterItemId, "boosterItemId");
    }

    @Override protected ActionStatus work(TickContext tick) {
        return switch (step) {
            case SYNC -> waitSync();
            case ADJUST -> adjust();
            case RETURN_CURSOR -> returnCursor();
        };
    }

    private ActionStatus waitSync() {
        Optional<Ae2PartControl.OpenMenuSnapshot> snap = control.readOpenMenu();
        if (snap.isPresent() && menu().reading().isPresent()) {
            if (snap.get().storageSlots().isEmpty()) {
                return stop(Problem.of(Problem.Kind.UNSUPPORTED, "无线访问点界面认不出增幅卡槽"));
            }
            boosterSlot = snap.get().storageSlots().getFirst().menuIndex();
            step = Step.ADJUST;
            resetWait();
            return ActionStatus.progressed();
        }
        if (!syncWaitElapsed()) return ActionStatus.running();
        return stop(Problem.of(Problem.Kind.STUCK, "无线访问点界面一直没同步完成"));
    }

    // 按当前增幅卡数量与目标值的差额逐步放入或取出，每步等服务端回显后再走下一步。
    private ActionStatus adjust() {
        if (menu().busy()) return ActionStatus.running();
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        Optional<MenuContent.Reading> reading = menu().reading();
        if (opt.isEmpty() || reading.isEmpty()) {
            return stop(Problem.of(Problem.Kind.TARGET_GONE, "无线访问点的界面被关掉了"));
        }
        int have = currentBoosters(opt.get());
        int cursorCount = menu().cursorEmpty() ? 0 : menu().cursor().count();
        if (sent) {
            if (have != lastBoosterCount || cursorCount != lastCursorCount) {
                sent = false;
                resetWait();
            } else if (syncWaitElapsed()) {
                return stop(Problem.of(Problem.Kind.STUCK, "调整无线增幅卡数量时界面一直没变化"));
            } else {
                return ActionStatus.running();
            }
        }
        if (have == wanted) {
            if (menu().cursorEmpty()) return completeWith(have);
            step = Step.RETURN_CURSOR;
            sent = false;
            resetWait();
            return ActionStatus.progressed();
        }
        if (have < wanted) {
            return addBooster(reading.get(), have);
        }
        return removeBooster(reading.get(), have);
    }

    private ActionStatus addBooster(MenuContent.Reading reading, int have) {
        int need = wanted - have;
        if (menu().cursorEmpty()) {
            int carryTotal = countCarriedBoosters(reading);
            if (carryTotal < need) {
                return stop(Problem.of(Problem.Kind.NEED_ITEM,
                        "背包里无线增幅卡不够：还需要 " + need + " 张 " + boosterItemId
                                + "，身上只有 " + carryTotal + " 张"));
            }
            int slot = findCarriedBoosterSlot(reading);
            if (slot < 0) return stop(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有 " + boosterItemId));
            if (!menu().click(slot, 0)) return ActionStatus.running();
            menu().noteCursorTakenFrom(slot);
            returnSlot = slot;
            lastBoosterCount = have;
            lastCursorCount = 0;
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        // 光标上拿着增幅卡：需要数量大于等于手上整堆就左键全放，否则右键放 1 张。
        int button = menu().cursor().count() <= need ? 0 : 1;
        if (!menu().click(boosterSlot, button)) return ActionStatus.running();
        lastBoosterCount = have;
        lastCursorCount = menu().cursor().count();
        sent = true;
        resetWait();
        return ActionStatus.progressed();
    }

    private ActionStatus removeBooster(MenuContent.Reading reading, int have) {
        if (wanted == 0 && menu().cursorEmpty()) {
            if (!menu().quickMove(boosterSlot)) return ActionStatus.running();
            lastBoosterCount = have;
            lastCursorCount = 0;
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        if (menu().cursorEmpty()) {
            if (!menu().click(boosterSlot, 0)) return ActionStatus.running();
            menu().noteCursorTakenFrom(boosterSlot);
            returnSlot = findEmptyOrStackablePlayerSlot(reading);
            lastBoosterCount = have;
            lastCursorCount = 0;
            sent = true;
            resetWait();
            return ActionStatus.progressed();
        }
        // 若进入减卡分支时光标上已有残余物品，先回收到背包再继续，避免停在 running 死等。
        step = Step.RETURN_CURSOR;
        returnSlot = -1;
        sent = false;
        resetWait();
        return ActionStatus.progressed();
    }

    // 把光标里剩余增幅卡放回背包：单格堆叠上限 64 满后光标若仍有余量，自动换下一空格分批放完。
    private ActionStatus returnCursor() {
        if (menu().busy()) return ActionStatus.running();
        if (menu().cursorEmpty()) {
            Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
            if (opt.isPresent() && currentBoosters(opt.get()) != wanted) {
                step = Step.ADJUST;
                sent = false;
                resetWait();
                return ActionStatus.progressed();
            }
            return completeWith(wanted);
        }
        int cursorCount = menu().cursor().count();
        if (sent) {
            if (cursorCount < lastCursorCount) {
                // 上一次点击已把部分增幅卡补满该格，重置状态重新找下一格放剩余部分。
                sent = false;
                resetWait();
                returnSlot = -1;
            } else if (syncWaitElapsed()) {
                return stop(Problem.of(Problem.Kind.STUCK, "把余下的无线增幅卡放回背包超时"));
            } else {
                return ActionStatus.running();
            }
        }
        Optional<MenuContent.Reading> reading = menu().reading();
        if (reading.isEmpty()) return stop(Problem.of(Problem.Kind.TARGET_GONE, "无线访问点的界面被关掉了"));
        int target = returnSlot >= 0 ? returnSlot : findEmptyOrStackablePlayerSlot(reading.get());
        if (target < 0) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "背包已满，放不下取出的无线增幅卡"));
        }
        if (!menu().click(target, 0)) return ActionStatus.running();
        lastCursorCount = cursorCount;
        sent = true;
        resetWait();
        return ActionStatus.progressed();
    }

    private ActionStatus completeWith(int boosters) {
        cache.recordLocal(pos, null, Map.of("boosters", Integer.toString(boosters)), Map.of("boosters", boosters));
        return stop(null);
    }

    private static int currentBoosters(Ae2PartControl.OpenMenuSnapshot snap) {
        if (snap.wirelessBoosters().isPresent()) return snap.wirelessBoosters().getAsInt();
        if (snap.storageSlots().isEmpty() || snap.storageSlots().getFirst().isEmpty()) return 0;
        return (int) snap.storageSlots().getFirst().count();
    }

    private int countCarriedBoosters(MenuContent.Reading reading) {
        int sum = 0;
        for (SlotSnapshot snap : reading.playerSnapshots()) {
            if (!snap.isEmpty() && boosterItemId.equals(snap.key())) {
                sum += snap.count();
            }
        }
        return sum;
    }

    private int findCarriedBoosterSlot(MenuContent.Reading reading) {
        List<SlotSnapshot> snaps = reading.playerSnapshots();
        List<Integer> ids = reading.playerSlotIds();
        for (int i = 0; i < snaps.size(); i++) {
            if (!snaps.get(i).isEmpty() && boosterItemId.equals(snaps.get(i).key())) {
                return ids.get(i);
            }
        }
        return -1;
    }

    private int findEmptyOrStackablePlayerSlot(MenuContent.Reading reading) {
        List<SlotSnapshot> snaps = reading.playerSnapshots();
        List<Integer> ids = reading.playerSlotIds();
        for (int i = 0; i < snaps.size(); i++) {
            SlotSnapshot snap = snaps.get(i);
            if (!snap.isEmpty() && boosterItemId.equals(snap.key()) && snap.count() < 64) {
                return ids.get(i);
            }
        }
        for (int i = 0; i < snaps.size(); i++) {
            if (snaps.get(i).isEmpty()) return ids.get(i);
        }
        return -1;
    }

    // 收场前界面还开着就把最新增幅卡张数刷进缓存：做成与没做成都要给下一次读回最新现场。
    private ActionStatus stop(Problem reason) {
        if (reason != null) {
            control.readOpenMenu().ifPresent(snap -> Ae2PartSnapshots.flushToCache(cache, pos, null, snap, null, null));
        }
        return closeWith(reason);
    }

    // 光标上拿着增幅卡时停不得，一关界面它就落进流程外。
    @Override public Interruptibility interruptibility() {
        return step == Step.ADJUST || step == Step.RETURN_CURSOR
                ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override protected String doing() {
        return "调整无线访问点的增幅卡数量为 " + wanted;
    }
}
