// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 无界面线缆部件的直接右键交互配置动作：
 * <ul>
 *   <li>存储监视器 / 转换监视器：{@code locked}（空手潜行右键部件盒）与 {@code displayed}（解锁后持样本右键，流体容器二次右键切流体）；</li>
 *   <li>6 种 P2P 通道：{@code attunement}（持调谐物品右键替换，不消耗）与 {@code p2p_role}（强制主手内存卡，副手明确报 NEED_ITEM）。</li>
 * </ul>
 */
final class Ae2DirectPartAction implements Action {

    /** 动作种类。 */
    enum Kind {
        MONITOR_LOCK,
        MONITOR_DISPLAY,
        P2P_ATTUNEMENT,
        P2P_ROLE
    }

    private enum Stage { CHECK, PREPARE_UNLOCK, APPROACH_UNLOCK, UNLOCK, HAND, APPROACH, CLICK, SECOND_CLICK, DONE }

    private final Ae2Compat compat;
    private final Ae2PartControl control;
    private final Ae2PartCache cache;
    private final PlayerServices services;
    private final BlockPos host;
    private final Direction side;
    private final Kind kind;
    private final String wanted;
    private final Permissions permissions;

    private Stage stage = Stage.CHECK;
    private Action step;
    private AABB partBox;
    private String itemToHold;
    private boolean needEmptyHand;
    private boolean sneakClick;
    private boolean needSecondFluidClick;
    private int p2pFreqBefore;
    private boolean p2pExpectNewFreq;
    private Problem problem;

    Ae2DirectPartAction(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache, PlayerServices services,
                        BlockPos host, Direction side, Kind kind, String wanted, Permissions permissions) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.services = Objects.requireNonNull(services, "services");
        this.host = host.immutable();
        this.side = Objects.requireNonNull(side, "side");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.wanted = Objects.requireNonNull(wanted, "wanted");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    /** 离线测试用入口：预设已完成前置判定后的锁定监视器解锁路径，验证先空手再靠近部件盒再解锁的顺序。 */
    static Ae2DirectPartAction forLockedUnlockTest(Ae2Compat compat, Ae2PartControl control, Ae2PartCache cache,
                                                   PlayerServices services, BlockPos host, Direction side,
                                                   AABB partBox, String wanted, String itemToHold,
                                                   Permissions permissions) {
        Ae2DirectPartAction action = new Ae2DirectPartAction(compat, control, cache, services, host, side,
                Kind.MONITOR_DISPLAY, wanted, permissions);
        action.partBox = partBox;
        action.itemToHold = itemToHold;
        action.stage = Stage.PREPARE_UNLOCK;
        return action;
    }

    /** 离线测试用：读取当前阶段名，验证状态机流转顺序。 */
    String stageName() {
        return stage.name();
    }

    @Override public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case CHECK -> check();
            case PREPARE_UNLOCK -> prepareUnlock(context);
            case APPROACH_UNLOCK -> approachForUnlock(context);
            case UNLOCK -> unlock(context);
            case HAND -> prepareHand(context);
            case APPROACH -> approach(context);
            case CLICK -> click(context, false);
            case SECOND_CLICK -> click(context, true);
            case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    // 动手前检查现场：确定部件包围盒、校验内存卡必须在主手、找齐所需样本或调谐物品。
    private ActionStatus check() {
        PlayerContext player = services.context().get();
        if (player == null || player.level() == null || player.localPlayer() == null) {
            return finish(Problem.of(Problem.Kind.STUCK, "角色不在世界里，没法操作线缆部件"));
        }
        ClientLevel level = player.level();
        partBox = control.partBox(level, host, side).orElse(null);
        if (partBox == null) {
            return finish(Problem.of(Problem.Kind.TARGET_GONE, "线缆 " + Ae2PartKeys.sideName(side) + " 面没有可交互的部件"));
        }
        return switch (kind) {
            case MONITOR_LOCK -> planMonitorLock(level);
            case MONITOR_DISPLAY -> planMonitorDisplay(level, player.localPlayer());
            case P2P_ATTUNEMENT -> planP2pAttunement(level, player.localPlayer());
            case P2P_ROLE -> planP2pRole(level, player.localPlayer());
        };
    }

    private ActionStatus planMonitorLock(ClientLevel level) {
        boolean wantLocked = Boolean.parseBoolean(wanted);
        Optional<Ae2PartControl.ClientPartReading> reading = control.readClientPart(level, host, side);
        if (reading.isPresent() && reading.get().hasMonitor() && reading.get().locked() == wantLocked) {
            cache.recordSetting(host, side, "locked", Boolean.toString(wantLocked));
            return finish(null);
        }
        needEmptyHand = true;
        sneakClick = true;
        stage = Stage.HAND;
        return ActionStatus.progressed();
    }

    private ActionStatus planMonitorDisplay(ClientLevel level, LocalPlayer player) {
        Ae2PartControl.ClientPartReading reading = control.readClientPart(level, host, side).orElse(null);
        MonitorDisplayPlan plan = decideMonitorDisplay(reading, wanted,
                findCarriedItem(player, wanted), findFluidContainer(player, wanted));
        if (plan.problem() != null) return finish(plan.problem());
        if (plan.alreadyMatched()) {
            cache.recordSetting(host, side, "displayed", wanted.equalsIgnoreCase(Ae2PartKeys.EMPTY)
                    ? Ae2PartKeys.EMPTY : wanted);
            return finish(null);
        }
        itemToHold = plan.itemToHold();
        needSecondFluidClick = plan.needSecondFluidClick();
        stage = plan.needUnlock() ? Stage.PREPARE_UNLOCK : Stage.HAND;
        return ActionStatus.progressed();
    }

    private ActionStatus planP2pAttunement(ClientLevel level, LocalPlayer player) {
        String targetPartId = Ae2PartKeys.p2pItemOf(wanted).orElse(null);
        if (targetPartId == null) {
            return finish(Problem.of(Problem.Kind.INVALID_PARAMETER, "不认识的 P2P 调谐类型：" + wanted));
        }
        String currentPartId = currentPartId(level).orElse("");
        if (targetPartId.equals(currentPartId)) {
            cache.recordSetting(host, side, "attunement", wanted);
            return finish(null);
        }
        Optional<String> trigger = findP2pTriggerItem(player, wanted);
        if (trigger.isEmpty()) {
            return finish(Problem.of(Problem.Kind.NEED_ITEM,
                    "背包里没有能把 P2P 调谐成 " + wanted + " 的触发物品（右键调谐不消耗物品）"));
        }
        itemToHold = trigger.get();
        sneakClick = false;
        stage = Stage.HAND;
        return ActionStatus.progressed();
    }

    /** P2P 配对主手内存卡前置判断结果（纯函数，离线可测）。 */
    record P2pRolePlan(Problem problem, String itemToHold, boolean sneakClick, int freqBefore, boolean expectNewFreq) {
    }

    /** 监视器设置展示物前置判断结果（纯函数，离线可测）。 */
    record MonitorDisplayPlan(Problem problem, boolean alreadyMatched, boolean needUnlock, String itemToHold,
                              boolean needSecondFluidClick) {
    }

    // AE2 P2PTunnelPart.onUseItemOn 对 hand == OFF_HAND 直接返回 false，故严格要求主手持有内存卡；
    // 同时读取点击前通道频率与已知角色，规避同频覆盖静默忽略或旧频假确认。
    private ActionStatus planP2pRole(ClientLevel level, LocalPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        ItemStack invCard = findMemoryCardStackInMainInventory(player);
        String invCardId = invCard.isEmpty() ? null : idOf(invCard);
        int freqBefore = control.readClientPart(level, host, side)
                .filter(Ae2PartControl.ClientPartReading::hasP2p)
                .map(Ae2PartControl.ClientPartReading::p2pFrequency)
                .orElse(0);
        boolean wasOutput = Boolean.TRUE.equals(
                cache.readingsFor(host, side, ServerReads.NONE).get("p2p_is_output"));
        P2pRolePlan plan = decideP2pRole(control, wanted, mainHand, offHand, invCard, invCardId, freqBefore, wasOutput);
        if (plan.problem() != null) return finish(plan.problem());
        itemToHold = plan.itemToHold();
        sneakClick = plan.sneakClick();
        p2pFreqBefore = plan.freqBefore();
        p2pExpectNewFreq = plan.expectNewFreq();
        stage = Stage.HAND;
        return ActionStatus.progressed();
    }

    /** 纯函数判定 P2P 配对的前置条件：副手内存卡必报 NEED_ITEM，同频覆盖 output 明确拒绝，避免假确认。 */
    static P2pRolePlan decideP2pRole(Ae2PartControl control, String role, ItemStack mainHand, ItemStack offHand,
                                     ItemStack invCard, String invCardId, int freqBefore, boolean wasOutput) {
        boolean mainIsCard = control.isMemoryCard(mainHand);
        boolean offIsCard = control.isMemoryCard(offHand);
        if (!mainIsCard && (invCard == null || invCard.isEmpty())) {
            String message = offIsCard
                    ? "内存卡当前在副手；AE2 的 P2P 配对强制要求主手持有 " + Ae2PartKeys.MEMORY_CARD + "（副手会被直接忽略）"
                    : "需要主手持有 " + Ae2PartKeys.MEMORY_CARD + " 才能配对 P2P 通道";
            return new P2pRolePlan(Problem.of(Problem.Kind.NEED_ITEM, message), null, false, freqBefore, false);
        }
        ItemStack card = mainIsCard ? mainHand : invCard;
        OptionalInt cardFreq = control.memoryCardP2pFrequency(card);
        boolean asOutput = "output".equals(role);
        if (asOutput) {
            if (control.memoryCardP2pType(card).isEmpty() || cardFreq.isEmpty()) {
                return new P2pRolePlan(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "主手内存卡里还没有已绑定的 P2P 频率，请先对输入端设置 p2p_role=input 复制频率"),
                        null, false, freqBefore, false);
            }
            // AE2 P2PTunnelPart.importSettings 在 frequency == this.freq 时静默忽略，避免误把输入端覆盖失效。
            if (freqBefore != 0 && freqBefore == cardFreq.getAsInt()) {
                return new P2pRolePlan(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "该 P2P 通道当前已经处于同一频率（" + Ae2PartKeys.formatFrequency(freqBefore)
                                + "），AE2 不允许同频覆盖为输出端（避免把输入端误改为输出）"),
                        null, false, freqBefore, false);
            }
            return new P2pRolePlan(null, mainIsCard ? null : invCardId, false, freqBefore, false);
        }
        // input：原本为输出端且与内存卡同频时，AE2 服务端会重新分配新频率，确认时必须等到频率变化。
        boolean expectNew = freqBefore != 0 && cardFreq.isPresent() && cardFreq.getAsInt() == freqBefore && wasOutput;
        return new P2pRolePlan(null, mainIsCard ? null : invCardId, true, freqBefore, expectNew);
    }

    /** 纯函数判定监视器设置展示物的执行步骤（已匹配 / 先解锁 / 样本直接右键 / 流体容器二次右键）。 */
    static MonitorDisplayPlan decideMonitorDisplay(Ae2PartControl.ClientPartReading reading, String wanted,
                                                   Optional<String> directItem, Optional<String> fluidContainer) {
        String current = reading != null && reading.hasMonitor() ? reading.displayed() : null;
        if (wanted.equalsIgnoreCase(Ae2PartKeys.EMPTY)) {
            if (current == null) return new MonitorDisplayPlan(null, true, false, null, false);
            return new MonitorDisplayPlan(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "AE2 监视器原生不支持交互清空已设展示物，需要拆下重装才能清空"), false, false, null, false);
        }
        if (wanted.equals(current)) {
            return new MonitorDisplayPlan(null, true, false, null, false);
        }
        boolean locked = reading != null && reading.hasMonitor() && reading.locked();
        if (directItem.isPresent()) {
            return new MonitorDisplayPlan(null, false, locked, directItem.get(), false);
        }
        if (fluidContainer.isPresent()) {
            String holder = fluidContainer.get();
            return new MonitorDisplayPlan(null, false, locked, holder, !holder.equals(current));
        }
        return new MonitorDisplayPlan(Problem.of(Problem.Kind.NEED_ITEM,
                "背包里没有用于设监视器展示的样本 " + wanted + "（或对应的流体容器）"), false, false, null, false);
    }

    // 监视器已锁定时，先空手 → 走到部件盒可达站位 → 潜行右键解锁，再换样本设展示物。
    private ActionStatus prepareUnlock(TickContext context) {
        if (step == null) {
            Optional<Action> empty = services.toMainhand().emptyMainhand();
            if (empty.isEmpty()) {
                return finish(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "腾不出空手给监视器解锁"));
            }
            step = empty.get();
        }
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        closeStep();
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        stage = Stage.APPROACH_UNLOCK;
        return ActionStatus.progressed();
    }

    // 解锁右键前先走到能看见并够得着部件盒的位置，避免隔空或背对直接交互报 UNREACHABLE。
    private ActionStatus approachForUnlock(TickContext context) {
        if (step == null) {
            step = services.approach().toward(ApproachTarget.ofBlockPart(host, partBox), permissions);
        }
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        closeStep();
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        stage = Stage.UNLOCK;
        return ActionStatus.progressed();
    }

    private ActionStatus unlock(TickContext context) {
        if (step == null) {
            InteractionConfirmation unlocked = InteractionConfirmation.expiresAsNotApplied(
                    ctx -> control.readClientPart(ctx.level(), host, side)
                            .filter(Ae2PartControl.ClientPartReading::hasMonitor)
                            .map(r -> !r.locked() ? InteractionConfirmation.Verdict.APPLIED
                                    : InteractionConfirmation.Verdict.PENDING)
                            .orElse(InteractionConfirmation.Verdict.PENDING));
            step = services.sneak().sneaking(services.interactions().useBlockPart(host, partBox, unlocked));
        }
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        closeStep();
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        cache.recordSetting(host, side, "locked", "false");
        stage = Stage.HAND;
        return ActionStatus.progressed();
    }

    private ActionStatus prepareHand(TickContext context) {
        if (!needEmptyHand && itemToHold == null) {
            stage = Stage.APPROACH;
            return ActionStatus.progressed();
        }
        if (step == null) {
            Optional<Action> action = needEmptyHand
                    ? services.toMainhand().emptyMainhand()
                    : services.toMainhand().moveToMainhand(itemToHold);
            if (action.isEmpty()) {
                return finish(Problem.of(Problem.Kind.NEED_ITEM,
                        needEmptyHand ? "背包已满，腾不出空手操作线缆部件" : "无法把 " + itemToHold + " 换到主手"));
            }
            step = action.get();
        }
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        closeStep();
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        stage = Stage.APPROACH;
        return ActionStatus.progressed();
    }

    private ActionStatus approach(TickContext context) {
        if (step == null) {
            step = services.approach().toward(ApproachTarget.ofBlockPart(host, partBox), permissions);
        }
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        closeStep();
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        stage = Stage.CLICK;
        return ActionStatus.progressed();
    }

    private ActionStatus click(TickContext context, boolean secondPass) {
        if (step == null) {
            InteractionConfirmation confirmation = buildConfirmation(secondPass);
            Action rawUse = services.interactions().useBlockPart(host, partBox, confirmation);
            step = sneakClick ? services.sneak().sneaking(rawUse) : rawUse;
        }
        ActionStatus status = step.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        closeStep();
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!secondPass && needSecondFluidClick) {
            stage = Stage.SECOND_CLICK;
            return ActionStatus.progressed();
        }
        recordSuccess();
        return finish(null);
    }

    private InteractionConfirmation buildConfirmation(boolean secondPass) {
        return InteractionConfirmation.expiresAsNotApplied(switch (kind) {
            case MONITOR_LOCK -> {
                boolean wantLocked = Boolean.parseBoolean(wanted);
                yield ctx -> control.readClientPart(ctx.level(), host, side)
                        .filter(Ae2PartControl.ClientPartReading::hasMonitor)
                        .map(r -> r.locked() == wantLocked ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING)
                        .orElse(InteractionConfirmation.Verdict.PENDING);
            }
            case MONITOR_DISPLAY -> {
                String expect = (!secondPass && needSecondFluidClick) ? itemToHold : wanted;
                yield ctx -> control.readClientPart(ctx.level(), host, side)
                        .filter(Ae2PartControl.ClientPartReading::hasMonitor)
                        .map(r -> expect.equals(r.displayed()) ? InteractionConfirmation.Verdict.APPLIED
                                : InteractionConfirmation.Verdict.PENDING)
                        .orElse(InteractionConfirmation.Verdict.PENDING);
            }
            case P2P_ATTUNEMENT -> {
                String targetPartId = Ae2PartKeys.p2pItemOf(wanted).orElse("");
                yield ctx -> currentPartId(ctx.level()).filter(targetPartId::equals).isPresent()
                        ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
            }
            case P2P_ROLE -> {
                boolean asOutput = wanted.equals("output");
                yield ctx -> confirmP2pRole(ctx, asOutput);
            }
        });
    }

    private InteractionConfirmation.Verdict confirmP2pRole(PlayerContext ctx, boolean asOutput) {
        ItemStack mainHand = ctx.localPlayer().getMainHandItem();
        OptionalInt cardFreq = control.memoryCardP2pFrequency(mainHand);
        Optional<Ae2PartControl.ClientPartReading> part = control.readClientPart(ctx.level(), host, side);
        if (part.isEmpty() || !part.get().hasP2p()) return InteractionConfirmation.Verdict.PENDING;
        return verifyP2pRole(asOutput, p2pFreqBefore, p2pExpectNewFreq, part.get().p2pFrequency(), cardFreq);
    }

    /**
     * 纯函数核对 P2P 配对点击是否已由服务端生效：
     * output 要求通道频率更新为内存卡频率且异于点击前频率；
     * input 在原为同频 output 时要求通道分配出新频率并与内存卡一致，杜绝未等回包就假确认。
     */
    static InteractionConfirmation.Verdict verifyP2pRole(boolean asOutput, int freqBefore, boolean expectNewFreq,
                                                         int partFreq, OptionalInt cardFreq) {
        if (partFreq == 0 || cardFreq.isEmpty()) return InteractionConfirmation.Verdict.PENDING;
        int card = cardFreq.getAsInt();
        if (asOutput) {
            return partFreq == card && partFreq != freqBefore
                    ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        }
        if (expectNewFreq) {
            return partFreq != freqBefore && card == partFreq
                    ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        }
        return card == partFreq ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
    }

    private void recordSuccess() {
        switch (kind) {
            case MONITOR_LOCK -> cache.recordSetting(host, side, "locked", wanted);
            case MONITOR_DISPLAY -> cache.recordSetting(host, side, "displayed", wanted);
            case P2P_ATTUNEMENT -> cache.recordSetting(host, side, "attunement", wanted);
            case P2P_ROLE -> cache.recordLocal(host, side,
                    Map.of("p2p_role", wanted),
                    Map.of("p2p_is_output", wanted.equals("output")));
        }
    }

    private Optional<String> currentPartId(ClientLevel level) {
        return compat.gridCell(level, host)
                .map(cell -> cell.parts().get(side))
                .map(Ae2Grid.PartView::itemId);
    }

    private Optional<String> findCarriedItem(LocalPlayer player, String itemId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) return Optional.of(itemId);
        }
        return Optional.empty();
    }

    private Optional<String> findFluidContainer(LocalPlayer player, String fluidId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty() && control.isFluidContainerFor(stack, fluidId)) return Optional.of(idOf(stack));
        }
        return Optional.empty();
    }

    private Optional<String> findP2pTriggerItem(LocalPlayer player, String attunement) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty() && control.p2pAttunementOf(stack).filter(attunement::equals).isPresent()) {
                return Optional.of(idOf(stack));
            }
        }
        return Optional.empty();
    }

    private ItemStack findMemoryCardStackInMainInventory(LocalPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (control.isMemoryCard(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private void closeStep() {
        if (step != null) {
            step.close();
            step = null;
        }
    }

    private ActionStatus finish(Problem reason) {
        problem = reason;
        stage = Stage.DONE;
        return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
    }

    @Override public void pause() {
        if (step != null) step.pause();
    }

    @Override public void close() {
        closeStep();
    }

    @Override public Interruptibility interruptibility() {
        return Interruptibility.WORKING;
    }

    @Override public String describe() {
        return "直接交互配置线缆 " + Ae2PartKeys.sideName(side) + " 面部件为 " + wanted;
    }
}
