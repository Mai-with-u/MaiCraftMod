// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 无线访问点增幅卡差额放取动作：打开 {@code ae2:wireless_access_point} 界面，
 * 只操作 {@code STORAGE} 槽（不碰无线终端绑定输入/输出格），按差额放入或取出
 * {@code ae2:wireless_booster}，关窗前把最新张数刷新到会话缓存。
 */
final class WirelessBoosterChange implements Action {

    private static final int WAIT_TICKS = 40;

    private enum Stage { OPEN, SYNC, ADJUST, RETURN_CURSOR, CLOSE, DONE }

    private final Ae2PartControl control;
    private final Ae2PartCache cache;
    private final BlockPos pos;
    private final int wanted;
    private final String boosterItemId;
    private final Supplier<MenuOpening> openingFactory;

    private Stage stage = Stage.OPEN;
    private MenuOpening opening;
    private OpenedMenu menu;
    private Action closing;
    private int waited;
    private int boosterSlot = -1;
    private int returnSlot = -1;
    private int lastBoosterCount = -1;
    private int lastCursorCount = -1;
    private boolean sent;
    private Problem problem;

    WirelessBoosterChange(Ae2PartControl control, Ae2PartCache cache, PlayerServices services, BlockPos pos,
                          int wanted, Permissions permissions) {
        this(control, cache, pos, wanted, Ae2PartKeys.WIRELESS_BOOSTER,
                () -> new ClientMenuOpening(pos, null, permissions, services.menuLayouts(),
                        services.approach(), services.interactions(), services.toMainhand(), services.context()));
    }

    /** 离线测试用构造：允许注入菜单打开替身与替身增幅卡物品 ID。 */
    WirelessBoosterChange(Ae2PartControl control, Ae2PartCache cache, BlockPos pos, int wanted, String boosterItemId,
                          Supplier<MenuOpening> openingFactory) {
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.pos = pos.immutable();
        this.wanted = wanted;
        this.boosterItemId = Objects.requireNonNull(boosterItemId, "boosterItemId");
        this.openingFactory = Objects.requireNonNull(openingFactory, "openingFactory");
    }

    @Override public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case OPEN -> open(context);
            case SYNC -> waitSync();
            case ADJUST -> adjust();
            case RETURN_CURSOR -> returnCursor();
            case CLOSE -> closeMenu(context);
            case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    private ActionStatus open(TickContext context) {
        if (opening == null) opening = openingFactory.get();
        ActionStatus status = opening.tick(context);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "无线访问点的界面没打开"));
        stage = Stage.SYNC;
        waited = 0;
        return ActionStatus.progressed();
    }

    private ActionStatus waitSync() {
        Optional<Ae2PartControl.OpenMenuSnapshot> snap = control.readOpenMenu();
        if (snap.isPresent() && menu.reading().isPresent()) {
            if (snap.get().storageSlots().isEmpty()) {
                return closeWith(Problem.of(Problem.Kind.UNSUPPORTED, "无线访问点界面认不出增幅卡槽"));
            }
            boosterSlot = snap.get().storageSlots().getFirst().menuIndex();
            stage = Stage.ADJUST;
            waited = 0;
            return ActionStatus.progressed();
        }
        if (++waited > WAIT_TICKS) {
            return closeWith(Problem.of(Problem.Kind.STUCK, "无线访问点界面一直没同步完成"));
        }
        return ActionStatus.running();
    }

    // 按当前增幅卡数量与目标值的差额逐步放入或取出，每步等服务端回显后再走下一步。
    private ActionStatus adjust() {
        if (menu.busy()) return ActionStatus.running();
        Optional<Ae2PartControl.OpenMenuSnapshot> opt = control.readOpenMenu();
        Optional<MenuContent.Reading> reading = menu.reading();
        if (opt.isEmpty() || reading.isEmpty()) {
            return closeWith(Problem.of(Problem.Kind.TARGET_GONE, "无线访问点的界面被关掉了"));
        }
        int have = currentBoosters(opt.get());
        int cursorCount = menu.cursorEmpty() ? 0 : menu.cursor().count();
        if (sent) {
            if (have != lastBoosterCount || cursorCount != lastCursorCount) {
                sent = false;
                waited = 0;
            } else if (++waited > WAIT_TICKS) {
                return closeWith(Problem.of(Problem.Kind.STUCK, "调整无线增幅卡数量时界面一直没变化"));
            } else {
                return ActionStatus.running();
            }
        }
        if (have == wanted) {
            if (menu.cursorEmpty()) return completeWith(have);
            stage = Stage.RETURN_CURSOR;
            sent = false;
            waited = 0;
            return ActionStatus.progressed();
        }
        if (have < wanted) {
            return addBooster(reading.get(), have);
        }
        return removeBooster(reading.get(), have);
    }

    private ActionStatus addBooster(MenuContent.Reading reading, int have) {
        int need = wanted - have;
        if (menu.cursorEmpty()) {
            int carryTotal = countCarriedBoosters(reading);
            if (carryTotal < need) {
                return closeWith(Problem.of(Problem.Kind.NEED_ITEM,
                        "背包里无线增幅卡不够：还需要 " + need + " 张 " + boosterItemId
                                + "，身上只有 " + carryTotal + " 张"));
            }
            int slot = findCarriedBoosterSlot(reading);
            if (slot < 0) return closeWith(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有 " + boosterItemId));
            if (!menu.click(slot, 0)) return ActionStatus.running();
            menu.noteCursorTakenFrom(slot);
            returnSlot = slot;
            lastBoosterCount = have;
            lastCursorCount = 0;
            sent = true;
            waited = 0;
            return ActionStatus.progressed();
        }
        // 光标上拿着增幅卡：需要数量大于等于手上整堆就左键全放，否则右键放 1 张。
        int button = menu.cursor().count() <= need ? 0 : 1;
        if (!menu.click(boosterSlot, button)) return ActionStatus.running();
        lastBoosterCount = have;
        lastCursorCount = menu.cursor().count();
        sent = true;
        waited = 0;
        return ActionStatus.progressed();
    }

    private ActionStatus removeBooster(MenuContent.Reading reading, int have) {
        if (wanted == 0 && menu.cursorEmpty()) {
            if (!menu.quickMove(boosterSlot)) return ActionStatus.running();
            lastBoosterCount = have;
            lastCursorCount = 0;
            sent = true;
            waited = 0;
            return ActionStatus.progressed();
        }
        if (menu.cursorEmpty()) {
            if (!menu.click(boosterSlot, 0)) return ActionStatus.running();
            menu.noteCursorTakenFrom(boosterSlot);
            returnSlot = findEmptyOrStackablePlayerSlot(reading);
            lastBoosterCount = have;
            lastCursorCount = 0;
            sent = true;
            waited = 0;
            return ActionStatus.progressed();
        }
        // 光标里刚拿起了全部增幅卡：此时槽位已变 0，会进入 addBooster 分支放回 wanted 张，再把余下放回背包。
        return ActionStatus.running();
    }

    private ActionStatus returnCursor() {
        if (menu.busy()) return ActionStatus.running();
        if (menu.cursorEmpty()) {
            return completeWith(wanted);
        }
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isEmpty()) return closeWith(Problem.of(Problem.Kind.TARGET_GONE, "无线访问点的界面被关掉了"));
        int target = returnSlot >= 0 ? returnSlot : findEmptyOrStackablePlayerSlot(reading.get());
        if (target < 0) {
            return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "背包已满，放不下取出的无线增幅卡"));
        }
        if (!sent) {
            if (!menu.click(target, 0)) return ActionStatus.running();
            sent = true;
            waited = 0;
            return ActionStatus.progressed();
        }
        if (++waited > WAIT_TICKS) {
            return closeWith(Problem.of(Problem.Kind.STUCK, "把余下的无线增幅卡放回背包超时"));
        }
        return ActionStatus.running();
    }

    private ActionStatus completeWith(int boosters) {
        cache.recordLocal(pos, null, Map.of("boosters", Integer.toString(boosters)), Map.of("boosters", boosters));
        return closeWith(null);
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

    private ActionStatus closeWith(Problem reason) {
        if (reason != null) {
            control.readOpenMenu().ifPresent(snap -> Ae2PartSnapshots.flushToCache(cache, pos, null, snap, null, null));
        }
        problem = reason;
        stage = Stage.CLOSE;
        return ActionStatus.progressed();
    }

    private ActionStatus closeMenu(TickContext context) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        closing = null;
        return finish(problem);
    }

    private ActionStatus finish(Problem reason) {
        problem = reason;
        stage = Stage.DONE;
        return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
    }

    @Override public void pause() {
        if (opening != null && menu == null) opening.pause();
        if (closing != null) closing.pause();
    }

    @Override public void close() {
        if (closing != null) closing.close();
        if (menu != null && stage != Stage.DONE) menu.abandon();
        else if (opening != null && menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() {
        return stage == Stage.ADJUST || stage == Stage.RETURN_CURSOR
                ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "调整无线访问点的增幅卡数量为 " + wanted;
    }
}
