// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;

/**
 * 右键实体提交那一刻的手势现场：潜行按着没有、眼睛到目标碰撞箱多远、实体交互距离多大、目标身上有没有乘客、
 * 目标是船时它已经连续没在水下多少刻。原版拒收这类点击时不发解释，把提交现场命中的拒收条件记下来，
 * 确认窗过期时拼进收场报文，下一轮实机直接读出"原版当场拒了"是哪一条拒的，不用再猜。纯判断，离线可测。
 */
record EntityUseScene(boolean sneakHeld, double eyeDistance, double interactionRange,
        boolean targetOccupied, Float boatSubmergedTicks) {

    /** 原版放任船在水下泡着的期限（刻）：超过它原版客户端与服务端都当场拒收骑乘，只报一次 PASS。 */
    private static final float BOAT_SUBMERGED_REFUSAL_TICKS = 60.0F;

    /** 提交现场取一份：按键读角色的输入状态，距离按眼睛到目标碰撞箱的最近距离，泡水时长只有船才有。 */
    static EntityUseScene of(LocalPlayer player, Entity target) {
        double eyeDistance = Math.sqrt(target.getBoundingBox().distanceToSqr(player.getEyePosition()));
        Float boatSubmergedTicks = target instanceof Boat boat ? boat.outOfControlTicks : null;
        return new EntityUseScene(player.isShiftKeyDown(), eyeDistance, player.entityInteractionRange(),
                !target.getPassengers().isEmpty(), boatSubmergedTicks);
    }

    /**
     * 命中的原版当场拒收条件整段话：潜行按住（载具右键被读成"想下来"）、点击点在实体交互距离之外、
     * 目标已有乘客、船连续没在水下超过原版期限（泡水 3 秒的空船永远骑不上去，客户端预测与服务端结算拒的是同一条）。
     * 客户端看得见的拒收条件一条都没命中时，说明拒收发生在服务端一侧，也如实说明。
     */
    String explain() {
        StringBuilder text = new StringBuilder();
        boolean anyMatched = false;
        if (sneakHeld) {
            text.append("the sneak key was still held (a sneak-click on a vehicle is read as wanting to dismount)");
            anyMatched = true;
        }
        if (eyeDistance > interactionRange) {
            if (anyMatched) text.append("; ");
            text.append(String.format(
                    "the click was %.1f blocks from the eyes, beyond the entity interaction range of %.1f",
                    eyeDistance, interactionRange));
            anyMatched = true;
        }
        if (targetOccupied) {
            if (anyMatched) text.append("; ");
            text.append("the target already carries a passenger");
            anyMatched = true;
        }
        if (boatSubmergedTicks != null && boatSubmergedTicks >= BOAT_SUBMERGED_REFUSAL_TICKS) {
            if (anyMatched) text.append("; ");
            text.append(String.format(
                    "the boat had been under the water surface for %.1f seconds"
                            + " (vanilla stops accepting boarding after 60 ticks (3 seconds) of it staying submerged)",
                    boatSubmergedTicks / 20.0));
            anyMatched = true;
        }
        if (!anyMatched) {
            return "; none of the client-visible refusal rules matched, so the refusal happened on the server's side";
        }
        return "; the on-sight rules it hit: " + text;
    }
}
