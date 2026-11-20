// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 10 种便携存储元件的随身背包适配（实现 CarriedBackpack）：
 * 免开界面直接从客户端同步的 AEComponents.STORED_ENERGY 与 STORAGE_CELL_INV 计算剩余空槽与存货摘要；
 * 仅物品便携元件参与腾背包且电量耗尽时报 0，流体便携元件同样在 carried() 中展示供观察。
 *
 * <p>存入动作换到主手后严格执行「普通右键」（先显式释放潜行并核实非潜行状态，绝不潜行右键以免空元件被
 * AE2 disassembleDrive 当场拆解回外壳与组件），打开 ae2:portable_item_cell 界面后按快速移动存入、核对背包减少再关窗。
 */
public final class PortableCellBackpack implements CarriedBackpack {

    private static final int OPEN_WAIT_TICKS = 40;
    private static final int SYNC_WAIT_TICKS = 40;
    private static final int SETTLE_TICKS = 10;
    private static final long GIVE_UP_TICKS = 20L * 30;

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;

    public PortableCellBackpack(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    // 仅物品便携元件参与腾背包计数；有电按剩余种类与字节折算，没电报 0。
    @Override public int freeSlots() {
        List<PortableCellCatalog.CarriedCell> cells = carriedCells();
        int total = 0;
        for (PortableCellCatalog.CarriedCell cell : cells) {
            total += PortableCellCatalog.freeSlotsOf(cell.snapshot());
        }
        return total;
    }

    // 全部 10 种便携元件（含流体便携元件）均输出免开界面直读摘要供 observe(self).carried_backpacks 展示。
    @Override public List<CarriedPack> carried() {
        List<PortableCellCatalog.CarriedCell> cells = carriedCells();
        if (cells.isEmpty()) return List.of();
        List<CarriedPack> packs = new ArrayList<>(cells.size());
        for (PortableCellCatalog.CarriedCell cell : cells) {
            Ae2ToolReads.PortableCellSnapshot snapshot = cell.snapshot();
            packs.add(new CarriedPack(
                    snapshot.itemId(),
                    cell.where(),
                    true,
                    PortableCellCatalog.freeSlotsOf(snapshot),
                    PortableCellCatalog.summarizeContents(snapshot)));
        }
        return List.copyOf(packs);
    }

    @Override public Optional<Action> store(BackpackStack stack, TaskRecords records) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(records, "records");
        // 便携存储元件本身不往便携存储元件里套娃存入。
        if (PortableCellCatalog.isPortableCellId(stack.itemId())) {
            return Optional.empty();
        }
        List<PortableCellCatalog.CarriedCell> itemCells = carriedCells().stream()
                .filter(cell -> !cell.snapshot().fluidCell())
                .toList();
        if (itemCells.isEmpty()) return Optional.empty();
        List<PortableCellCatalog.CarriedCell> usable = itemCells.stream()
                .filter(cell -> PortableCellCatalog.canAcceptItem(cell.snapshot(), stack.itemId()))
                .toList();
        if (!usable.isEmpty()) {
            return Optional.of(new StoreAction(stack, usable, records));
        }
        // 身上有物品便携元件但全部电量耗尽时，返回清晰低电报错并建议充能器补电。
        Optional<PortableCellCatalog.CarriedCell> unpowered = itemCells.stream()
                .filter(cell -> cell.snapshot().currentEnergy() <= 0.0)
                .findFirst();
        if (unpowered.isPresent()) {
            String message = PortableCellCatalog.lowEnergyMessage(unpowered.get().snapshot());
            return Optional.of(immediateLowEnergyFailure(message));
        }
        return Optional.empty();
    }

    private List<PortableCellCatalog.CarriedCell> carriedCells() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return List.of();
        return PortableCellCatalog.scanCarried(compat, reads, current.localPlayer());
    }

    private static Action immediateLowEnergyFailure(String message) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, message,
                        "把便携存储元件放入充能器（ae2:charger）并执行 machine_run 补电"));
            }

            @Override public String describe() {
                return message;
            }
        };
    }

    /**
     * 往物品便携存储元件存入一堆物品的动作：换到主手（确保不潜行）→ 普通右键打开 ae2:portable_item_cell →
     * 核实电量与界面同步 → 对目标物品所在槽执行快速移动 → 核对背包减少并记账 → 关窗。
     */
    private final class StoreAction implements Action {

        private enum Stage { HAND, OPEN, SYNC, STORE, CLOSE, DONE }

        private final BackpackStack target;
        private final Deque<PortableCellCatalog.CarriedCell> candidates;
        private final TaskRecords records;
        private final List<String> deadEnds = new ArrayList<>();

        private PortableCellCatalog.CarriedCell currentCell;
        private Stage stage = Stage.HAND;
        private Action handStep;
        private PendingInteraction opening;
        private OpenedMenu menu;
        private Action closing;
        private int unsneakTicks;
        private int openWaited;
        private int syncWaited;
        private int beforeCount = -1;
        private int quietTicks;
        private int storedTotal;
        private int totalTicks;

        StoreAction(BackpackStack target, List<PortableCellCatalog.CarriedCell> usable, TaskRecords records) {
            this.target = target;
            this.candidates = new ArrayDeque<>(usable);
            this.records = records;
            this.currentCell = this.candidates.poll();
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            if (++totalTicks > GIVE_UP_TICKS && stage != Stage.CLOSE && stage != Stage.DONE) {
                return beginCloseOrFail(Problem.of(Problem.Kind.STUCK,
                        "往便携存储元件存入 " + target.itemId() + " 超时"));
            }
            return switch (stage) {
                case HAND -> moveToHand(tick, player.localPlayer());
                case OPEN -> openWithoutSneak(tick, player);
                case SYNC -> waitSync(player);
                case STORE -> storeItems(player);
                case CLOSE -> tickClosing(tick);
                case DONE -> storedTotal > 0 ? ActionStatus.done() : finishFailure();
            };
        }

        private ActionStatus moveToHand(TickContext tick, LocalPlayer self) {
            if (handStep == null) {
                handStep = PortableCellCatalog.moveSlotToMainhand(services, self,
                        currentCell.slot(), currentCell.snapshot().itemId()).orElse(null);
                if (handStep == null) {
                    stage = Stage.OPEN;
                    return ActionStatus.progressed();
                }
            }
            ActionStatus status = handStep.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            handStep.close();
            handStep = null;
            if (status instanceof ActionStatus.Failed failed) {
                return nextOrFail("没能把 " + currentCell.snapshot().itemId() + " 换到主手：" + failed.problem().message());
            }
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        // 绝不潜行右键便携元件：连续至少 2 刻确认解除潜行且保持静止，确保服务端已同步非潜行态，
        // 防止空便携元件被 AE2 disassembleDrive 当场拆解。
        private ActionStatus openWithoutSneak(TickContext tick, PlayerContext player) {
            LocalPlayer self = player.localPlayer();
            if (opening == null) {
                boolean sneaking = self.isShiftKeyDown();
                if (!PortableCellCatalog.isUnsneakStable(sneaking, unsneakTicks)) {
                    unsneakTicks = PortableCellCatalog.advanceUnsneakTicks(sneaking, unsneakTicks);
                    player.input().applyMovement(PlayerInput.Movement.STOPPED, tick.gameTick());
                    return ActionStatus.running();
                }
                Optional<Ae2ToolReads.PortableCellSnapshot> fresh = compat.call("复查便携元件电量",
                        () -> reads.readPortableCell(self.getMainHandItem()));
                if (fresh.isPresent() && fresh.get().currentEnergy() <= 0.0) {
                    return nextOrFail(PortableCellCatalog.lowEnergyMessage(fresh.get()));
                }
                int beforeId = self.containerMenu.containerId;
                PendingInteraction submitted = player.interactionSender().useItem(player, InteractionHand.MAIN_HAND,
                        ctx -> ctx.localPlayer().containerMenu.containerId != beforeId
                                ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING,
                        OPEN_WAIT_TICKS);
                if (submitted == null) return ActionStatus.running();
                opening = submitted;
                openWaited = 0;
                return ActionStatus.running();
            }
            if (!opening.terminal()) opening = player.interactionSender().poll(player, opening);
            if (!opening.terminal()) {
                if (++openWaited > OPEN_WAIT_TICKS) {
                    return nextOrFail("打开 " + currentCell.snapshot().itemId() + " 界面超时");
                }
                return ActionStatus.running();
            }
            AbstractContainerMenu raw = self.containerMenu;
            boolean expected = compat.call("核对便携物品元件界面", () -> reads.isPortableCellMenu(raw, false));
            if (!expected) {
                // 误开非目标界面时先关闭再失败/换候选，防止错误界面滞留屏幕卡住后续动作。
                PortableCellCatalog.closeUnexpectedMenu(self);
                return nextOrFail("打开的不是物品便携存储元件界面");
            }
            menu = ClientOpenedMenu.claimCurrent(services.context(),
                    new ClientMenuContent(services.context(), services.menuLayouts()));
            if (menu == null) return nextOrFail("便携存储元件界面打开后立即关闭");
            syncWaited = 0;
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        private ActionStatus waitSync(PlayerContext player) {
            AbstractContainerMenu raw = player.localPlayer().containerMenu;
            Optional<Boolean> linked = compat.networkLinked(raw);
            if (linked.isPresent() && !linked.get()) {
                String why = compat.unlinkedWhy(raw).orElse(PortableCellCatalog.lowEnergyMessage(currentCell.snapshot()));
                deadEnds.add(why);
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (menu.reading().isPresent() && linked.orElse(false)) {
                beforeCount = -1;
                quietTicks = 0;
                stage = Stage.STORE;
                return ActionStatus.progressed();
            }
            if (++syncWaited > SYNC_WAIT_TICKS) {
                deadEnds.add(currentCell.snapshot().itemId() + " 界面内容同步超时");
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            return ActionStatus.running();
        }

        // 在便携元件终端界面对目标物品执行快速移动并按背包差量核对记账。
        private ActionStatus storeItems(PlayerContext player) {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) {
                stage = Stage.DONE;
                return ActionStatus.progressed();
            }
            if (menu.busy()) return ActionStatus.running();
            MenuContent.Reading reading = readingOpt.get();
            int currentCount = countItem(reading.playerSnapshots(), target.itemId());
            if (beforeCount >= 0) {
                int moved = beforeCount - currentCount;
                if (moved > 0) {
                    storedTotal += moved;
                    records.change(new Change(Change.Kind.ITEM_STORED, target.itemId(), moved,
                            "存进了便携存储元件 " + currentCell.snapshot().itemId()));
                    beforeCount = -1;
                    quietTicks = 0;
                } else if (++quietTicks < SETTLE_TICKS) {
                    return ActionStatus.running();
                } else {
                    deadEnds.add(currentCell.snapshot().itemId() + " 已满或拒收 " + target.itemId());
                    beforeCount = -1;
                    stage = Stage.CLOSE;
                    return ActionStatus.progressed();
                }
            }
            if (storedTotal >= target.count() || currentCount <= 0) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            int slotId = findTargetSlotId(reading);
            if (slotId < 0) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (!menu.quickMove(slotId)) {
                if (++quietTicks > SYNC_WAIT_TICKS) {
                    deadEnds.add(currentCell.snapshot().itemId() + " 快速存入操作被界面拒绝或超时");
                    stage = Stage.CLOSE;
                    return ActionStatus.progressed();
                }
                return ActionStatus.running();
            }
            beforeCount = currentCount;
            quietTicks = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus tickClosing(TickContext tick) {
            if (closing == null) {
                closing = menu == null ? null : menu.closing();
                if (closing == null) return afterClosed();
            }
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            closing.close();
            closing = null;
            menu = null;
            return afterClosed();
        }

        private ActionStatus afterClosed() {
            if (storedTotal > 0 || candidates.isEmpty()) {
                stage = Stage.DONE;
                return storedTotal > 0 ? ActionStatus.done() : finishFailure();
            }
            currentCell = candidates.poll();
            opening = null;
            handStep = null;
            unsneakTicks = 0;
            stage = Stage.HAND;
            return ActionStatus.progressed();
        }

        private ActionStatus nextOrFail(String reason) {
            deadEnds.add(reason);
            if (!candidates.isEmpty()) {
                currentCell = candidates.poll();
                opening = null;
                handStep = null;
                unsneakTicks = 0;
                stage = Stage.HAND;
                return ActionStatus.progressed();
            }
            stage = Stage.DONE;
            return finishFailure();
        }

        private ActionStatus beginCloseOrFail(Problem reason) {
            deadEnds.add(reason.message());
            if (menu != null) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            stage = Stage.DONE;
            return ActionStatus.failed(reason);
        }

        private ActionStatus finishFailure() {
            String message = deadEnds.isEmpty()
                    ? "便携存储元件无法存入 " + target.itemId()
                    : String.join("；", deadEnds);
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, message, null));
        }

        private int findTargetSlotId(MenuContent.Reading reading) {
            List<SlotSnapshot> playerSlots = reading.playerSnapshots();
            for (int i = 0; i < playerSlots.size(); i++) {
                SlotSnapshot slot = playerSlots.get(i);
                if (!slot.isEmpty() && PortableCellCatalog.idOf(slot.stack()).equals(target.itemId())) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        private static int countItem(List<SlotSnapshot> slots, String itemId) {
            int total = 0;
            for (SlotSnapshot slot : slots) {
                if (!slot.isEmpty() && PortableCellCatalog.idOf(slot.stack()).equals(itemId)) {
                    total += slot.count();
                }
            }
            return total;
        }

        @Override public void pause() {
            if (handStep != null) handStep.pause();
            if (menu != null) menu.abandon();
        }

        @Override public void close() {
            if (handStep != null) {
                handStep.close();
                handStep = null;
            }
            if (closing != null) {
                closing.close();
                closing = null;
            }
            if (menu != null && stage != Stage.DONE) {
                menu.abandon();
                menu = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            return menu != null || closing != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "往便携存储元件 " + currentCell.snapshot().itemId() + " 存入 " + target.itemId();
        }
    }
}
