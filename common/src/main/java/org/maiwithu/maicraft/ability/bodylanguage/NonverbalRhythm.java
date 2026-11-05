// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

/**
 * 蹲起、点头、摇头、挥手、跳的节奏：第几刻该按着潜行、视线该偏多少、两下挥手隔多久，和一次要花多少刻。纯函数。
 *
 * <p>蹲起按下、松开各 3 刻为一下（服务器每刻收一次潜行变化，3 刻别人看得清）；点头往下 25 度 4 刻、抬回 4 刻；
 * 摇头往左 25 度 4 刻、往右 4 刻，最后回正 4 刻；挥手两下隔 10 刻（原版点空后半秒才能再点）；跳一下最多等 20 刻落地。
 */
final class NonverbalRhythm {

    /** 蹲起按下、松开各几刻。 */
    static final int CROUCH_HALF_TICKS = 3;
    /** 点头、摇头一下里每个方向停几刻。 */
    static final int TURN_HALF_TICKS = 4;
    /** 点头、摇头偏多少度。 */
    static final float TURN_DEGREES = 25;
    /** 两下挥手隔几刻。 */
    static final int SWING_GAP_TICKS = 10;
    /** 跳一下最多等几刻：起跳到落地。 */
    static final int JUMP_TICKS = 20;
    /** 拿出、收回、转过去这些零碎留几刻。 */
    static final int SETTLE_TICKS = 10;
    /** 一次最长几刻：15 秒。 */
    static final int MAX_TICKS = 300;

    private NonverbalRhythm() {}

    /** 蹲起的第 tick 刻该不该按着潜行。 */
    static boolean crouching(int tick) {
        return tick % (2 * CROUCH_HALF_TICKS) < CROUCH_HALF_TICKS;
    }

    /** 点头的第 tick 刻视线比看着他的脸往下偏多少度（俯仰正数是低头）。 */
    static float nodPitch(int tick) {
        return tick % (2 * TURN_HALF_TICKS) < TURN_HALF_TICKS ? TURN_DEGREES : 0;
    }

    /** 摇头的第 tick 刻视线比看着他的脸往左右偏多少度：先左后右，做满 count 下以后回正。 */
    static float shakeYaw(int tick, int count) {
        if (tick >= count * 2 * TURN_HALF_TICKS) return 0;
        return tick % (2 * TURN_HALF_TICKS) < TURN_HALF_TICKS ? -TURN_DEGREES : TURN_DEGREES;
    }

    /** 做完 count 下一共要几刻（看着是 lookTicks 刻）：一次超过 15 秒就拒。 */
    static int plannedTicks(NonverbalOperation op, int count, int lookTicks) {
        return switch (op) {
            case LOOK -> lookTicks;
            case CROUCH -> count * 2 * CROUCH_HALF_TICKS;
            case NOD -> count * 2 * TURN_HALF_TICKS;
            case SHAKE_HEAD -> count * 2 * TURN_HALF_TICKS + TURN_HALF_TICKS;
            case SWING -> count * SWING_GAP_TICKS + SETTLE_TICKS;
            case JUMP -> count * JUMP_TICKS;
            case HOLD, PICK_UP, POKE -> SETTLE_TICKS;
            case TOSS -> SETTLE_TICKS + count;
        };
    }
}
