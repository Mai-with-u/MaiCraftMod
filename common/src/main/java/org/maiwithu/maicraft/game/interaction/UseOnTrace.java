// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 一次右键方块提交现场的逐项事实：提交的点击面与位置、客户端原生预测结果、
 * 点击后客户端观察到的落格状态，以及方块操作编号是否前进（前进＝预测包已发出，
 * 服务端收到了这次点击；不前进＝客户端本地就拒绝了，根本没有发包）。
 * 只读观察与拼句，不改变任何世界状态；随确认记录走，排查"点击被谁拒了"用。
 * 写成一句中文：方块和物品用注册 ID，手、面、原版结算用玩家的说法，不报枚举原名。
 */
final class UseOnTrace {

    private UseOnTrace() {}

    static String describe(PlayerContext context, InteractionHand hand, BlockHitResult hit,
                           InteractionResult result, int sequenceBefore) {
        try {
            var level = context.level();
            var player = context.localPlayer();
            var clicked = hit.getBlockPos();
            var placementCell = clicked.relative(hit.getDirection());
            int sequenceAfter = level instanceof BlockUseAcknowledgement acknowledgement
                    ? acknowledgement.maicraft$currentBlockSequence() : Integer.MIN_VALUE;
            BiFunction<BlockPos, BlockState, String> describe =
                    (pos, state) -> pos.toShortString() + "（" + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "）";
            // 点的是哪格哪一面、手上拿着什么、离多远、按没按潜行、原版当场怎么结算、要落进的那格现在是什么、包发没发出。
            return "点击现场：" + handName(hand) + "拿着 "
                    + BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem())
                    + "×" + player.getItemInHand(hand).getCount()
                    + "，点的是 " + describe.apply(clicked, level.getBlockState(clicked))
                    + "的" + faceName(hit.getDirection()) + "面"
                    + "，点击点 (" + Math.round(hit.getLocation().x * 100) / 100.0
                    + ", " + Math.round(hit.getLocation().y * 100) / 100.0
                    + ", " + Math.round(hit.getLocation().z * 100) / 100.0 + ")"
                    + "，眼睛离点击点 " + Math.round(player.getEyePosition().distanceTo(hit.getLocation()) * 100) / 100.0
                    + " 格，脚在 " + player.blockPosition().toShortString()
                    + "，" + (player.isShiftKeyDown() ? "按着潜行" : "没按潜行")
                    + "，原版当场" + nativeVerdict(result)
                    + "，要落进的那格 " + describe.apply(placementCell, level.getBlockState(placementCell))
                    + "，" + (sequenceBefore == Integer.MIN_VALUE ? "不知道客户端发没发出预测包"
                            : sequenceAfter > sequenceBefore ? "客户端发出了预测包（服务端收到了这一下）"
                            : "客户端没发预测包（本地就拒了，服务端没收到这一下）");
        } catch (RuntimeException traceFailure) {
            // 记现场本身出错不影响这次点击的结论，只说没记下来，不把异常类名塞进给人看的句子。
            return "点击现场没能记下来";
        }
    }

    /** 哪只手，按玩家的说法。 */
    static String handName(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? "副手" : "主手";
    }

    /** 点的是哪一面，按玩家的说法：顶、底、东南西北。 */
    static String faceName(Direction face) {
        return switch (face) {
            case UP -> "顶";
            case DOWN -> "底";
            case NORTH -> "北";
            case SOUTH -> "南";
            case WEST -> "西";
            case EAST -> "东";
        };
    }

    /** 原版客户端对这一下右键的当场结算：接受了、拒绝了，还是这样点根本没有用法。 */
    static String nativeVerdict(InteractionResult result) {
        if (result.consumesAction()) return "接受了这一下";
        return result == InteractionResult.FAIL ? "拒绝了这一下" : "没接这一下（这样点没有用法）";
    }
}
