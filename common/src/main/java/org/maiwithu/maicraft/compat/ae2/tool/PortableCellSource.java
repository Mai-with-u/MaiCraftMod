// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2TerminalMenu;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 10 种便携存储元件的随身物品来源（via=carried）：
 * 问价直接读取客户端同步的 AEComponents.STORAGE_CELL_INV 与电量，无需先开窗即可精准报价；
 * 动手时换到主手普通右键（绝不潜行防拆解）打开对应的物品或流体便携元件 MEStorageMenu，
 * 复用终端取货或拿空桶装流体协议取出所需数量后关窗；电量不足时以 NOT_POSSIBLE_HERE 明确报出当前/上限电量并建议充能器补电。
 */
public final class PortableCellSource implements ItemSource {

    private static final int ACTIONS_PER_VISIT = 4;
    private static final int OPEN_WAIT_TICKS = 40;
    private static final int SYNC_WAIT_TICKS = 40;
    private static final int SETTLE_TICKS = 10;
    private static final long GIVE_UP_TICKS = 20L * 45;
    private static final String EMPTY_BUCKET = "minecraft:bucket";

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;

    public PortableCellSource(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String describe() {
        return "便携存储元件";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CARRIED;
    }

    // 问价直接从客户端同步的 STORAGE_CELL_INV 查库存与电量，无需先打开界面。
    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<PortableCellCatalog.CarriedCell> cells = carriedCells();
        if (cells.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "身上没有携带便携存储元件");
        }
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        long poweredCount = 0L;
        boolean needsBucket = false;
        Ae2ToolReads.PortableCellSnapshot unpoweredMatch = null;
        for (PortableCellCatalog.CarriedCell cell : cells) {
            long cellMatch = matchingCount(cell.snapshot(), wanted);
            if (cellMatch <= 0L) continue;
            if (cell.snapshot().currentEnergy() <= 0.0) {
                if (unpoweredMatch == null) unpoweredMatch = cell.snapshot();
                continue;
            }
            poweredCount += cellMatch;
            if (cell.snapshot().fluidCell()) needsBucket = true;
        }
        if (poweredCount <= 0L) {
            if (unpoweredMatch != null) {
                return new SourceQuote.Unavailable(describe(),
                        PortableCellCatalog.lowEnergyMessage(unpoweredMatch));
            }
            return new SourceQuote.Unavailable(describe(),
                    "身上的便携存储元件里没有" + request.wanted().describe());
        }
        int offerCount = (int) Math.min(request.count(), Math.min(Integer.MAX_VALUE, poweredCount));
        String note = needsBucket
                ? "客户端直读便携流体元件存货（需携带空桶在界面内灌装），动手时以现场为准"
                : "客户端直读便携存储元件存货，动手时以现场为准";
        return new SourceQuote.Offer(describe(), offerCount, new AcquisitionCost(0, ACTIONS_PER_VISIT), note, "");
    }

    // 流体便携元件装桶时需要身上有空桶，估算缺料时主动汇报所需空桶。
    @Override public List<ItemRequest> missingIngredients(ItemRequest request, SourceQuote.Offer offer,
            SourceContext context) {
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        boolean needsBucket = carriedCells().stream()
                .anyMatch(cell -> cell.snapshot().fluidCell()
                        && cell.snapshot().currentEnergy() > 0.0
                        && matchingCount(cell.snapshot(), wanted) > 0L);
        if (!needsBucket) return List.of();
        int emptyBuckets = carriedCount(EMPTY_BUCKET);
        int need = Math.max(0, offer.obtainableCount() - emptyBuckets);
        return need > 0 ? List.of(new ItemRequest(WantedItem.ofItem(EMPTY_BUCKET), need, "便携流体元件装桶所需空桶"))
                : List.of();
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        List<PortableCellCatalog.CarriedCell> matched = carriedCells().stream()
                .filter(cell -> matchingCount(cell.snapshot(), wanted) > 0L)
                .toList();
        if (matched.isEmpty()) return Optional.empty();
        List<PortableCellCatalog.CarriedCell> powered = matched.stream()
                .filter(cell -> cell.snapshot().currentEnergy() > 0.0)
                .toList();
        if (powered.isEmpty()) {
            // 问价与动手之间耗尽电量：以 NOT_POSSIBLE_HERE 明确汇报电量与充能器补电建议。
            String lowMsg = PortableCellCatalog.lowEnergyMessage(matched.getFirst().snapshot());
            return Optional.of(lowEnergyAction(lowMsg));
        }
        return Optional.of(new TakeAction(request, powered, wanted));
    }

    static long matchingCount(Ae2ToolReads.PortableCellSnapshot snapshot, Predicate<String> wanted) {
        long sum = 0L;
        for (Ae2ToolReads.CellStockEntry entry : snapshot.entries()) {
            if (entry.count() > 0L && wanted.test(entry.itemId())) {
                sum += entry.count();
            }
        }
        return sum;
    }

    private List<PortableCellCatalog.CarriedCell> carriedCells() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return List.of();
        return PortableCellCatalog.scanCarried(compat, reads, current.localPlayer());
    }

    private int carriedCount(String itemId) {
        int total = 0;
        for (BackpackStack stack : services.backpack().stacks()) {
            if (stack.itemId().equals(itemId)) total += stack.count();
        }
        return total;
    }

    private static Action lowEnergyAction(String message) {
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
     * 打开便携存储元件并取货/装桶的动作：换到主手（确保不潜行）→ 普通右键打开对应 MEStorageMenu →
     * 核实电量并等存货表同步 → 逐笔发 takeStack/takeOne 或抓空桶发 fillBucket → 放回光标残留并关窗。
     */
    private final class TakeAction implements Action {

        private enum Stage { HAND, OPEN, SYNC, TAKE, RETURN_CURSOR, CLOSE, DONE }

        private final ItemRequest request;
        private final Deque<PortableCellCatalog.CarriedCell> candidates;
        private final Predicate<String> wanted;
        private final List<String> deadEnds = new ArrayList<>();

        private PortableCellCatalog.CarriedCell currentCell;
        private Stage stage = Stage.HAND;
        private Action handStep;
        private PendingInteraction opening;
        private OpenedMenu menu;
        private Action closing;
        private int startCarried = -1;
        private int beforeAction = -1;
        private boolean expectingCursor;
        private int bucketSourceSlot = -1;
        private int unsneakTicks;
        private int openWaited;
        private int syncWaited;
        private int quietTicks;
        private int totalTicks;

        TakeAction(ItemRequest request, List<PortableCellCatalog.CarriedCell> powered, Predicate<String> wanted) {
            this.request = request;
            this.candidates = new ArrayDeque<>(powered);
            this.wanted = wanted;
            this.currentCell = this.candidates.poll();
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            if (startCarried < 0) startCarried = carriedWanted();
            if (++totalTicks > GIVE_UP_TICKS && stage != Stage.CLOSE && stage != Stage.DONE) {
                return wrapUp("从便携存储元件取 " + request.wanted().describe() + " 超时");
            }
            return switch (stage) {
                case HAND -> moveToHand(tick, player.localPlayer());
                case OPEN -> openWithoutSneak(tick, player);
                case SYNC -> waitSync(player);
                case TAKE -> takeStep(player);
                case RETURN_CURSOR -> returnCursor(player);
                case CLOSE -> tickClosing(tick);
                case DONE -> obtained() > 0 ? ActionStatus.done() : finishFailure();
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
                return nextCellOrFail("没能把 " + currentCell.snapshot().itemId() + " 换到主手：" + failed.problem().message());
            }
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        // 严格保证不潜行右键打开便携存储元件：连续至少 2 刻确认解除潜行且保持静止，确保服务端已同步非潜行态，
        // 防止空元件触发 disassembleDrive 被拆解。
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
                    return nextCellOrFail(PortableCellCatalog.lowEnergyMessage(fresh.get()));
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
                    return nextCellOrFail("打开 " + currentCell.snapshot().itemId() + " 界面超时");
                }
                return ActionStatus.running();
            }
            AbstractContainerMenu raw = self.containerMenu;
            boolean fluid = currentCell.snapshot().fluidCell();
            boolean expected = compat.call("核对便携存储元件界面", () -> reads.isPortableCellMenu(raw, fluid));
            if (!expected) {
                // 误开非目标界面时先关闭再失败/换候选，防止错误界面滞留屏幕卡住后续动作。
                PortableCellCatalog.closeUnexpectedMenu(self);
                return nextCellOrFail("打开的不是预期的便携存储元件界面");
            }
            menu = ClientOpenedMenu.claimCurrent(services.context(),
                    new ClientMenuContent(services.context(), services.menuLayouts()));
            if (menu == null) return nextCellOrFail("便携存储元件界面打开后立即关闭");
            syncWaited = 0;
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        private ActionStatus waitSync(PlayerContext player) {
            AbstractContainerMenu raw = player.localPlayer().containerMenu;
            Optional<Boolean> linked = compat.networkLinked(raw);
            if (linked.isPresent() && !linked.get()) {
                String why = compat.unlinkedWhy(raw).orElse(PortableCellCatalog.lowEnergyMessage(currentCell.snapshot()));
                return wrapUp(why);
            }
            Optional<List<Ae2TerminalMenu.StockEntry>> stock = compat.networkStock(raw);
            if (menu.reading().isPresent() && linked.orElse(false) && stock.isPresent()) {
                beforeAction = -1;
                quietTicks = 0;
                stage = Stage.TAKE;
                return ActionStatus.progressed();
            }
            if (++syncWaited > SYNC_WAIT_TICKS) {
                return wrapUp(currentCell.snapshot().itemId() + " 存货表同步超时");
            }
            return ActionStatus.running();
        }

        private ActionStatus takeStep(PlayerContext player) {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) {
                stage = Stage.DONE;
                return ActionStatus.progressed();
            }
            if (menu.busy()) return ActionStatus.running();
            if (obtained() >= request.count()) {
                stage = Stage.RETURN_CURSOR;
                return ActionStatus.progressed();
            }
            MenuContent.Reading reading = readingOpt.get();
            if (expectingCursor) return settleCursorTake(reading);
            int currentCarried = carriedWanted();
            if (beforeAction >= 0) {
                if (currentCarried > beforeAction) {
                    beforeAction = -1;
                    quietTicks = 0;
                    if (obtained() >= request.count()) {
                        stage = Stage.RETURN_CURSOR;
                        return ActionStatus.progressed();
                    }
                } else if (++quietTicks < SETTLE_TICKS) {
                    return ActionStatus.running();
                } else {
                    beforeAction = -1;
                    return wrapUp(currentCell.snapshot().itemId() + " 取出未产生背包增量（可能背包已满或电量不足）");
                }
            }
            AbstractContainerMenu raw = player.localPlayer().containerMenu;
            Optional<Ae2TerminalMenu.StockEntry> entryOpt = findStockEntry(raw);
            if (entryOpt.isEmpty()) {
                stage = Stage.RETURN_CURSOR;
                return ActionStatus.progressed();
            }
            Ae2TerminalMenu.StockEntry entry = entryOpt.get();
            if (entry.fillsBucket()) {
                return takeFluidBucket(raw, reading, entry);
            }
            if (!menu.cursorEmpty()) {
                int targetSlot = findPlayerDepositSlot(reading, menu.cursor().stack());
                if (targetSlot < 0) return wrapUp("背包已满，放不下光标上的物品");
                if (!menu.click(targetSlot, 0)) return ActionStatus.running();
                beforeAction = currentCarried;
                quietTicks = 0;
                return ActionStatus.progressed();
            }
            int stillNeed = request.count() - obtained();
            if (stillNeed >= entry.sample().getMaxStackSize() || entry.stored() <= stillNeed) {
                compat.takeStack(raw, entry.serial());
                beforeAction = currentCarried;
            } else {
                compat.takeOne(raw, entry.serial());
                expectingCursor = true;
            }
            quietTicks = 0;
            return ActionStatus.progressed();
        }

        // 单件取货（takeOne）把物品放到光标上：光标非空时立即放下到背包匹配格或空格，不误判为无背包增量。
        private ActionStatus settleCursorTake(MenuContent.Reading reading) {
            if (!menu.cursorEmpty()) {
                expectingCursor = false;
                quietTicks = 0;
                int targetSlot = findPlayerDepositSlot(reading, menu.cursor().stack());
                if (targetSlot < 0) return wrapUp("背包已满，放不下光标上的物品");
                if (!menu.click(targetSlot, 0)) return ActionStatus.running();
                beforeAction = carriedWanted();
                return ActionStatus.progressed();
            }
            if (++quietTicks < SETTLE_TICKS) return ActionStatus.running();
            expectingCursor = false;
            return wrapUp(currentCell.snapshot().itemId() + " 单件取货未同步到光标（可能电量不足）");
        }

        // 便携流体元件不存空桶，需先把玩家背包里的空桶拿上光标，再发 fillBucket 灌满送回背包。
        private ActionStatus takeFluidBucket(AbstractContainerMenu raw, MenuContent.Reading reading,
                Ae2TerminalMenu.StockEntry entry) {
            SlotSnapshot cursor = menu.cursor();
            if (cursor.isEmpty() || !cursor.stack().is(Items.BUCKET)) {
                if (!cursor.isEmpty()) {
                    stage = Stage.RETURN_CURSOR;
                    return ActionStatus.progressed();
                }
                int emptySlot = findEmptyBucketSlot(reading);
                if (emptySlot < 0) {
                    return wrapUp("从便携流体元件装桶需要身上携带空桶（minecraft:bucket）");
                }
                bucketSourceSlot = emptySlot;
                menu.noteCursorTakenFrom(emptySlot);
                if (!menu.click(emptySlot, 0)) return ActionStatus.running();
                return ActionStatus.progressed();
            }
            beforeAction = carriedWanted();
            quietTicks = 0;
            compat.fillBucket(raw, entry.serial());
            return ActionStatus.progressed();
        }

        private ActionStatus returnCursor(PlayerContext player) {
            if (menu == null || menu.cursorEmpty()) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (menu.busy()) return ActionStatus.running();
            MenuContent.Reading reading = readingOpt.get();
            ItemStack cursorStack = menu.cursor().stack();
            int backSlot = bucketSourceSlot >= 0 && canPlaceCursorInSlot(reading, bucketSourceSlot, cursorStack)
                    ? bucketSourceSlot
                    : findPlayerDepositSlot(reading, cursorStack);
            if (backSlot >= 0) {
                if (menu.click(backSlot, 0)) {
                    bucketSourceSlot = -1;
                    quietTicks = 0;
                    return ActionStatus.progressed();
                }
                if (++quietTicks <= SYNC_WAIT_TICKS) return ActionStatus.running();
            }
            compat.putBack(player.localPlayer().containerMenu);
            stage = Stage.CLOSE;
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
            if (obtained() >= request.count() || candidates.isEmpty()) {
                stage = Stage.DONE;
                return obtained() > 0 ? ActionStatus.done() : finishFailure();
            }
            currentCell = candidates.poll();
            opening = null;
            handStep = null;
            unsneakTicks = 0;
            stage = Stage.HAND;
            return ActionStatus.progressed();
        }

        private ActionStatus nextCellOrFail(String reason) {
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

        private ActionStatus wrapUp(String reason) {
            deadEnds.add(reason);
            stage = menu == null ? Stage.DONE : Stage.RETURN_CURSOR;
            return ActionStatus.progressed();
        }

        private ActionStatus finishFailure() {
            String message = deadEnds.isEmpty()
                    ? "未能从便携存储元件取出" + request.wanted().describe()
                    : String.join("；", deadEnds);
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, message, null));
        }

        private Optional<Ae2TerminalMenu.StockEntry> findStockEntry(AbstractContainerMenu raw) {
            Optional<List<Ae2TerminalMenu.StockEntry>> entries = compat.networkStock(raw);
            if (entries.isEmpty()) return Optional.empty();
            for (Ae2TerminalMenu.StockEntry entry : entries.get()) {
                if (entry.stored() <= 0L) continue;
                String id = PortableCellCatalog.idOf(entry.sample());
                if (wanted.test(id)) return Optional.of(entry);
            }
            return Optional.empty();
        }

        private int findEmptyBucketSlot(MenuContent.Reading reading) {
            List<SlotSnapshot> slots = reading.playerSnapshots();
            for (int i = 0; i < slots.size(); i++) {
                if (!slots.get(i).isEmpty() && slots.get(i).stack().is(Items.BUCKET)) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        // 优先放进可叠堆的同类未满格，再放进空格，避免满槽交换导致光标滞留。
        private int findPlayerDepositSlot(MenuContent.Reading reading, ItemStack cursor) {
            List<SlotSnapshot> slots = reading.playerSnapshots();
            if (cursor != null && !cursor.isEmpty()) {
                for (int i = 0; i < slots.size(); i++) {
                    SlotSnapshot slot = slots.get(i);
                    if (!slot.isEmpty()
                            && ItemStack.isSameItemSameComponents(slot.stack(), cursor)
                            && slot.count() < slot.stack().getMaxStackSize()) {
                        return reading.playerSlotIds().get(i);
                    }
                }
            }
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).isEmpty()) return reading.playerSlotIds().get(i);
            }
            return -1;
        }

        private static boolean canPlaceCursorInSlot(MenuContent.Reading reading, int menuSlotId, ItemStack cursor) {
            int idx = reading.playerSlotIds().indexOf(menuSlotId);
            if (idx < 0) return false;
            SlotSnapshot slot = reading.playerSnapshots().get(idx);
            if (slot.isEmpty()) return true;
            return cursor != null && !cursor.isEmpty()
                    && ItemStack.isSameItemSameComponents(slot.stack(), cursor)
                    && slot.count() < slot.stack().getMaxStackSize();
        }

        private int obtained() {
            return Math.max(0, carriedWanted() - startCarried);
        }

        private int carriedWanted() {
            int total = 0;
            for (BackpackStack stack : services.backpack().stacks()) {
                if (wanted.test(stack.itemId())) total += stack.count();
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
            return "从便携存储元件 " + currentCell.snapshot().itemId() + " 取" + request.wanted().describe();
        }
    }
}
