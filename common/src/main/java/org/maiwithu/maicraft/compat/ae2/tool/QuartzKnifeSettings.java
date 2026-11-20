// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.menu.ClientMenuContent;
import org.maiwithu.maicraft.behavior.menu.ClientOpenedMenu;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 石英切割刀（赛特斯石英切割刀与下界石英切割刀）的物品设置适配：
 * 通过设置键 engrave_name 刻制指定名称的命名压印模板（ae2:name_press）。
 *
 * <p>执行流程：校验名称非空且身上带有 #ae2:metal_ingots 金属锭（缺锭按 NEED_ITEM 拒绝）→
 * 切到主手普通右键打开 ae2:quartzknife → 放 1 个金属锭入 MACHINE_INPUT →
 * 发 GuiActionPacket("setName", wantedName) → 从 MACHINE_OUTPUT 取走命名压印模板 →
 * 关窗前显式将槽 0 任何残留金属锭收回背包防止 QuartzKnifeMenu.removed() 掉地 →
 * 关窗后经带角色核对重载 holds(stack, player, "engrave_name", wanted) 核验背包产出。
 */
public final class QuartzKnifeSettings implements ItemSettings {

    public static final String CERTUS_KNIFE = "ae2:certus_quartz_cutting_knife";
    public static final String NETHER_KNIFE = "ae2:nether_quartz_cutting_knife";
    public static final String NAME_PRESS = "ae2:name_press";
    public static final String METAL_INGOTS_TAG = "ae2:metal_ingots";
    public static final String KEY_ENGRAVE_NAME = "engrave_name";

    private static final Set<String> KNIVES = Set.of(CERTUS_KNIFE, NETHER_KNIFE);
    private static final int OPEN_WAIT_TICKS = 40;
    private static final int SYNC_WAIT_TICKS = 40;
    private static final int OUTPUT_WAIT_TICKS = 30;
    private static final int RECOVER_WAIT_TICKS = 20;
    static final int MAX_PICK_ATTEMPTS = 3;
    private static final long GIVE_UP_TICKS = 20L * 30;

    /** 放置金属锭阶段的纯判断结论。 */
    enum PlaceDecision { SEND_NAME, PLACE_ONE, RETRY_PICK, FAIL_PICK }

    /** 取走命名压印模板阶段的纯判断结论（单次取货防连发）。 */
    enum TakeDecision { DONE_RECOVER, SEND_QUICK_MOVE, WAIT_SYNC, TIMEOUT_NO_OUTPUT, TIMEOUT_UNSYNCED }

    /** 判断放锭阶段下一步：光标已空时必须核实槽 0 真有金属锭才允许发 setName，空槽时回退重试拿起（带次数上限）。 */
    static PlaceDecision decidePlaceStep(boolean cursorEmpty, boolean inputOccupied, int pickAttempts) {
        if (!cursorEmpty) return PlaceDecision.PLACE_ONE;
        if (inputOccupied) return PlaceDecision.SEND_NAME;
        return pickAttempts < MAX_PICK_ATTEMPTS ? PlaceDecision.RETRY_PICK : PlaceDecision.FAIL_PICK;
    }

    /** 判断取模板阶段下一步：未发过取货且输出槽非空时仅发一次 quickMove，已发后绝不连发，只等背包同步或超时。 */
    static TakeDecision decideTakeStep(boolean holdsPress, boolean takeSent, boolean outputOccupied, int waited) {
        if (holdsPress) return TakeDecision.DONE_RECOVER;
        if (!takeSent) {
            if (outputOccupied) return TakeDecision.SEND_QUICK_MOVE;
            return waited > OUTPUT_WAIT_TICKS ? TakeDecision.TIMEOUT_NO_OUTPUT : TakeDecision.WAIT_SYNC;
        }
        return waited > OUTPUT_WAIT_TICKS ? TakeDecision.TIMEOUT_UNSYNCED : TakeDecision.WAIT_SYNC;
    }

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;
    private final Function<ItemStack, String> itemIdReader;

    public QuartzKnifeSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this(compat, reads, services, PortableCellCatalog::idOf);
    }

    QuartzKnifeSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services,
            Function<ItemStack, String> itemIdReader) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.itemIdReader = Objects.requireNonNull(itemIdReader, "itemIdReader");
    }

    @Override public boolean covers(ItemStack stack) {
        return stack != null && !stack.isEmpty() && KNIVES.contains(itemIdReader.apply(stack));
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        if (!covers(stack)) return List.of();
        return List.of(new MachineSetting(KEY_ENGRAVE_NAME, "", List.of(),
                "输入非空名称，消耗 1 个满足 #ae2:metal_ingots 的金属锭与 1 点耐久刻制命名压印模板（ae2:name_press）"));
    }

    @Override public boolean holds(ItemStack stack, String key, String wanted) {
        return false;
    }

    // 结合本地角色背包核对是否已产出带对应 AEComponents.NAME_PRESS_NAME 的 ae2:name_press（切割刀最后一点耐久碎裂后依然生效）。
    @Override public boolean holds(ItemStack stack, Player player, String key, String wanted) {
        if (!KEY_ENGRAVE_NAME.equals(key) || player == null || wanted == null || wanted.isBlank()) {
            return false;
        }
        String expected = wanted.trim();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (matchesNamedPress(inventory.items.get(slot), expected)) return true;
        }
        return matchesNamedPress(player.getOffhandItem(), expected);
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        if (wanted == null || wanted.size() != 1 || !wanted.containsKey(KEY_ENGRAVE_NAME)) {
            return Optional.empty();
        }
        String name = wanted.get(KEY_ENGRAVE_NAME);
        return Optional.of(new EngraveAction(inventorySlot, name == null ? "" : name.trim()));
    }

    /** 判断某件物品是不是满足刻名要求的金属锭（先看物品标签表，再看模组读端）。 */
    boolean isMetalIngot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String id = PortableCellCatalog.idOf(stack);
        if (services.itemTags().tagsOf(id).contains(METAL_INGOTS_TAG)) return true;
        return compat.call("判断物品是否为 AE2 金属锭", () -> reads.isMetalIngot(stack));
    }

    boolean hasMetalIngot(Player player) {
        if (player == null) return false;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (isMetalIngot(inventory.items.get(slot))) return true;
        }
        return false;
    }

    private boolean matchesNamedPress(ItemStack candidate, String expectedName) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (!NAME_PRESS.equals(itemIdReader.apply(candidate))) return false;
        Optional<String> actual = compat.call("读取命名压印模板名称", () -> reads.namePressName(candidate));
        return actual.map(expectedName::equals).orElse(false);
    }

    /**
     * 刻制命名压印模板的完整动作：验名称与金属锭 → 换到主手普通右键开窗 → 放 1 锭入槽 0 →
     * 发 setName 动作 → 从槽 1 取走模板 → 关窗前清空槽 0 防掉锭 → 关窗。
     */
    private final class EngraveAction implements Action {

        private enum Stage {
            CHECK, HAND, OPEN, SYNC, PICK_INGOT, PLACE_ONE_INGOT, RETURN_REST_INGOT,
            SEND_NAME, TAKE_OUTPUT, RECOVER_INPUT, CLOSE, DONE
        }

        private final int knifeSlot;
        private final String wantedName;
        private Stage stage = Stage.CHECK;
        private Action handStep;
        private PendingInteraction opening;
        private OpenedMenu menu;
        private Action closing;
        private int inputSlot = -1;
        private int outputSlot = -1;
        private int ingotPlayerSlot = -1;
        private int pickAttempts;
        private boolean takeSent;
        private int waited;
        private int totalTicks;
        private Problem failure;

        EngraveAction(int knifeSlot, String wantedName) {
            this.knifeSlot = knifeSlot;
            this.wantedName = wantedName;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            if (++totalTicks > GIVE_UP_TICKS && stage != Stage.RECOVER_INPUT && stage != Stage.CLOSE
                    && stage != Stage.DONE) {
                return abortRecovering(Problem.of(Problem.Kind.STUCK, "石英切割刀刻制命名压印模板超时"));
            }
            return switch (stage) {
                case CHECK -> checkPreconditions(player.localPlayer());
                case HAND -> moveKnifeToHand(tick, player.localPlayer());
                case OPEN -> openMenu(player);
                case SYNC -> waitMenuSync();
                case PICK_INGOT -> pickMetalIngot();
                case PLACE_ONE_INGOT -> placeOneIngot();
                case RETURN_REST_INGOT -> returnRestIngots();
                case SEND_NAME -> sendSetName(player.localPlayer());
                case TAKE_OUTPUT -> takeNamedPress(player.localPlayer());
                case RECOVER_INPUT -> recoverInputSlotBeforeClose();
                case CLOSE -> tickClose(tick);
                case DONE -> failure == null ? ActionStatus.done() : ActionStatus.failed(failure);
            };
        }

        // 先验名称非空与身上确实有金属锭：缺锭按 NEED_ITEM 拒绝，不偷偷替造。
        private ActionStatus checkPreconditions(LocalPlayer self) {
            if (wantedName.isBlank()) {
                failure = Problem.of(Problem.Kind.INVALID_PARAMETER, "刻制命名压印模板的名称不能为空");
                stage = Stage.DONE;
                return ActionStatus.failed(failure);
            }
            if (!hasMetalIngot(self)) {
                String msg = isMetalIngot(self.getOffhandItem())
                        ? "金属锭目前在副手，石英切割刀界面无法直接拿副手物品，请先放回主背包再刻名"
                        : "身上没有满足 #" + METAL_INGOTS_TAG + " 的金属锭（例如铁锭、金锭、铜锭），无法使用石英切割刀刻名";
                failure = Problem.of(Problem.Kind.NEED_ITEM, msg);
                stage = Stage.DONE;
                return ActionStatus.failed(failure);
            }
            stage = Stage.HAND;
            return ActionStatus.progressed();
        }

        private ActionStatus moveKnifeToHand(TickContext tick, LocalPlayer self) {
            if (handStep == null) {
                ItemStack targetStack = knifeSlot == PortableCellCatalog.OFFHAND_SLOT
                        ? self.getOffhandItem()
                        : (knifeSlot >= 0 && knifeSlot < self.getInventory().items.size()
                                ? self.getInventory().items.get(knifeSlot) : ItemStack.EMPTY);
                handStep = PortableCellCatalog.moveSlotToMainhand(services, self, knifeSlot,
                        PortableCellCatalog.idOf(targetStack)).orElse(null);
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
                failure = Problem.of(failed.problem().kind(), "没能把石英切割刀拿到主手：" + failed.problem().message());
                stage = Stage.DONE;
                return ActionStatus.failed(failure);
            }
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        private ActionStatus openMenu(PlayerContext player) {
            LocalPlayer self = player.localPlayer();
            if (opening == null) {
                int beforeId = self.containerMenu.containerId;
                PendingInteraction submitted = player.interactionSender().useItem(player, InteractionHand.MAIN_HAND,
                        ctx -> ctx.localPlayer().containerMenu.containerId != beforeId
                                ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING,
                        OPEN_WAIT_TICKS);
                if (submitted == null) return ActionStatus.running();
                opening = submitted;
                waited = 0;
                return ActionStatus.running();
            }
            if (!opening.terminal()) opening = player.interactionSender().poll(player, opening);
            if (!opening.terminal()) {
                if (++waited > OPEN_WAIT_TICKS) {
                    failure = Problem.of(Problem.Kind.STUCK, "打开石英切割刀界面超时");
                    stage = Stage.DONE;
                    return ActionStatus.failed(failure);
                }
                return ActionStatus.running();
            }
            AbstractContainerMenu raw = self.containerMenu;
            if (!compat.call("核对石英切割刀界面", () -> reads.isQuartzKnifeMenu(raw))) {
                // 误开其他方块或物品界面时立即关窗，防止错误界面滞留屏幕卡住后续动作。
                PortableCellCatalog.closeUnexpectedMenu(self);
                failure = Problem.of(Problem.Kind.REFUSED_BY_GAME, "右键石英切割刀后未打开石英切割刀界面");
                stage = Stage.DONE;
                return ActionStatus.failed(failure);
            }
            menu = ClientOpenedMenu.claimCurrent(services.context(),
                    new ClientMenuContent(services.context(), services.menuLayouts()));
            if (menu == null) {
                failure = Problem.of(Problem.Kind.TARGET_GONE, "石英切割刀界面打开后立即关闭");
                stage = Stage.DONE;
                return ActionStatus.failed(failure);
            }
            waited = 0;
            stage = Stage.SYNC;
            return ActionStatus.progressed();
        }

        private ActionStatus waitMenuSync() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isPresent()) {
                List<Integer> inputs = compat.menuSlotsOf("MACHINE_INPUT");
                List<Integer> outputs = compat.menuSlotsOf("MACHINE_OUTPUT");
                List<Integer> containerIds = reading.get().containerSlotIds();
                inputSlot = !inputs.isEmpty() ? inputs.getFirst() : (containerIds.size() >= 1 ? containerIds.get(0) : -1);
                outputSlot = !outputs.isEmpty() ? outputs.getFirst() : (containerIds.size() >= 2 ? containerIds.get(1) : -1);
                if (inputSlot < 0 || outputSlot < 0) {
                    return abortRecovering(Problem.of(Problem.Kind.UNSUPPORTED, "认不出石英切割刀界面的输入槽与输出槽"));
                }
                stage = Stage.PICK_INGOT;
                return ActionStatus.progressed();
            }
            if (++waited > SYNC_WAIT_TICKS) {
                return abortRecovering(Problem.of(Problem.Kind.STUCK, "石英切割刀界面同步超时"));
            }
            return ActionStatus.running();
        }

        private ActionStatus pickMetalIngot() {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) return abortRecovering(Problem.of(Problem.Kind.TARGET_GONE, "石英切割刀界面中途关闭"));
            if (menu.busy()) return ActionStatus.running();
            MenuContent.Reading reading = readingOpt.get();
            // 若输入槽已有金属锭，直接跳到发 setName。
            if (isContainerSlotOccupied(reading, inputSlot)) {
                stage = Stage.SEND_NAME;
                return ActionStatus.progressed();
            }
            int found = findIngotPlayerSlot(reading);
            if (found < 0) {
                return abortRecovering(Problem.of(Problem.Kind.NEED_ITEM, "界面内找不到可用的金属锭"));
            }
            ingotPlayerSlot = found;
            menu.noteCursorTakenFrom(found);
            if (!menu.click(found, 0)) {
                return failIfWaited("拿起金属锭失败");
            }
            waited = 0;
            stage = Stage.PLACE_ONE_INGOT;
            return ActionStatus.progressed();
        }

        // 右键只往槽 0 放 1 个金属锭；若光标空着必须先核实槽 0 真有金属锭才发 setName，空槽时回退重试拿起。
        private ActionStatus placeOneIngot() {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) return abortRecovering(Problem.of(Problem.Kind.TARGET_GONE, "石英切割刀界面中途关闭"));
            if (menu.busy()) return ActionStatus.running();
            boolean inputOccupied = isContainerSlotOccupied(readingOpt.get(), inputSlot);
            return switch (decidePlaceStep(menu.cursorEmpty(), inputOccupied, pickAttempts)) {
                case SEND_NAME -> {
                    waited = 0;
                    stage = Stage.SEND_NAME;
                    yield ActionStatus.progressed();
                }
                case RETRY_PICK -> {
                    pickAttempts++;
                    waited = 0;
                    stage = Stage.PICK_INGOT;
                    yield ActionStatus.progressed();
                }
                case FAIL_PICK -> abortRecovering(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "多次尝试未能把金属锭放入石英切割刀输入槽"));
                case PLACE_ONE -> {
                    if (!menu.click(inputSlot, 1)) {
                        yield failIfWaited("放入金属锭到石英切割刀输入槽失败");
                    }
                    waited = 0;
                    stage = Stage.RETURN_REST_INGOT;
                    yield ActionStatus.progressed();
                }
            };
        }

        private ActionStatus returnRestIngots() {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) return abortRecovering(Problem.of(Problem.Kind.TARGET_GONE, "石英切割刀界面中途关闭"));
            if (menu.busy()) return ActionStatus.running();
            if (!menu.cursorEmpty() && ingotPlayerSlot >= 0) {
                if (!menu.click(ingotPlayerSlot, 0)) {
                    return failIfWaited("归还多余金属锭到背包失败");
                }
                waited = 0;
                return ActionStatus.progressed();
            }
            if (!isContainerSlotOccupied(readingOpt.get(), inputSlot)) {
                if (pickAttempts < MAX_PICK_ATTEMPTS) {
                    pickAttempts++;
                    waited = 0;
                    stage = Stage.PICK_INGOT;
                    return ActionStatus.progressed();
                }
                return abortRecovering(Problem.of(Problem.Kind.REFUSED_BY_GAME, "金属锭未落入石英切割刀输入槽"));
            }
            waited = 0;
            stage = Stage.SEND_NAME;
            return ActionStatus.progressed();
        }

        // 优先走读写端调用公开 QuartzKnifeMenu.setName 同步两端名称，离线替身无读写端时回退通用动作包。
        private ActionStatus sendSetName(LocalPlayer self) {
            if (menu.busy()) return ActionStatus.running();
            AbstractContainerMenu raw = self.containerMenu;
            boolean sent = compat.call("设置石英切割刀刻名", () -> reads.setQuartzKnifeName(raw, wantedName))
                    || compat.sendMenuStringAction("setName", wantedName);
            if (!sent) {
                return abortRecovering(Problem.of(Problem.Kind.REFUSED_BY_GAME, "发送石英切割刀 setName 动作失败"));
            }
            takeSent = false;
            waited = 0;
            stage = Stage.TAKE_OUTPUT;
            return ActionStatus.progressed();
        }

        // 发一次 quickMove(outputSlot) 后置 takeSent=true 等待背包同步，即使输入槽有余锭也绝不连发重复刻模板。
        private ActionStatus takeNamedPress(LocalPlayer self) {
            boolean hasPress = holds(ItemStack.EMPTY, self, KEY_ENGRAVE_NAME, wantedName);
            if (hasPress) {
                waited = 0;
                stage = Stage.RECOVER_INPUT;
                return ActionStatus.progressed();
            }
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) {
                if (failure == null) {
                    failure = Problem.of(Problem.Kind.TARGET_GONE, "石英切割刀界面中途关闭，未能取出命名压印模板");
                }
                stage = Stage.DONE;
                return ActionStatus.failed(failure);
            }
            if (menu.busy()) return ActionStatus.running();
            boolean outputOccupied = isContainerSlotOccupied(readingOpt.get(), outputSlot);
            TakeDecision decision = decideTakeStep(false, takeSent, outputOccupied, waited + 1);
            return switch (decision) {
                case DONE_RECOVER -> {
                    waited = 0;
                    stage = Stage.RECOVER_INPUT;
                    yield ActionStatus.progressed();
                }
                case SEND_QUICK_MOVE -> {
                    if (menu.quickMove(outputSlot)) {
                        takeSent = true;
                        waited = 0;
                        yield ActionStatus.progressed();
                    }
                    if (++waited > OUTPUT_WAIT_TICKS) {
                        yield abortRecovering(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                                "未能从石英切割刀输出槽取走命名压印模板（背包可能已满）"));
                    }
                    yield ActionStatus.running();
                }
                case WAIT_SYNC -> {
                    waited++;
                    yield ActionStatus.running();
                }
                case TIMEOUT_NO_OUTPUT -> abortRecovering(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "石英切割刀输出槽未出现名为 \"" + wantedName + "\" 的命名压印模板"));
                case TIMEOUT_UNSYNCED -> abortRecovering(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "已向石英切割刀输出槽发过一次取货，但背包未同步到名为 \"" + wantedName + "\" 的命名压印模板（背包可能已满）"));
            };
        }

        // 关窗前防掉锭纪律：把槽 0 任何剩余金属锭全部快速移回背包，绝不交给 QuartzKnifeMenu.removed() 掉到地上。
        private ActionStatus recoverInputSlotBeforeClose() {
            Optional<MenuContent.Reading> readingOpt = menu == null ? Optional.empty() : menu.reading();
            if (readingOpt.isEmpty() || inputSlot < 0) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            if (menu.busy()) return ActionStatus.running();
            if (!menu.cursorEmpty() && ingotPlayerSlot >= 0) {
                if (menu.click(ingotPlayerSlot, 0)) {
                    return ActionStatus.progressed();
                }
                if (++waited > RECOVER_WAIT_TICKS) {
                    stage = Stage.CLOSE;
                    return ActionStatus.progressed();
                }
                return ActionStatus.running();
            }
            if (isContainerSlotOccupied(readingOpt.get(), inputSlot)) {
                if (++waited > RECOVER_WAIT_TICKS) {
                    stage = Stage.CLOSE;
                    return ActionStatus.progressed();
                }
                if (!menu.quickMove(inputSlot)) return ActionStatus.running();
                return ActionStatus.progressed();
            }
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        private ActionStatus failIfWaited(String reason) {
            if (++waited > SYNC_WAIT_TICKS) {
                return abortRecovering(Problem.of(Problem.Kind.STUCK, reason));
            }
            return ActionStatus.running();
        }

        private ActionStatus tickClose(TickContext tick) {
            if (closing == null) {
                closing = menu == null ? null : menu.closing();
                if (closing == null) {
                    stage = Stage.DONE;
                    return failure == null ? ActionStatus.done() : ActionStatus.failed(failure);
                }
            }
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            closing.close();
            closing = null;
            menu = null;
            stage = Stage.DONE;
            return failure == null ? ActionStatus.done() : ActionStatus.failed(failure);
        }

        private ActionStatus abortRecovering(Problem reason) {
            if (failure == null) failure = reason;
            if (menu != null) {
                waited = 0;
                stage = Stage.RECOVER_INPUT;
                return ActionStatus.progressed();
            }
            stage = Stage.DONE;
            return ActionStatus.failed(failure);
        }

        private int findIngotPlayerSlot(MenuContent.Reading reading) {
            List<SlotSnapshot> playerSlots = reading.playerSnapshots();
            for (int i = 0; i < playerSlots.size(); i++) {
                SlotSnapshot slot = playerSlots.get(i);
                if (!slot.isEmpty() && isMetalIngot(slot.stack())) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        private static boolean isContainerSlotOccupied(MenuContent.Reading reading, int menuSlotId) {
            int idx = reading.containerSlotIds().indexOf(menuSlotId);
            return idx >= 0 && !reading.containerSnapshots().get(idx).isEmpty();
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
            return "用石英切割刀刻制命名压印模板 \"" + wantedName + "\"";
        }
    }
}
