// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import org.maiwithu.maicraft.behavior.perception.looking.Aim;
import org.maiwithu.maicraft.behavior.perception.looking.TurnsHead;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 看着一个人逐下做肢体动作：看着、蹲起、点头、摇头、原地跳、挥手、拍一下。
 *
 * <p>每刻先对准他（他走动就跟着转），再按节奏做这一刻该做的：蹲起按下松开潜行，点头摇头在他脸上下左右偏，
 * 挥手先把视线从他脸上抬高到准星什么都碰不到再按攻击键，拍一下等准星落在他身上再按。没有对着的人就对着开始时的方向做。
 * 做成几下按游戏里真的发生了算（蹲下去了、跳起来又落地了、按出去了），做不成的几下写清原因，不硬凑。
 */
final class Gesturing implements Action {

    /** 拍一下等准星落在他身上最多几刻。 */
    static final int POKE_PATIENCE_TICKS = 40;
    /** 挥手时视线从他脸上起先抬高几度，准星还碰得到东西就再抬这么多。 */
    static final float SWING_RAISE_DEGREES = 30;
    static final float SWING_RAISE_STEP = 15;
    /** 视线和要的方向差多少度以内算转到了。 */
    static final float SETTLED_DEGREES = 3;

    private final NonverbalMoves moves;
    private final NonverbalOperation op;
    private final int count;
    private final int lookTicks;
    private final NonverbalMoves.Partner partner;
    private final NonverbalMoves.AimPoint point;
    private TurnsHead head;
    private TickContext last;
    private boolean holding;
    private boolean based;
    private float baseYaw;
    private float basePitch;
    private int tick;
    private int done;
    private String skipped;
    private boolean crouchSeen;
    private boolean airborne;
    private int jumpWait;
    private float raise = SWING_RAISE_DEGREES;
    private int sinceSwing = NonverbalRhythm.SWING_GAP_TICKS;

    /**
     * @param lookTicks 只用于看着：看多少刻
     * @param partner   对着谁；没有为 null，对着开始时的方向做
     * @param point     看他哪里
     */
    Gesturing(NonverbalMoves moves, NonverbalOperation op, int count, int lookTicks, NonverbalMoves.Partner partner,
              NonverbalMoves.AimPoint point) {
        this.moves = moves;
        this.op = op;
        this.count = count;
        this.lookTicks = lookTicks;
        this.partner = partner;
        this.point = point;
    }

    /** 做成了几下。 */
    int done() {
        return done;
    }

    /** 没做的几下和原因；都做了为 null。 */
    String skipped() {
        return skipped;
    }

    // 第一刻记下站定朝向（只为看人转头），之后每刻对准他，再做这一刻的那一下。他走了就收场。
    @Override public ActionStatus tick(TickContext context) {
        last = context;
        head = moves.head(context);
        if (head == null) return ActionStatus.running();
        if (!holding) {
            head.hold();
            holding = true;
        }
        if (!aim(context)) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, partner.name() + " 不见了", null));
        }
        ActionStatus status = switch (op) {
            case CROUCH -> crouch(context);
            case NOD -> rhythm(baseYaw, basePitch + NonverbalRhythm.nodPitch(tick),
                    count * 2 * NonverbalRhythm.TURN_HALF_TICKS);
            case SHAKE_HEAD -> rhythm(baseYaw + NonverbalRhythm.shakeYaw(tick, count), basePitch,
                    count * 2 * NonverbalRhythm.TURN_HALF_TICKS + NonverbalRhythm.TURN_HALF_TICKS);
            case JUMP -> jump(context);
            case SWING -> swing(context);
            case POKE -> poke(context);
            default -> look();
        };
        tick++;
        return status;
    }

    // 对准他：他的眼睛、身体中间或脚下；没有对着的人就记下开始时镜头朝哪，一直对着那里做。
    private boolean aim(TickContext context) {
        if (partner != null) {
            Aim aim = moves.aimAt(context, partner.entityId(), point);
            if (aim == null) return false;
            baseYaw = aim.yaw();
            basePitch = aim.pitch();
        } else if (!based) {
            baseYaw = head.yaw();
            basePitch = head.pitch();
        }
        based = true;
        return true;
    }

    private ActionStatus look() {
        head.turnToward(baseYaw, basePitch);
        if (tick + 1 < lookTicks) return ActionStatus.running();
        done = 1;
        return ActionStatus.done();
    }

    // 蹲起：按下 3 刻、松开 3 刻为一下；按下那几刻里服务器那边真蹲下去了才算一下。
    private ActionStatus crouch(TickContext context) {
        head.turnToward(baseYaw, basePitch);
        int cycle = 2 * NonverbalRhythm.CROUCH_HALF_TICKS;
        boolean on = NonverbalRhythm.crouching(tick);
        moves.press(context, on, false);
        if (on && moves.crouched(context)) crouchSeen = true;
        if ((tick + 1) % cycle == 0) {
            if (crouchSeen) done++;
            crouchSeen = false;
        }
        if (tick + 1 < count * cycle) return ActionStatus.progressed();
        moves.press(context, false, false);
        if (done < count) skipped = "按了潜行没蹲下去，少蹲 " + (count - done) + " 下";
        return ActionStatus.done();
    }

    // 点头、摇头：视线照节奏在他脸上下或左右偏，每 8 刻一下。
    private ActionStatus rhythm(float yaw, float pitch, int totalTicks) {
        head.turnToward(yaw, pitch);
        if ((tick + 1) % (2 * NonverbalRhythm.TURN_HALF_TICKS) == 0 && done < count) done++;
        return tick + 1 < totalTicks ? ActionStatus.progressed() : ActionStatus.done();
    }

    // 原地跳：踩在地上就按跳，离地再落地算一下；按了跳一直没离地（头顶贴着方块）就不跳了。
    private ActionStatus jump(TickContext context) {
        head.turnToward(baseYaw, basePitch);
        boolean grounded = moves.onGround(context);
        if (airborne && grounded) {
            done++;
            airborne = false;
            jumpWait = 0;
        } else if (!grounded) {
            airborne = true;
        }
        if (done >= count) return ActionStatus.done();
        if (++jumpWait > NonverbalRhythm.JUMP_TICKS) {
            skipped = "按了跳没跳起来（头顶贴着方块？），少跳 " + (count - done) + " 下";
            return ActionStatus.done();
        }
        moves.press(context, false, grounded && !airborne);
        return ActionStatus.progressed();
    }

    // 挥手：视线从他脸上往上抬，准星碰不到他、也碰不到方块了才按攻击键，两下隔半秒；抬到头顶还碰得到就不挥了。
    private ActionStatus swing(TickContext context) {
        float pitch = Math.max(-90, basePitch - raise);
        head.turnToward(baseYaw, pitch);
        sinceSwing++;
        if (!settled(baseYaw, pitch)) return ActionStatus.running();
        if (!moves.aimsAtNothing()) {
            if (pitch <= -90) {
                skipped = "眼前贴着方块，挥手会挖到，少挥 " + (count - done) + " 下";
                return ActionStatus.done();
            }
            raise += SWING_RAISE_STEP;
            return ActionStatus.running();
        }
        if (sinceSwing >= NonverbalRhythm.SWING_GAP_TICKS && moves.pressAttack(context)) {
            done++;
            sinceSwing = 0;
        }
        return done >= count ? ActionStatus.done() : ActionStatus.progressed();
    }

    // 拍一下：准星落在他身上再按攻击键；等了两秒还对不上（隔着东西、站不够近）就如实说拍不到。
    private ActionStatus poke(TickContext context) {
        head.turnToward(baseYaw, basePitch);
        if (moves.aimedEntity() == partner.entityId() && moves.pressAttack(context)) {
            done = 1;
            return ActionStatus.done();
        }
        if (tick >= POKE_PATIENCE_TICKS) {
            return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "准星对不上 " + partner.name() + "：中间隔着东西，或者离得不够近", "走到他跟前、两人之间没有遮挡再拍"));
        }
        return ActionStatus.running();
    }

    private boolean settled(float yaw, float pitch) {
        return Math.abs(Aim.wrap(head.yaw() - yaw)) < SETTLED_DEGREES && Math.abs(head.pitch() - pitch) < SETTLED_DEGREES;
    }

    // 被打断、做完：松开潜行和跳，不再为看人转头，镜头停在哪就是哪。
    @Override public void pause() {
        release();
    }

    @Override public void close() {
        release();
    }

    private void release() {
        if (last != null && (op == NonverbalOperation.CROUCH || op == NonverbalOperation.JUMP)) {
            moves.press(last, false, false);
        }
        if (head != null && holding) head.letGo();
        holding = false;
    }

    // 看着、点头、摇头只是转头，随时能插；蹲起、跳、挥手、拍一下手上按着键，正常干活。
    @Override public Interruptibility interruptibility() {
        return op == NonverbalOperation.LOOK || op == NonverbalOperation.NOD || op == NonverbalOperation.SHAKE_HEAD
                ? Interruptibility.BETWEEN_ACTIONS : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return op == NonverbalOperation.LOOK ? "看着他" : op.words + "第 " + (done + 1) + " 下";
    }
}
