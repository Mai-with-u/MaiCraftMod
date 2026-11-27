// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.inventory.containers.FeedReport;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 水晶组装器界面储罐倒桶动作：打开水晶组装器界面后核对储罐槽位与实时流体余量（防止异种流体冲突或超量倒桶），
 * 逐桶左键拿起背包里的满桶至光标，经 {@link OpenedMenu#submitModAction} 发送 AE2 原生
 * {@code InventoryActionPacket(EMPTY_ITEM, tankSlotIndex, 0)}，确认光标变为空桶且储罐毫桶数增加后，
 * 将空桶放回原格或背包空格，全部倒完后关窗并按 {@link FeedReport} 交回实情。
 */
final class CrystalAssemblerTankFeed implements Action, FeedReport {

    static final String EMPTY_BUCKET_ID = "minecraft:bucket";
    private static final int SYNC_WAIT_TICKS = ExtendedAeMenuActionSupport.SYNC_WAIT_TICKS;
    private static final long BUCKET_MB = 1000L;

    private enum Stage { OPEN, INSPECT_TANK, PICK_BUCKET, WAIT_PICK, POUR, WAIT_POUR, RETURN_BUCKET, WAIT_RETURN,
        CLOSE, DONE }

    private final BlockPos at;
    private final String bucketItemId;
    private final String expectedFluidId;
    private final long requiredFluidMb;
    private final int maxBuckets;
    private final MenuOpening opening;
    private final ExtendedAeCompat compat;
    private final ExtendedAeDeviceReadings readings;

    private final List<String> notes = new ArrayList<>();
    private Stage stage = Stage.OPEN;
    private OpenedMenu menu;
    private Action closing;
    private int tankSlotIndex = -1;
    private int bucketsToPour;
    private int poured;
    private int originPlayerSlot = -1;
    private int returnTargetSlot = -1;
    private long tankAmountBeforePour;
    private PendingMenuAction pendingPour;
    private int waited;
    private Problem problem;

    CrystalAssemblerTankFeed(BlockPos at, String bucketItemId, String expectedFluidId, long requiredFluidMb,
            int maxBuckets, MenuOpening opening, ExtendedAeCompat compat, ExtendedAeDeviceReadings readings) {
        this.at = at.immutable();
        this.bucketItemId = Objects.requireNonNull(bucketItemId, "bucketItemId");
        this.expectedFluidId = expectedFluidId == null ? "" : expectedFluidId;
        this.requiredFluidMb = Math.max(0L, requiredFluidMb);
        this.maxBuckets = Math.max(1, maxBuckets);
        this.opening = Objects.requireNonNull(opening, "opening");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.readings = Objects.requireNonNull(readings, "readings");
    }

    @Override public int delivered() { return poured; }

    @Override public List<String> notes() { return List.copyOf(notes); }

    @Override public ActionStatus tick(TickContext tick) {
        return switch (stage) {
            case OPEN -> open(tick);
            case INSPECT_TANK -> inspectTank();
            case PICK_BUCKET -> pickBucket();
            case WAIT_PICK -> waitPick();
            case POUR -> pourIntoTank();
            case WAIT_POUR -> waitPour();
            case RETURN_BUCKET -> returnEmptyBucket();
            case WAIT_RETURN -> waitReturn();
            case CLOSE -> closeMenu(tick);
            case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "水晶组装器界面没打开"));
        stage = Stage.INSPECT_TANK;
        waited = 0;
        return ActionStatus.progressed();
    }

    // 进界面后先核对储罐槽位与实时存量：异种流体立刻报错停手，已有同种足够毫桶则直接零桶完成。
    private ActionStatus inspectTank() {
        Optional<ExtendedAeReads.CrystalAssemblerMenuView> viewOpt = compat.openCrystalAssemblerMenu();
        if (viewOpt.isEmpty() || menu.reading().isEmpty()) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "打开水晶组装器界面后读不到储罐状态"));
        }
        ExtendedAeReads.CrystalAssemblerMenuView view = viewOpt.get();
        readings.putCrystalAssembler(at, view);
        tankSlotIndex = resolveTankSlot(view);
        if (tankSlotIndex < 0) {
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "水晶组装器界面里找不到储罐槽位"));
        }
        String liveFluid = view.tankFluidId();
        long liveAmount = view.tankAmountMb();
        if (liveAmount > 0 && !expectedFluidId.isEmpty() && !liveFluid.isEmpty()
                && !liveFluid.equals(expectedFluidId)) {
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "水晶组装器储罐内已有异种流体 " + liveFluid + "（" + liveAmount + " mB），无法倒入 "
                            + expectedFluidId,
                    "先用空桶抽出机内流体或换一台空闲的水晶组装器"));
        }
        long existingSame = (!expectedFluidId.isEmpty() && liveFluid.equals(expectedFluidId)) ? liveAmount : 0L;
        if (requiredFluidMb > 0 && existingSame >= requiredFluidMb) {
            notes.add("界面实时核对：储罐内已有 " + existingSame + " mB " + liveFluid + "，已满足所需，不用再倒桶");
            return beginClose(null);
        }
        long deficit = requiredFluidMb > 0 ? Math.max(0L, requiredFluidMb - existingSame) : maxBuckets * BUCKET_MB;
        long freeCapacity = Math.max(0L, view.tankCapacityMb() - liveAmount);
        int neededByDeficit = (int) Math.ceil(deficit / (double) BUCKET_MB);
        int fitsInTank = (int) (freeCapacity / BUCKET_MB);
        bucketsToPour = Math.min(maxBuckets, Math.min(neededByDeficit, fitsInTank));
        if (bucketsToPour <= 0) {
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "水晶组装器储罐剩余空间不足 1000 mB（当前 " + liveAmount + "/" + view.tankCapacityMb() + " mB）"));
        }
        waited = 0;
        stage = Stage.PICK_BUCKET;
        return ActionStatus.progressed();
    }

    private ActionStatus pickBucket() {
        if (poured >= bucketsToPour) {
            notes.add("已通过水晶组装器界面向储罐倒入 " + poured + " 桶 " + bucketItemId);
            return beginClose(null);
        }
        if (menu.busy()) return ActionStatus.running();
        Optional<MenuContent.Reading> readingOpt = menu.reading();
        if (readingOpt.isEmpty()) return beginClose(Problem.of(Problem.Kind.TARGET_GONE, "倒桶途中界面意外关闭"));
        int playerSlot = findPlayerSlot(readingOpt.get(), bucketItemId);
        if (playerSlot < 0) {
            if (poured > 0) {
                notes.add("背包里的 " + bucketItemId + " 用完，本次共倒入 " + poured + "/" + bucketsToPour + " 桶");
                return beginClose(null);
            }
            return beginClose(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有 " + bucketItemId + "，无法倒桶"));
        }
        if (!menu.click(playerSlot, 0)) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "无法从背包拿起 " + bucketItemId));
        }
        originPlayerSlot = playerSlot;
        menu.noteCursorTakenFrom(playerSlot);
        waited = 0;
        stage = Stage.WAIT_PICK;
        return ActionStatus.progressed();
    }

    private ActionStatus waitPick() {
        if (menu.busy()) return ActionStatus.running();
        if (!menu.cursorEmpty() && bucketItemId.equals(menu.cursor().key())) {
            waited = 0;
            stage = Stage.POUR;
            return ActionStatus.progressed();
        }
        if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.STUCK, "左键点击后光标未拿到 " + bucketItemId));
    }

    // 光标已持有满桶：向储罐槽发 AE2 原生 InventoryActionPacket(EMPTY_ITEM, tankSlot, 0) 并等储罐与光标双重确认。
    private ActionStatus pourIntoTank() {
        if (menu.busy()) return ActionStatus.running();
        Optional<ExtendedAeReads.CrystalAssemblerMenuView> beforeView = compat.openCrystalAssemblerMenu();
        if (beforeView.isEmpty()) {
            return beginClose(Problem.of(Problem.Kind.TARGET_GONE, "倒桶前水晶组装器界面丢失"));
        }
        tankAmountBeforePour = beforeView.get().tankAmountMb();
        MenuConfirmation confirmation = (ctx, pending) -> isPourConfirmed()
                ? MenuConfirmation.Verdict.APPLIED : MenuConfirmation.Verdict.PENDING;
        Optional<PendingMenuAction> submitted = menu.submitModAction(
                "向水晶组装器储罐倒桶 " + bucketItemId,
                () -> compat.sendEmptyItemToTank(tankSlotIndex),
                confirmation, SYNC_WAIT_TICKS);
        if (submitted.isEmpty()) {
            // 若提交当刻已同步观察到光标变空桶且储罐增加，直接结算本桶，避免重复发包。
            if (isPourConfirmed()) {
                compat.openCrystalAssemblerMenu().ifPresent(v -> readings.putCrystalAssembler(at, v));
                poured++;
                waited = 0;
                stage = Stage.RETURN_BUCKET;
                return ActionStatus.progressed();
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "无法向水晶组装器发送储罐倒桶包"));
        }
        pendingPour = submitted.get();
        waited = 0;
        stage = Stage.WAIT_POUR;
        return ActionStatus.progressed();
    }

    private ActionStatus waitPour() {
        if (isPourConfirmed()) {
            compat.openCrystalAssemblerMenu().ifPresent(v -> readings.putCrystalAssembler(at, v));
            pendingPour = null;
            poured++;
            waited = 0;
            stage = Stage.RETURN_BUCKET;
            return ActionStatus.progressed();
        }
        if (pendingPour != null && pendingPour.status() != PendingMenuAction.Status.PENDING) {
            PendingMenuAction.Status terminal = pendingPour.status();
            pendingPour = null;
            if (terminal == PendingMenuAction.Status.CONFIRMED_APPLIED) {
                compat.openCrystalAssemblerMenu().ifPresent(v -> readings.putCrystalAssembler(at, v));
                poured++;
                waited = 0;
                stage = Stage.RETURN_BUCKET;
                return ActionStatus.progressed();
            }
            return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "向水晶组装器储罐倒桶未被游戏确认（可能储罐已满或含异种流体）"));
        }
        if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.STUCK, "等待水晶组装器储罐倒桶确认超时"));
    }

    // 倒完一桶后光标上是空桶，放回原背包格或背包第一个空格，保证不把空桶留在光标上。
    private ActionStatus returnEmptyBucket() {
        if (menu.cursorEmpty()) {
            waited = 0;
            stage = Stage.PICK_BUCKET;
            return ActionStatus.progressed();
        }
        if (menu.busy()) return ActionStatus.running();
        Optional<MenuContent.Reading> readingOpt = menu.reading();
        if (readingOpt.isEmpty()) return beginClose(Problem.of(Problem.Kind.TARGET_GONE, "放回空桶时界面关闭"));
        returnTargetSlot = chooseReturnSlot(readingOpt.get());
        if (returnTargetSlot < 0) {
            return beginClose(Problem.of(Problem.Kind.INVENTORY_FULL, "倒完流体后背包已满，空桶无处放回"));
        }
        if (!menu.click(returnTargetSlot, 0)) {
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "无法将空桶放回背包"));
        }
        waited = 0;
        stage = Stage.WAIT_RETURN;
        return ActionStatus.progressed();
    }

    private ActionStatus waitReturn() {
        if (menu.busy()) return ActionStatus.running();
        if (menu.cursorEmpty()) {
            waited = 0;
            stage = Stage.PICK_BUCKET;
            return ActionStatus.progressed();
        }
        if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.STUCK, "等待空桶放回背包确认超时"));
    }

    private boolean isPourConfirmed() {
        boolean cursorIsEmptyBucket = !menu.cursorEmpty() && EMPTY_BUCKET_ID.equals(menu.cursor().key());
        boolean tankGrew = compat.openCrystalAssemblerMenu()
                .map(v -> v.tankAmountMb() > tankAmountBeforePour)
                .orElse(false);
        return cursorIsEmptyBucket && tankGrew;
    }

    private int resolveTankSlot(ExtendedAeReads.CrystalAssemblerMenuView view) {
        if (view.tankSlotIndex() >= 0) return view.tankSlotIndex();
        List<Integer> storageSlots = compat.allMenuSlotsOf("STORAGE");
        return storageSlots.isEmpty() ? -1 : storageSlots.getFirst();
    }

    private static int findPlayerSlot(MenuContent.Reading reading, String wantedKey) {
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot snap = reading.playerSnapshots().get(i);
            if (!snap.isEmpty() && wantedKey.equals(snap.key())) return reading.playerSlotIds().get(i);
        }
        return -1;
    }

    private int chooseReturnSlot(MenuContent.Reading reading) {
        for (int i = 0; i < reading.playerSlotIds().size(); i++) {
            if (reading.playerSlotIds().get(i) == originPlayerSlot) {
                SlotSnapshot snap = reading.playerSnapshots().get(i);
                if (snap.isEmpty() || (EMPTY_BUCKET_ID.equals(snap.key()) && snap.count() < 16)) {
                    return originPlayerSlot;
                }
            }
        }
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot snap = reading.playerSnapshots().get(i);
            if (snap.isEmpty() || (EMPTY_BUCKET_ID.equals(snap.key()) && snap.count() < 16)) {
                return reading.playerSlotIds().get(i);
            }
        }
        return -1;
    }

    private ActionStatus closeMenu(TickContext tick) {
        if (closing == null) closing = menu.closing();
        ActionStatus status = closing.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        closing = null;
        return finish(problem);
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

    @Override public void pause() {
        if (closing != null) closing.pause();
        else if (menu == null) opening.pause();
    }

    @Override public void close() {
        if (closing != null) closing.close();
        if (menu != null && stage != Stage.DONE) menu.abandon();
        else if (menu == null) opening.close();
    }

    @Override public Interruptibility interruptibility() {
        if (menu != null && !menu.cursorEmpty()) return Interruptibility.UNSAFE_TO_STOP;
        return Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "通过水晶组装器界面向储罐倒入 " + bucketItemId;
    }
}
