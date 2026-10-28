// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 攻击充能：近战出手的够格线、攻击条充满要几刻、离下一刀还差几刻，全仓只有这一份。原生攻击按它等，
 * 战斗引擎按它算冷却中什么时候往回走、什么时候起跳。
 *
 * <p>原版每把武器有攻击冷却：挥完一下攻击条从零往上涨，涨满要 20 ÷ 攻速 刻（剑 12.5 刻、铁斧 22 刻、
 * 重锤 33 刻、空手 5 刻），攻速里武器、急迫、挖掘疲劳都算着，读原版自己的数。充能到 0.95 就算够格：
 * 计数器是整刻的，等满 1.0 要多花一整刻，0.95 已经高过原版判暴击、疾跑击退、横扫的 0.9。
 *
 * <p>主手换成另一种东西，原版下一刻就把攻击冷却清零（换成另一把同样的剑不清）；服务端也要到这位玩家下一次
 * 实体刻才换上新武器的攻击属性。所以自动化同一刻里刚换了手，这一刻读到的满条是旧东西的，挥出去那一刀也按旧东西算：
 * 主手这一刻才拿上的不算够格，隔过一刻，客户端自己的充能也清零了，再按新武器从零充。
 */
public final class AttackCharge {

    /** 充能到这么多算够格。 */
    public static final float READY = 0.95f;
    /** 浮点误差的余量：正好差整数刻时不多算一刻。 */
    private static final double SLACK = 1.0e-4;

    private AttackCharge() {}

    /**
     * 攻击条离够格还差几刻：已够格为 0。
     *
     * @param charge    此刻的充能（0 到 1）
     * @param fullTicks 攻击条充满要几刻（20 ÷ 攻速）
     */
    public static int ticksToReady(float charge, double fullTicks) {
        if (charge >= READY) return 0;
        return Math.max(1, (int) Math.ceil((READY - charge) * Math.max(0.0, fullTicks) - SLACK));
    }

    /** 主手拿稳了：此刻这种东西是上一刻或更早拿上的，不是这一刻才换上的。 */
    public static boolean handSettled(long heldSince, long now) {
        return heldSince < now;
    }

    /**
     * 离下一刀还差几刻：攻击条与主手拿没拿稳一起看；主手这一刻才换上的至少还差一刻。
     *
     * @param heldSince 主手此刻这种东西是哪一刻拿上的
     * @param now       此刻的客户端刻
     */
    public static int ticksToReady(float charge, double fullTicks, long heldSince, long now) {
        int ticks = ticksToReady(charge, fullTicks);
        return handSettled(heldSince, now) ? ticks : Math.max(1, ticks);
    }

    /** 读角色此刻离下一刀还差几刻；没有角色时按够格算（不替不存在的身体等）。 */
    public static int ticksToReady(PlayerContext context) {
        LocalPlayer self = context == null ? null : context.localPlayer();
        if (self == null) return 0;
        return ticksToReady(self.getAttackStrengthScale(0.0F), self.getCurrentItemAttackStrengthDelay(),
                context.mainHandHeldSince(), context.clientTick());
    }

    /** 角色此刻够格出手（攻击条到 0.95、主手不是这一刻才换上的）。 */
    public static boolean ready(PlayerContext context) {
        return ticksToReady(context) == 0;
    }
}
