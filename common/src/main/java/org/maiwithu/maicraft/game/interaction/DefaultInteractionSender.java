// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.Objects;
import java.util.function.BiFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.MenuVisibility;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 真正调用游戏的挖掘、使用、攻击等操作，并保存一项待确认动作；结果由后续观察判断，不直接用 API 返回值。 */
public final class DefaultInteractionSender implements InteractionSender {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultInteractionSender.class);

    private final MenuActions menuActions;

    /** 当前正在等确认的动作；副手放置单独记录，与主动作并行等待。 */
    private PendingInteraction active;
    private PendingInteraction auxiliary;
    /** 只有确实进入过举盾、进食等持续持用，松手后才适用提前收尾；瞬时右键等待自身的原生确认。 */
    private boolean activeItemUseWasHeld;
    private boolean activeProtocolUsesMenu;
    /** 任务已经退出但本刻无交互机会时，端口暂时接住持用，下一刻切槽取消而不是松弓射箭。 */
    private final PendingMainHandStop mainHandStop = new PendingMainHandStop();
    /** 持续使用自己结束了还在等确认的兜底收尾账。 */
    private final AbandonedItemUse abandonedItemUse = new AbandonedItemUse();
    /** 持续挖掘的开挖、推进与停手；任务边界欠下的停挖原因也归它记。 */
    private final MiningActions mining = new MiningActions(this);

    public DefaultInteractionSender(MenuActions menuActions) {
        this.menuActions = menuActions;
    }

    String diagnosticState() {
        return active == null ? "none" : active.kind() + ":" + active.status()
                + " submitted=" + active.submittedTick() + " deadline=" + active.deadlineTick();
    }

    @Override public PendingInteraction tryAuxiliaryBlockUse(PlayerContext context, BlockHitResult hit,
                                                             InteractionConfirmation confirmation, int timeoutTicks) {
        // 主手持续操作和旧副手点击优先结算；本刻主任务没出手时，才允许一次副手原生放置。
        if (!context.canInteractThisTick() || !settledForRoutinePause() || menuActions.hasPendingTransaction()
                || auxiliary != null && !auxiliary.terminal()) return null;
        PendingInteraction previous = active;
        auxiliary = useBlock(context, InteractionHand.OFF_HAND, hit, confirmation, timeoutTicks);
        active = previous;
        return auxiliary;
    }

    /** 非紧急生活动作等旧点击、停挖和取消蓄力都结清后接手，不能打断正在收尾的动作。 */
    boolean settledForRoutinePause() { return (active == null || active.terminal())
            && !mining.owesCancellation() && !mainHandStop.owed(); }

    @Override public PendingInteraction dropSelected(PlayerContext context, ItemStack expectedSelected, boolean fullStack,
                                                     InteractionConfirmation confirmation, int timeoutTicks) {
        // 原生 Q 使用服务端朝向，先同步已经实际转到的视角，再调用玩家原生投掷；不创建实体或设置其速度。
        // 本刻不收时交回 null，东西留在手上，由调用方下一刻再丢。
        if (!acceptSubmission(context)) return null;
        var player = context.localPlayer();
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()
                || !MenuVisibility.worldInputAllowed(Minecraft.getInstance().screen) || player.isUsingItem())
            throw new IllegalStateException("selected-stack drop requires an idle world view and empty cursor");
        if (player.getInventory().selected < 0 || player.getInventory().selected > 8 || expectedSelected.isEmpty()
                || !ItemStack.matches(player.getMainHandItem(), expectedSelected))
            throw new IllegalStateException("selected stack changed before native drop");
        Objects.requireNonNull(confirmation, "drop requires exact debit and receiving evidence");
        PendingInteraction pending = oneShot(PendingInteraction.Kind.DROP_SELECTED, context, confirmation, timeoutTicks);
        try {
            context.localPlayer().connection.send(new ServerboundMovePlayerPacket.Rot(
                    player.getYRot(), player.getXRot(), player.onGround()));
            if (!player.drop(fullStack)) pending.finish(
                    PendingInteraction.Status.CONFIRMED_NOT_APPLIED, "native player drop rejected the selected stack");
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN, "native drop entered submission but its outcome is unknown");
        }
        return poll(context, pending);
    }

    /** 菜单按钮的模组协议也须先取得原生确认，再允许世界任务退出该界面。 */
    @Override public boolean hasPendingMenuTransaction() { return activeProtocolUsesMenu && active != null && !active.terminal(); }

    // 持用按键只能绑定仍是本端口当前动作的确认；新动作接管后，旧吃饭任务不能继续按住或松开。
    // 刷子这类由右键方块开始的持用也算：右键确认生效后，这次按住仍归提交方，直到别的动作接管。
    public boolean ownsItemUse(PendingInteraction pending) {
        if (pending == null || pending != active) return false;
        if (pending.kind() == PendingInteraction.Kind.USE_ITEM) {
            return !pending.terminal() || pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED;
        }
        return pending.kind() == PendingInteraction.Kind.USE_BLOCK
                && pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED;
    }

    @Override
    public PendingInteraction startBreaking(PlayerContext context, BlockHitResult hit, int timeoutTicks) {
        return mining.start(context, hit, timeoutTicks);
    }

    @Override
    public PendingInteraction cancelBreaking(PlayerContext context, PendingInteraction pending) {
        return mining.stop(context, pending);
    }

    @Override
    public PendingInteraction cancelBreakingForTaskBoundary(
            PlayerContext context, PendingInteraction pending, String boundaryReason) {
        return mining.stopForTaskBoundary(context, pending, boundaryReason);
    }

    @Override
    public PendingInteraction continueBreaking(PlayerContext context, PendingInteraction pending) {
        return mining.advance(context, pending);
    }

    @Override
    public PendingInteraction useBlock(PlayerContext context, InteractionHand hand, BlockHitResult hit,
                                       InteractionConfirmation confirmation, int timeoutTicks) {
        // 发一次普通方块右键，再用调用者给的条件查结果；游戏本身可能先在客户端预测放置效果。
        // 本刻不收时交回 null，由调用方下一刻重试，不算点击失败。
        if (!acceptSubmission(context)) return null;
        BlockUseConfirmation acknowledged = null;
        if (confirmation.requiresBlockAcknowledgement()) {
            if (!(context.level() instanceof BlockUseAcknowledgement sequences))
                throw new IllegalStateException("native block acknowledgement hook is unavailable");
            acknowledged = new BlockUseConfirmation(confirmation, sequences);
        }
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.USE_BLOCK, context,
                acknowledged == null ? confirmation : acknowledged, timeoutTicks);
        try {
            var player = context.localPlayer();
            // 任务在玩家移动包之后执行，渲染更新的镜头可能比服务端已知朝向更新。
            // 方块交互包不含朝向和潜行状态，因此点击前先同步实际身体姿态。
            player.connection.send(new ServerboundMovePlayerPacket.Rot(
                    player.getYRot(), player.getXRot(), player.onGround()));
            player.connection.send(new ServerboundPlayerCommandPacket(player, player.isShiftKeyDown()
                    ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                    : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            // 火把等附着方块在实机上出现过“出手成功但方块不出现”。把提交现场的点击面、
            // 客户端预测结果与预测包是否发出逐项落到确认记录里，下一轮实机确认即可看出服务端对这次点击做了什么。
            int sequenceBefore = context.level() instanceof BlockUseAcknowledgement acknowledgement
                    ? acknowledgement.maicraft$currentBlockSequence() : Integer.MIN_VALUE;
            var result = gameMode(context).useItemOn(player, hand, hit);
            if (acknowledged != null) acknowledged.submitted();
            pending.attachUseOnTrace(UseOnTrace.describe(context, hand, hit, result, sequenceBefore));
            // 点击现场已随确认记录交给调用方（提交失败记录带 use_on_trace）；日志降为 debug，
            // 建造时每放一块都打 INFO 会刷屏，排查时再打开 debug 对照服务端现场。
            LOG.debug("[maicraft-interaction] {}", pending.useOnTrace());
            if (result.shouldSwing()) player.swing(hand);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native block use threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction useItem(PlayerContext context, InteractionHand hand,
                                      InteractionConfirmation confirmation, int timeoutTicks) {
        // 本刻不收时交回 null，由调用方下一刻重试，不算使用失败。
        if (!acceptSubmission(context)) return null;
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.USE_ITEM, context, confirmation, timeoutTicks);
        try {
            var result = gameMode(context).useItem(context.localPlayer(), hand);
            activeItemUseWasHeld = context.localPlayer().isUsingItem();
            if (result.shouldSwing()) context.localPlayer().swing(hand);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native item use threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction releaseUsingItem(PlayerContext context, PendingInteraction pending) {
        // 持续吃东西、拉弓等需要实际松开使用；新的“已停止使用”确认接替原来的使用确认。
        // 松开认两种起点：直接按住手中物品，或右键方块开始的按住（刷子刷可疑的沙子、沙砾）。
        // 本刻没有交互机会时交回 null：按住使用键的投影两刻内自然过期，原版就看到松键。
        if (!claimSubmission(context)) return null;
        if (pending == null || pending != active && pending != auxiliary
                || pending.kind() != PendingInteraction.Kind.USE_ITEM
                        && pending.kind() != PendingInteraction.Kind.USE_BLOCK) {
            throw new IllegalArgumentException("the pending interaction is not the active native action");
        }
        PendingInteraction release = oneShot(
                PendingInteraction.Kind.RELEASE_ITEM,
                context,
                InteractionConfirmation.itemUseStopped(),
                10);
        try {
            gameMode(context).releaseUsingItem(context.localPlayer());
        } catch (RuntimeException failure) {
            release.finish(PendingInteraction.Status.UNCERTAIN,
                    "native use release threw before the outcome was confirmed");
        }
        return poll(context, release);
    }

    @Override
    public PendingInteraction wakeFromBed(PlayerContext context, InteractionConfirmation confirmation, int timeoutTicks) {
        // 原版"离开床"按钮的做法：给服务器发 STOP_SLEEPING，人被传回床边站着；这里照发同一个包并等不在睡了。
        // 本刻不收时交回 null，由调用方下一刻再起身。
        if (!acceptSubmission(context)) return null;
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.WAKE_FROM_BED, context, confirmation, timeoutTicks);
        try {
            connection(context).send(new ServerboundPlayerCommandPacket(
                    context.localPlayer(), ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native wake-from-bed threw before the outcome was confirmed");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction selectHotbar(PlayerContext context, int slot, int timeoutTicks) {
        // 本地切换选中槽并发包通知服务器；随后比较的 selected 字段就是这个本地值。
        // 选中上限问原版自己：服务端 handleSetCarriedItem 也按 Inventory.getSelectionSize() 校验；
        // 扩展快捷栏模组（如 HotBaaaar）会把它抬到 9 以上，未装时恒为 9，与原校验一致。
        int selectionLimit = Inventory.getSelectionSize();
        if (slot < 0 || slot >= selectionLimit) {
            throw new IllegalArgumentException(
                    "hotbar slot must be between 0 and " + (selectionLimit - 1));
        }
        // 本刻不收时交回 null，槽位保持原样，由调用方下一刻再选。
        if (!acceptSubmission(context)) return null;
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.SELECT_HOTBAR,
                context,
                InteractionConfirmation.hotbarSelected(slot),
                timeoutTicks);
        try {
            context.localPlayer().getInventory().selected = slot;
            connection(context).send(new ServerboundSetCarriedItemPacket(slot));
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native hotbar selection threw before the outcome was confirmed");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction creativeSetSlot(
            PlayerContext context, int inventorySlot, ItemStack expected, int timeoutTicks) {
        // 只有创造模式且物品栏界面已显示才允许设置槽位；发包后等物品栏实际出现期望物品，不直接在这里改本地数量。
        if (inventorySlot < 0 || inventorySlot >= Math.min(
                36, context.localPlayer().getInventory().getContainerSize())) {
            throw new IllegalArgumentException("creative inventory slot must be between 0 and 35");
        }
        if (!context.localPlayer().getAbilities().instabuild) {
            throw new IllegalStateException("creative slot mutation requires creative mode");
        }
        if (!menuActions.ensureVisible(context)) {
            throw new IllegalStateException("creative inventory changes require a rendered player inventory GUI");
        }
        // 本刻不收时交回 null，背包保持原样，由调用方下一刻再放。
        if (!acceptSubmission(context)) return null;
        ItemStack frozen = expected.copy();
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.CREATIVE_SET_SLOT,
                context,
                InteractionConfirmation.inventorySlot(inventorySlot, frozen),
                timeoutTicks);
        try {
            int protocolSlot = inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
            connection(context).send(
                    new ServerboundSetCreativeModeSlotPacket(protocolSlot, frozen.copy()));
            menuActions.interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "creative slot packet threw before synchronized inventory confirmation");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction submitProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks) {
        return submit(context, operation, submission, confirmation, timeoutTicks, true);
    }

    @Override
    public PendingInteraction submitControlProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks) {
        return submit(context, operation, submission, confirmation, timeoutTicks, false);
    }

    private PendingInteraction submit(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks,
            boolean usesMenu) {
        // 模组菜单协议必须有可见菜单，世界控制协议则要求合适的世界界面；两者仍共用同一交互机会和等待位置。
        if (submission == null) throw new IllegalArgumentException("submission is required");
        if (confirmation == null) throw new IllegalArgumentException("confirmation is required");
        String name = operation == null || operation.isBlank() ? "mod protocol action" : operation;
        if (usesMenu) {
            if (!menuActions.ensureVisible(context)) {
                throw new IllegalStateException("mod menu protocols require a rendered GUI");
            }
        } else if (!MenuVisibility.worldInputAllowed(Minecraft.getInstance().screen)) {
            throw new IllegalStateException("mod control protocols require a world interaction screen");
        }
        // 本刻不收时交回 null，协议下一刻再发，不算发送失败。
        if (!acceptSubmission(context)) return null;
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.MOD_PROTOCOL, context, confirmation, timeoutTicks);
        activeProtocolUsesMenu = usesMenu;
        try {
            submission.run();
            if (usesMenu) menuActions.interactionSubmitted(context);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    name + " threw after protocol submission began; application is unknown");
        }
        return poll(context, pending);
    }

    public boolean deferMainHandUseCancellation(Object owner, PlayerContext context, PendingInteraction pending) {
        // 只能接收当前任务确实持有的主手动作；租借继续绑定原角色、确认、槽位、物品和递减的持用计时。
        requireCurrent(context);
        if (!ownsItemUse(pending)
                || context.localPlayer().getUsedItemHand() != InteractionHand.MAIN_HAND) return false;
        mainHandStop.owe(context, pending);
        return true;
    }

    @Override
    public PendingInteraction cancelMainHandUse(PlayerContext context, PendingInteraction pending) {
        // 本刻没有交互机会时交回 null：取消下一刻再发，持用中的物品由原版按松键收场。
        if (!claimSubmission(context)) return null;
        requireActive(pending, PendingInteraction.Kind.USE_ITEM);
        if (context.localPlayer().isUsingItem()
                && context.localPlayer().getUsedItemHand() != InteractionHand.MAIN_HAND)
            throw new IllegalStateException("main-hand cancellation cannot stop an offhand action");
        // 切到剑斧、空格这类，别切到挨着弓的那格箭上（停手后一直拿着箭走）。
        int replacement = UseCancelSlot.pick(context.localPlayer().getInventory());
        // 切槽和本地停止是同步动作；这里确认的是已提交的取消操作，不是箭命中或服务器确认。
        InteractionConfirmation stopped = new InteractionConfirmation() {
            public Verdict observe(PlayerContext c) {
                return !c.localPlayer().isUsingItem() && c.localPlayer().getInventory().selected == replacement
                        ? Verdict.APPLIED : Verdict.PENDING;
            }
            public int stableTicksRequired() { return 1; }
        };
        var cancelled = oneShot(PendingInteraction.Kind.RELEASE_ITEM, context, stopped, 10);
        try {
            // 原版服务端 handleSetCarriedItem 在切换主手时 stopUsingItem，不会释放弓箭。
            connection(context).send(new ServerboundSetCarriedItemPacket(replacement));
            context.localPlayer().getInventory().selected = replacement;
            context.localPlayer().stopUsingItem();
        } catch (RuntimeException failure) {
            cancelled.finish(PendingInteraction.Status.UNCERTAIN, "main-hand cancellation was not confirmed");
        }
        return poll(context, cancelled);
    }

    @Override
    public PendingInteraction attack(PlayerContext context, Entity target,
                                     InteractionConfirmation confirmation, int timeoutTicks) {
        requireCurrent(context);
        // 同一目标的挥击还在等确认时，重提交按幂等等待：不重复挥手、不抛异常，也不占本刻的交互机会，
        // 把仍在进行的等待原样交回，调用方逐刻读它自己的结算结果。
        if (sameTargetAttackAwaiting(active, context, target.getId())) {
            return active;
        }
        // 本刻不收（交互机会被占用、或上一动作如举盾还没结清）时交回 null，由出手动作下一刻再挥，
        // 不再把这一刻没轮到当成出错——撤离途中被追上还手时，整场自卫不能因此掀翻。
        if (!acceptSubmission(context)) return null;
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.ATTACK_ENTITY, context, confirmation, timeoutTicks);
        pending.attachAttackTarget(target.getId());
        try {
            gameMode(context).attack(context.localPlayer(), target);
            context.localPlayer().swing(InteractionHand.MAIN_HAND);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native attack threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction interact(PlayerContext context, Entity target, InteractionHand hand,
                                       InteractionConfirmation confirmation, int timeoutTicks) {
        // 本刻不收时交回 null，由调用方下一刻再点，不算交互失败。
        if (!acceptSubmission(context)) return null;
        PendingInteraction pending = oneShot(
                PendingInteraction.Kind.INTERACT_ENTITY, context, confirmation, timeoutTicks);
        try {
            var result = gameMode(context).interact(context.localPlayer(), target, hand);
            // 原版自己的结算是这一下右键唯一在出手当时就有的权威事实：骑船这类不开界面、不改世界的用法，
            // 确认窗只能等乘客关系同步，等不到时全靠它分清是原版规则当场拒了（船泡在水下、刚下过车），
            // 还是点击被接受了、效果丢在了客户端到服务端之间。
            pending.noteNativeAnswer(result.consumesAction());
            // 原版当场拒收时不解释是哪条拒的：把提交瞬间的按键、距离与乘客现场记进等待记录，
            // 确认窗过期时随收场报文带出，下一轮实机直接读出拒收原因。
            if (!result.consumesAction()) {
                pending.noteEntityUseScene(EntityUseScene.of(context.localPlayer(), target));
            }
            if (result.shouldSwing()) context.localPlayer().swing(hand);
        } catch (RuntimeException failure) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "native entity interaction threw after entering the client action path");
        }
        return poll(context, pending);
    }

    @Override
    public PendingInteraction retireOneShotForTaskBoundary(
            PlayerContext context,
            PendingInteraction pending,
            String boundaryReason) {
        // 一次性操作已提交后不能假装撤销；任务结束而结果未定时标记不确定。挖掘和持续使用必须另发停止操作。
        if (pending == null) throw new IllegalArgumentException("pending is required");
        requireActive(pending, pending.kind());
        if (pending.kind() == PendingInteraction.Kind.BREAK_BLOCK
                || pending.kind() == PendingInteraction.Kind.USE_ITEM) {
            throw new IllegalArgumentException(
                    "continuous native actions require their dedicated physical stop operation");
        }
        poll(context, pending);
        if (!pending.terminal()) {
            String reason = boundaryReason == null || boundaryReason.isBlank()
                    ? "the owning task ended before native confirmation"
                    : boundaryReason;
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    reason + "; the submitted one-shot effect may already have applied");
        }
        return pending;
    }

    @Override
    public PendingInteraction poll(PlayerContext context, PendingInteraction pending) {
        // 先核对仍是同一角色、上下文仍是本刻，再运行只读判断；当前默认累计两刻匹配，不包含服务器预测序号检查。
        // 已终结的确认是冻结的历史：端口可能已经先一步替它收尾（每刻的 advance），或者有后来者
        // 合法接管了新动作。迟到的持有者仍要读回自己的最终结果，不能因为端口换了动作就判成内部错误。
        if (pending.terminal()) return pending;
        requireActive(pending, pending.kind());
        if (!pending.fromSamePlayer(context)) {
            pending.finish(PendingInteraction.Status.UNCERTAIN,
                    "body or control authority changed before confirmation");
            return pending;
        }
        InteractionConfirmation.Verdict verdict;
        try {
            verdict = pending.confirmation().observe(context);
        } catch (RuntimeException observationFailure) {
            verdict = InteractionConfirmation.Verdict.PENDING;
        }
        switch (verdict) {
            case APPLIED -> {
                if (pending.countStable(context.clientTick())) pending.finish(
                        PendingInteraction.Status.CONFIRMED_APPLIED,
                        "authoritative client facts confirmed the native action");
            }
            case NOT_APPLIED -> pending.finish(
                    PendingInteraction.Status.CONFIRMED_NOT_APPLIED,
                    "authoritative client facts confirmed that the action was not applied");
            case DIVERGED -> pending.finish(
                    PendingInteraction.Status.DIVERGED,
                    "live facts diverged from both the frozen before and expected after state");
            case PENDING -> pending.resetStable(context.clientTick());
        }
        if (!pending.terminal() && context.clientTick() >= pending.deadlineTick()) {
            // 确认窗到期：放置类的证据（手上少一个、方块变样、实体出现）一样都没出现，
            // 就按「提交了但没生效」如实收场，报文与骑乘确认的「原版拒了 / 接受而效果丢失」同一条线；
            // 拿不准的手势照旧按没能确认交上去，不冒充知道。射线命中了什么由提交现场的插桩事实随结果带出。
            PendingInteraction.Status expiry = expiryStatus(pending.confirmation());
            pending.finish(expiry, expiry == PendingInteraction.Status.CONFIRMED_NOT_APPLIED
                    ? "the confirmation window expired with no net change in hand item, target block,"
                            + " menu or nearby entities; the submitted action did not take effect"
                    : "the bounded read-only confirmation window expired"
                            + ExpiryContext.of(pending.nativeAnswer(), pending.entityUseScene()));
        }
        if (pending.terminal() && (pending.kind() == PendingInteraction.Kind.CREATIVE_SET_SLOT
                || (pending.kind() == PendingInteraction.Kind.MOD_PROTOCOL && activeProtocolUsesMenu))) {
            menuActions.interactionSubmitted(context);
        }
        return pending;
    }

    /**
     * 刻外任务收尾（MCP 取消等在两次游戏刻之间执行，没有当刻上下文）：发不了停止包，
     * 只登记边界原因，下一刻 advance 先停挖再结算。没有这一步，等待中的挖掘会一直占着
     * 动作队列直到自身超期——挖掘窗口长达数千刻，期间所有原生提交都被拒绝。
     */
    @Override public void deferBreakCancellationForTaskBoundary(PendingInteraction pending, String boundaryReason) {
        if (pending == null || pending.terminal() || pending != active
                || pending.kind() != PendingInteraction.Kind.BREAK_BLOCK) return;
        mining.oweCancellation(boundaryReason);
    }

    /**
     * 刻外任务收尾的一次性动作版：不需要世界操作就能如实终结，标记效果未知
     * （提交可能已经生效），不留等待占位。持续动作（挖掘、持用）须走各自的物理停手。
     */
    @Override public void abandonOneShotForTaskBoundary(PendingInteraction pending, String boundaryReason) {
        if (pending == null || pending.terminal()) return;
        if (pending != active && pending != auxiliary) return;
        if (pending.kind() == PendingInteraction.Kind.BREAK_BLOCK
                || pending.kind() == PendingInteraction.Kind.USE_ITEM) {
            throw new IllegalArgumentException(
                    "continuous native actions require their dedicated physical stop operation");
        }
        String reason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before native confirmation" : boundaryReason;
        pending.finish(PendingInteraction.Status.UNCERTAIN,
                reason + "; the submitted one-shot effect may already have applied");
    }

    /** 换角色或交还控制权时结束旧等待：旧结果不再可信，这里只改等待记录，不再次发世界操作。 */
    @Override public void revokeForBoundary(String reason) {
        mainHandStop.drop(); // 交还角色后，旧取消不得切换新操作者的槽位。
        if (active != null && !active.terminal()) {
            active.finish(PendingInteraction.Status.UNCERTAIN, reason);
        }
        // 换世界、死亡或 F8 交还控制时，副手的旧等待也必须结束，不能带到新操作者身上。
        if (auxiliary != null && !auxiliary.terminal()) auxiliary.finish(PendingInteraction.Status.UNCERTAIN, reason);
        mining.clearCancellation();
    }

    // 物品使用的任务边界收场：期限或确认先给了结论的照读不翻案；已起手的持续使用把停手欠给边界，
    // 下一刻先停稳再放新任务进来；还没起手的右键没人再读它的确认了，提交可能已生效，如实按不确定收场。
    @Override
    public PendingInteraction settleItemUseForTaskBoundary(
            PlayerContext context, PendingInteraction pending, String boundaryReason) {
        if (pending.terminal()) return pending;
        requireActive(pending, PendingInteraction.Kind.USE_ITEM);
        poll(context, pending);
        if (pending.terminal()) return pending;
        var player = context.localPlayer();
        if (player != null && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            mainHandStop.owe(context, pending);
            return pending;
        }
        String reason = boundaryReason == null || boundaryReason.isBlank()
                ? "the owning task ended before the item-use confirmation" : boundaryReason;
        pending.finish(PendingInteraction.Status.UNCERTAIN,
                reason + "; the submitted item use may still have applied");
        return pending;
    }

    private PendingInteraction oneShot(PendingInteraction.Kind kind, PlayerContext context,
                                       InteractionConfirmation confirmation, int timeoutTicks) {
        PendingInteraction pending = new PendingInteraction(
                kind, context, timeoutTicks, confirmation.stableTicksRequired(), confirmation, null, null);
        install(pending);
        return pending;
    }

    void install(PendingInteraction pending) {
        mainHandStop.drop(); // 新原生动作接管时丢弃旧欠账，不替后来者松手或换物。
        active = pending;
        // 新的一次点击不继承上一次举盾的持用状态，避免无线终端被当作已经松开的盾牌。
        activeItemUseWasHeld = false;
        activeProtocolUsesMenu = false;
        mining.clearCancellation();
    }

    // 上下文必须属于本刻：旧刻的上下文可能指着已经换掉的玩家或连接。
    static void requireCurrent(PlayerContext context) {
        if (context == null || !context.isCurrent()) throw new IllegalArgumentException("the player context must belong to the current tick");
    }

    // 发包前：角色仍归自动化控制（F8 交还后不能再发），并占用本刻唯一的交互机会。
    // 本刻机会被占用时不抛异常，交回假由提交方法返回显式的「本刻不收」；上下文过期仍是调用方的协议错误。
    static boolean claimSubmission(PlayerContext context) {
        requireCurrent(context);
        return context.tryClaimInteraction();
    }

    /**
     * 同一目标、同一角色且未过期的攻击等待才算"还在等同一次挥击"，重提交幂等等待；
     * 换目标、过了期限或换了角色都不算，仍走正常的回收与提交检查。
     * 纯判断，离线可测。
     */
    static boolean sameTargetAttackAwaiting(PendingInteraction active, PlayerContext context, int targetId) {
        return active != null && !active.terminal()
                && active.kind() == PendingInteraction.Kind.ATTACK_ENTITY
                && active.attackTargetId() == targetId
                && active.fromSamePlayer(context)
                && context.clientTick() < active.deadlineTick();
    }

    /** 确认窗到期的结算：放置类的证据一样都没出现就按没生效收场；拿不准的手势按没能确认交上去。 */
    static PendingInteraction.Status expiryStatus(InteractionConfirmation confirmation) {
        return confirmation.expiryMeansNotApplied()
                ? PendingInteraction.Status.CONFIRMED_NOT_APPLIED
                : PendingInteraction.Status.UNCERTAIN;
    }

    /**
     * 有没有空位收一次新提交：上一动作还在等确认时交回假，由提交方法返回显式的「本刻不收」。
     * 接管、换角色或长期无驱动留下的僵尸确认（已过期或不再属于当前角色）在这里就地结算为不确定并放行，
     * 不能让一次悬挂升级成会话级瘫痪。
     */
    private boolean idleForNewSubmission(PlayerContext context) {
        if (active == null || active.terminal()) return true;
        if (context.clientTick() >= active.deadlineTick() || !active.fromSamePlayer(context)) {
            PendingInteraction stale = active;
            LOG.warn("[maicraft-interaction] reclaiming stale native action {} (kind={}, status={}, submitted_tick={}, deadline_tick={})",
                    stale.id(), stale.kind(), stale.status(), stale.submittedTick(), stale.deadlineTick());
            stale.finish(PendingInteraction.Status.UNCERTAIN,
                    "the pending native action outlived its deadline or its player; reclaimed before a new submission");
            return true;
        }
        // 前一项操作还没结束时这一刻不收新提交，防止多次点击共用一份结果后分不清谁做了什么；
        // 它到期限或换角色时会结清，同一笔提交下一刻重试即可。
        return false;
    }

    /**
     * 提交前的一道闸：确认上一动作已结清，再占用本刻交互机会。
     * 先查空位再占机会，过不了闸（上一动作还在等确认、或本刻机会被占用）不烧掉这一刻；
     * 提交方法随即返回 null 表示「本刻不收」。
     */
    boolean acceptSubmission(PlayerContext context) {
        return idleForNewSubmission(context) && claimSubmission(context);
    }

    void requireActive(PendingInteraction pending, PendingInteraction.Kind kind) {
        if (pending == null || pending != active && pending != auxiliary || pending.kind() != kind) {
            throw new IllegalArgumentException("the pending interaction is not the active native action");
        }
    }

    /** 每刻推进一次：先结算副手与挂起的取消，再处理停挖请求和持用收尾。 */
    @Override
    public void advance(PlayerContext context) {
        // 副手仅做只读确认；即使主任务已经开始下一块矿，也不能重新提交旧火把。
        if (auxiliary != null && !auxiliary.terminal()) poll(context, auxiliary);
        // 主手停用的欠账还没结清时先结清它（起手确认可能已经成功）；后来者不继承旧取消。
        if (mainHandStop.settle(context, pending -> cancelMainHandUse(context, pending))) return;
        // 每刻先读旧结果；如果有任务已经结束却还没来得及松开挖掘，就先处理这次停止。
        PendingInteraction pending = active;
        if (pending == null || pending.terminal()) {
            mining.clearCancellation();
            abandonedItemUse.forget();
            return;
        }
        // 持用可能在服务器确认后才开始；持续记录真实状态，瞬时开菜单则一直等待菜单或截止时间。
        if (pending.kind() == PendingInteraction.Kind.USE_ITEM && context.localPlayer().isUsingItem())
            activeItemUseWasHeld = true;
        // 欠着的停挖先结清它；没有欠账就按普通推进读结果、收尾弃用的持用。
        if (mining.settleOwedCancellation(context, pending)) return;
        poll(context, pending);
        abandonedItemUse.retire(context, pending, activeItemUseWasHeld, pending == active);
    }

    static MultiPlayerGameMode gameMode(PlayerContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null) throw new IllegalStateException("no active client game mode");
        return minecraft.gameMode;
    }

    private static ClientPacketListener connection(PlayerContext context) {
        ClientPacketListener connection = context.localPlayer().connection;
        if (connection == null) throw new IllegalStateException("no active server connection");
        return connection;
    }
}
