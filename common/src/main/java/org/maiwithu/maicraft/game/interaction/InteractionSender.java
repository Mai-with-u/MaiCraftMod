// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.player.BoundaryLifecycle;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 提交真实玩家操作并逐刻确认：提交一次动作与确认它做成了是两个阶段。
 * 同一时刻只允许一项动作在等确认；确认前不能提交下一次。
 * 边界时刻（换身体、控制权换手、停机、每刻开头）的收尾走 {@link BoundaryLifecycle}。
 *
 * <p>提交方法共用一条「本刻不收」的显式结论：本刻的交互机会已被占用、或上一动作还在等确认时，
 * 提交方法返回 {@code null}，调用方把它当作这一刻没轮到，下一刻原样重试，不算提交失败、
 * 也不抛异常；只有协议用错（上下文过期、等待记录对不上号）才照旧抛出。
 * 持续挖掘的推进与取消不返回 null：这一刻没轮到时等待记录原样交回，下一刻接着推进。
 */
public interface InteractionSender extends BoundaryLifecycle {

    /**
     * 副手放置只借用本刻空闲的交互机会；确认独立等待，不占住主任务下一刻的挖掘或交互。
     * 本刻主任务已经出过手、或有旧动作没收尾时返回 {@code null}，由调用方改刻再试。
     */
    default PendingInteraction tryAuxiliaryBlockUse(
            PlayerContext context, BlockHitResult hit,
            InteractionConfirmation confirmation, int timeoutTicks) {
        return null;
    }

    PendingInteraction startBreaking(PlayerContext context, BlockHitResult hit, int timeoutTicks);

    PendingInteraction cancelBreaking(PlayerContext context, PendingInteraction pending);

    /**
     * 任务即将结束时登记停手请求；游戏接口层最迟在下一游戏刻实际停止挖掘，
     * 再允许后继任务操作角色，避免本刻已经用完交互机会时留下持续破坏动作。
     */
    PendingInteraction cancelBreakingForTaskBoundary(
            PlayerContext context,
            PendingInteraction pending,
            String boundaryReason);

    PendingInteraction continueBreaking(PlayerContext context, PendingInteraction pending);

    PendingInteraction useBlock(
            PlayerContext context,
            InteractionHand hand,
            BlockHitResult hit,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction useItem(
            PlayerContext context,
            InteractionHand hand,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction releaseUsingItem(PlayerContext context, PendingInteraction pending);

    /** 通过切换主手槽取消蓄力，不发送会让弓发射的 RELEASE_USE_ITEM。 */
    PendingInteraction cancelMainHandUse(PlayerContext context, PendingInteraction pending);

    PendingInteraction selectHotbar(PlayerContext context, int slot, int timeoutTicks);

    /** 原生 Q 投掷只接受出手前完整主手快照；落点与实际扣减由调用方提供的只读确认一起核验。 */
    default PendingInteraction dropSelected(
            PlayerContext context, ItemStack expectedSelected, boolean fullStack,
            InteractionConfirmation confirmation, int timeoutTicks) {
        throw new UnsupportedOperationException("native selected-stack dropping is unavailable");
    }

    PendingInteraction creativeSetSlot(
            PlayerContext context,
            int inventorySlot, ItemStack expected, int timeoutTicks);

    /** 经模组原生协议提交的菜单动作；菜单协议必须先有可见界面，并占用同一份逐刻确认等待。 */
    PendingInteraction submitProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    /** 不开菜单的模组世界控制，同样受角色控制权和一次交互机会约束。 */
    PendingInteraction submitControlProtocol(
            PlayerContext context,
            String operation,
            Runnable submission,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction attack(
            PlayerContext context,
            Entity target,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    PendingInteraction interact(
            PlayerContext context,
            Entity target,
            InteractionHand hand,
            InteractionConfirmation confirmation,
            int timeoutTicks);

    /**
     * 从床上起来：给服务器发"离开床"，起身是几刻的游戏结算（服务器把人传回床边）。
     * 确认条件核对"已经不在睡"；服务器本来就要叫醒所有睡着的人时（跳夜、床被拆），
     * 人已经不在睡，确认即刻成立。不接这个入口的读写端按不支持原样抛出。
     */
    default PendingInteraction wakeFromBed(
            PlayerContext context,
            InteractionConfirmation confirmation,
            int timeoutTicks) {
        throw new UnsupportedOperationException("native waking from bed is unavailable");
    }

    /** 这次持续使用是否仍归提交方管理；只有它自己认下的使用才允许继续按住或松开。 */
    default boolean ownsItemUse(PendingInteraction pending) { return false; }

    /** 有经菜单模组协议提交的动作还没确认时为真；界面收尾要等它结清。 */
    default boolean hasPendingMenuTransaction() { return false; }

    /**
     * 单次操作尚未确认而所属任务已结束时，释放该任务占用的交互等待。
     * 结果未定的提交仍记为不确定，不把释放等待当成撤销已经发生的效果。
     * 持续挖掘和持续使用物品须调用各自的停手接口，不能在这里装作结束。
     */
    PendingInteraction retireOneShotForTaskBoundary(
            PlayerContext context,
            PendingInteraction pending,
            String boundaryReason);

    /**
     * 两刻之间结束任务、拿不到当刻上下文时：发不了停挖，先登记停挖原因，下一刻在新任务操作角色之前停掉。
     */
    void deferBreakCancellationForTaskBoundary(PendingInteraction pending, String boundaryReason);

    /**
     * 两刻之间结束任务时交还还在等确认的一次性提交：按效果不确定收尾（提交可能已经生效），不留等待占位。
     * 持续挖掘与持续使用要走各自的停手。
     */
    void abandonOneShotForTaskBoundary(PendingInteraction pending, String boundaryReason);

    /**
     * 待确认的物品使用赶上任务边界（目标被替换、取消或任务结束）时的结算：等待记录写明的
     * 「到期限或玩家变更时结算」，玩家变更就是这条。已经终结的按冻结的结论原样交回；
     * 已起手的持续使用先把停手欠给边界（下一刻先停稳再放新任务进来）；
     * 还没起手的右键没人再读它的确认了，提交可能已经生效，如实按效果不确定收场并放掉等待占位，
     * 新任务的第一次出手不再被旧等待挡住。
     */
    PendingInteraction settleItemUseForTaskBoundary(
            PlayerContext context, PendingInteraction pending, String boundaryReason);

    /**
     * 任务边界结算一条还在等确认的提交，按动作种类走各自的边界收场：
     * 挖掘发停手（本刻没机会就欠到下一刻）、物品使用按「玩家变更时结算」收场、其余一次性提交按不确定交还。
     * 调用方不必分辨手上的提交是哪种：期限或确认先给了结论的，各收场自己都不重复记账。
     */
    default PendingInteraction settleForTaskBoundary(
            PlayerContext context, PendingInteraction pending, String boundaryReason) {
        return switch (pending.kind()) {
            case BREAK_BLOCK -> cancelBreakingForTaskBoundary(context, pending, boundaryReason);
            case USE_ITEM -> settleItemUseForTaskBoundary(context, pending, boundaryReason);
            default -> retireOneShotForTaskBoundary(context, pending, boundaryReason);
        };
    }

    /** 逐刻结算：先核对上下文仍是本刻、动作仍是本角色的，再运行只读确认条件。 */
    PendingInteraction poll(PlayerContext context, PendingInteraction pending);
}
