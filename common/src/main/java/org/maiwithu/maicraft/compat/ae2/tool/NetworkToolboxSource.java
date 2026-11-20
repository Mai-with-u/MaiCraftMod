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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 网络工具（ae2:network_tool）9 格随身升级卡箱的物品来源（via=carried）：
 * 问价直接读取物品组件 DataComponents.CONTAINER 内的 9 格升级卡库存（免开界面）；
 * 动手时把网络工具换到主手并对空右键打开 ae2:networktool（避开对着 ME 方块误开网络状态页），
 * 从 STORAGE 槽取出所需升级卡后关窗。
 */
public final class NetworkToolboxSource implements ItemSource {

    private static final int ACTIONS_PER_VISIT = 4;
    private static final int SYNC_WAIT_TICKS = 40;
    private static final long GIVE_UP_TICKS = 20L * 30;

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;

    public NetworkToolboxSource(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String describe() {
        return "AE2 网络工具升级卡箱";
    }

    @Override public AcquireVia via() {
        return AcquireVia.CARRIED;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        List<CarriedTool> tools = carriedTools();
        if (tools.isEmpty()) {
            return new SourceQuote.Unavailable(describe(), "身上没有携带 AE2 网络工具");
        }
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        int available = 0;
        for (CarriedTool tool : tools) {
            available += matchingCount(tool.contents(), wanted);
        }
        if (available <= 0) {
            return new SourceQuote.Unavailable(describe(),
                    "随身网络工具的 9 格升级卡箱里没有" + request.wanted().describe());
        }
        return new SourceQuote.Offer(describe(), Math.min(available, request.count()),
                new AcquisitionCost(0, ACTIONS_PER_VISIT),
                "客户端直读网络工具 DataComponents.CONTAINER 升级卡库存，动手时以现场为准", "");
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        Predicate<String> wanted = itemId -> request.wanted().matches(itemId, services.itemTags().tagsOf(itemId));
        List<CarriedTool> matching = carriedTools().stream()
                .filter(tool -> matchingCount(tool.contents(), wanted) > 0)
                .toList();
        if (matching.isEmpty()) return Optional.empty();
        return Optional.of(new TakeUpgradeAction(request, matching, wanted));
    }

    private record CarriedTool(int slot, List<ItemStack> contents) {}

    private List<CarriedTool> carriedTools() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null) return List.of();
        LocalPlayer self = current.localPlayer();
        List<CarriedTool> out = new ArrayList<>();
        Inventory inventory = self.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (Ae2Wrench.NETWORK_TOOL.equals(PortableCellCatalog.idOf(stack))) {
                List<ItemStack> cards = compat.call("读取网络工具升级卡箱", () -> reads.readNetworkToolbox(stack));
                out.add(new CarriedTool(slot, cards));
            }
        }
        ItemStack offhand = self.getOffhandItem();
        if (Ae2Wrench.NETWORK_TOOL.equals(PortableCellCatalog.idOf(offhand))) {
            List<ItemStack> cards = compat.call("读取网络工具升级卡箱", () -> reads.readNetworkToolbox(offhand));
            out.add(new CarriedTool(PortableCellCatalog.OFFHAND_SLOT, cards));
        }
        return out;
    }

    static int matchingCount(List<ItemStack> contents, Predicate<String> wanted) {
        int sum = 0;
        for (ItemStack stack : contents) {
            if (stack != null && !stack.isEmpty() && wanted.test(PortableCellCatalog.idOf(stack))) {
                sum += stack.getCount();
            }
        }
        return sum;
    }

    /**
     * 打开网络工具升级卡箱并搬出升级卡的动作：换到主手 → 对空右键打开 ae2:networktool →
     * 走通用 MenuTakeRound 按 STORAGE 槽搬出所需张数 → 关窗。
     */
    private final class TakeUpgradeAction implements Action {

        private enum Stage { HAND, OPEN, SYNC, TAKE, DONE }

        private final ItemRequest request;
        private final Deque<CarriedTool> candidates;
        private final Predicate<String> wanted;
        private final List<String> deadEnds = new ArrayList<>();

        private CarriedTool current;
        private Stage stage = Stage.HAND;
        private Action step;
        private OpenedMenu menu;
        private MenuTakeRound round;
        private int startCarried = -1;
        private int waited;
        private int totalTicks;

        TakeUpgradeAction(ItemRequest request, List<CarriedTool> matching, Predicate<String> wanted) {
            this.request = request;
            this.candidates = new ArrayDeque<>(matching);
            this.wanted = wanted;
            this.current = this.candidates.poll();
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            if (startCarried < 0) startCarried = carriedWanted();
            if (++totalTicks > GIVE_UP_TICKS && stage != Stage.DONE) {
                if (round != null) round.close(false);
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "从网络工具箱取升级卡超时"));
            }
            return switch (stage) {
                case HAND -> moveToHand(tick, player.localPlayer());
                case OPEN -> openIntoAir(tick, player.localPlayer());
                case SYNC -> waitSync();
                case TAKE -> tickRound(tick);
                case DONE -> obtained() > 0 ? ActionStatus.done() : finishFailure();
            };
        }

        private ActionStatus moveToHand(TickContext tick, LocalPlayer self) {
            if (step == null) {
                step = PortableCellCatalog.moveSlotToMainhand(services, self, current.slot(),
                        Ae2Wrench.NETWORK_TOOL).orElse(null);
                if (step == null) {
                    stage = Stage.OPEN;
                    return ActionStatus.progressed();
                }
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            step.close();
            step = null;
            if (status instanceof ActionStatus.Failed failed) {
                return nextOrFail("没能把网络工具拿到主手：" + failed.problem().message());
            }
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        // 对空右键确保触发 NetworkToolItem.use 打开 ae2:networktool 工具箱，而不是点到 ME 设备误开网络状态页。
        private ActionStatus openIntoAir(TickContext tick, LocalPlayer self) {
            if (step == null) {
                int beforeId = self.containerMenu.containerId;
                step = services.interactions().pressUseKeyIntoAir(InteractionConfirmation.menuChanged(beforeId));
            }
            ActionStatus status = step.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            step.close();
            step = null;
            if (status instanceof ActionStatus.Failed failed) {
                return nextOrFail("对空右键未能打开网络工具箱：" + failed.problem().message());
            }
            AbstractContainerMenu raw = self.containerMenu;
            if (!compat.call("核对网络工具箱界面", () -> reads.isNetworkToolMenu(raw))) {
                // 误开网络状态页或其他容器界面时立即关窗，避免滞留屏幕挡住后续交互。
                PortableCellCatalog.closeUnexpectedMenu(self);
                return nextOrFail("打开的不是网络工具升级卡箱界面");
            }
            menu = ClientOpenedMenu.claimCurrent(services.context(),
                    new ClientMenuContent(services.context(), services.menuLayouts()));
            if (menu == null) return nextOrFail("网络工具箱界面打开后立即关闭");
            waited = 0;
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        private ActionStatus waitSync() {
            if (menu.reading().isPresent()) {
                round = new MenuTakeRound(menu, List.of(new UpgradeWant()), new UpgradeEndings());
                stage = Stage.TAKE;
                return ActionStatus.progressed();
            }
            if (++waited > SYNC_WAIT_TICKS) {
                menu.abandon();
                menu = null;
                return nextOrFail("网络工具箱界面内容同步超时");
            }
            return ActionStatus.running();
        }

        private ActionStatus tickRound(TickContext tick) {
            return round.tick(tick, menu.reading());
        }

        private ActionStatus nextOrFail(String reason) {
            deadEnds.add(reason);
            if (!candidates.isEmpty()) {
                current = candidates.poll();
                menu = null;
                round = null;
                stage = Stage.HAND;
                return ActionStatus.progressed();
            }
            stage = Stage.DONE;
            return finishFailure();
        }

        private ActionStatus afterClosed() {
            menu = null;
            round = null;
            if (obtained() >= request.count() || candidates.isEmpty()) {
                stage = Stage.DONE;
                return obtained() > 0 ? ActionStatus.done() : finishFailure();
            }
            current = candidates.poll();
            stage = Stage.HAND;
            return ActionStatus.progressed();
        }

        private ActionStatus finishFailure() {
            String message = deadEnds.isEmpty()
                    ? "未能从网络工具箱取出" + request.wanted().describe()
                    : String.join("；", deadEnds);
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, message, null));
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
            if (step != null) step.pause();
            if (menu != null) menu.abandon();
        }

        @Override public void close() {
            if (step != null) {
                step.close();
                step = null;
            }
            if (round != null) round.close(false);
            if (menu != null && stage != Stage.DONE) {
                menu.abandon();
                menu = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            return menu != null ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "从随身网络工具箱取出" + request.wanted().describe();
        }

        private final class UpgradeWant implements MenuTakeRound.Want {
            @Override public String label() {
                return "从网络工具箱取" + request.wanted().describe();
            }

            @Override public Predicate<SlotSnapshot> matcher() {
                return slot -> !slot.isEmpty() && wanted.test(PortableCellCatalog.idOf(slot.stack()));
            }

            @Override public int stillNeeded(MenuContent.Reading reading) {
                return request.count() - obtained();
            }

            @Override public boolean settleAfterDiverge() {
                return true;
            }
        }

        private final class UpgradeEndings implements MenuTakeRound.Endings {
            @Override public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
            }

            @Override public ActionStatus finishAfterClosed(boolean tookAnything) {
                return afterClosed();
            }

            @Override public ActionStatus finishAfterMenuGone(boolean tookAnything) {
                return afterClosed();
            }
        }
    }
}
