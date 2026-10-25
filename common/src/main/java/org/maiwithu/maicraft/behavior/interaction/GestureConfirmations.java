// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 按手势取确认条件：手上是什么、效果落在哪，决定这次右键怎样算生效。
 *
 * <p>确认条件本身由游戏接口层的工厂提供（手上物品变了、方块变过了、界面开了、火出现了），
 * 这里按 {@link ItemUseAim} 的结论把它们组合起来：一种证据可能被客户端预测掩盖，
 * 几种证据有一项成立就算生效，都还等着才继续等。
 */
public final class GestureConfirmations {

    private GestureConfirmations() {}

    /** 几项证据有一项成立就算生效；全部实现都还在等时才算没到。 */
    public static InteractionConfirmation anyOf(InteractionConfirmation... parts) {
        List<InteractionConfirmation> checks = List.of(parts);
        return context -> {
            boolean allDiverged = true;
            for (InteractionConfirmation check : checks) {
                InteractionConfirmation.Verdict verdict = check.observe(context);
                if (verdict == InteractionConfirmation.Verdict.APPLIED) return verdict;
                if (verdict != InteractionConfirmation.Verdict.DIVERGED) allDiverged = false;
            }
            return allDiverged ? InteractionConfirmation.Verdict.DIVERGED : InteractionConfirmation.Verdict.PENDING;
        };
    }

    /**
     * 按手势结论取确认条件。
     *
     * @param confirmation 手势的生效方式
     * @param aim          瞄准决定（点哪格、效果落在哪格）
     * @param hand         用的是哪只手
     * @param handBefore   出手前手上的东西
     * @param targetBefore 出手前目标格的状态；目标是实体时传 null
     * @param menuIdBefore 出手前的界面编号；用它认"点开了新界面"这条证据
     */
    public static InteractionConfirmation forGesture(ItemUseAim.Confirmation confirmation, BlockPos effectCell,
            InteractionHand hand, ItemStack handBefore, BlockState targetBefore, int menuIdBefore) {
        List<InteractionConfirmation> checks = new ArrayList<>();
        switch (confirmation) {
            case HAND_BECOMES_FULL_BUCKET, HAND_EMPTIES_OR_FLUID_APPEARS ->
                checks.add(InteractionConfirmation.heldItemChanged(hand, handBefore));
            // 放船这类：方块不变、界面不开，放出去手上就少一个——手上变了是唯一可等的证据。
            case OWN_USE_TAKES_EFFECT -> checks.add(InteractionConfirmation.heldItemChanged(hand, handBefore));
            case FIRE_APPEARS -> checks.add(InteractionConfirmation.ignitionWorldEffect(effectCell));
            case BLOCK_TURNS_INTO -> {
                if (targetBefore != null) checks.add(InteractionConfirmation.blockChanged(effectCell, targetBefore));
            }
            case TARGET_OR_HAND_CHANGES -> {
                if (targetBefore != null) checks.add(InteractionConfirmation.blockChanged(effectCell, targetBefore));
                checks.add(InteractionConfirmation.menuChanged(menuIdBefore));
                checks.add(InteractionConfirmation.heldItemChanged(hand, handBefore));
            }
        }
        // 放置类（放船、放床这类放出去手上少一个或目标格变样的手势）：确认窗过期而证据一样都没出现，
        // 就能断定没生效；船漂在深水上放不出去、床被两格碰撞挡住这类静默失败由此如实浮出来。
        boolean placement = confirmation == ItemUseAim.Confirmation.OWN_USE_TAKES_EFFECT
                || confirmation == ItemUseAim.Confirmation.TARGET_OR_HAND_CHANGES;
        InteractionConfirmation combined = checks.size() == 1
                ? checks.getFirst() : anyOf(checks.toArray(new InteractionConfirmation[0]));
        return placement ? expiresAsNotApplied(combined) : combined;
    }

    /** 给组合好的确认条件挂上「过期即没生效」的断定；其余核对原样转达。 */
    private static InteractionConfirmation expiresAsNotApplied(InteractionConfirmation check) {
        return new InteractionConfirmation() {
            @Override public InteractionConfirmation.Verdict observe(PlayerContext context) { return check.observe(context); }
            @Override public int stableTicksRequired() { return check.stableTicksRequired(); }
            @Override public boolean requiresBlockAcknowledgement() { return check.requiresBlockAcknowledgement(); }
            @Override public InteractionConfirmation.Verdict observeAcknowledged(PlayerContext context) { return check.observeAcknowledged(context); }
            @Override public boolean expiryMeansNotApplied() { return true; }
        };
    }
}
