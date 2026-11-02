// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import net.minecraft.util.Mth;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 环顾：站定转头扫一圈——先往一边转约 90 度、略低头，再扫到另一边、抬头，最后回到站定朝向，约两秒。
 *
 * <p>站着找东西的玩家就是这样看的：找东西扫描时、钻洞每走一段停下时、读了周围却没什么可看时，都用这一个动作。
 * 先往哪边、转多大、两头停多久都带随机，不是匀速来回扫；转头期间角色朝哪边按站定朝向算，
 * 观察里报的前后左右不跟着头走。转头只是让观众看见它在看：感知以视线有没有被挡住判断看没看见，
 * 和镜头朝哪无关，所以环顾不改变看到的东西。
 *
 * <p>转头不算真实进展，也随时可以打断：找东西的停滞判断照旧只看命中，不急的生存需求照样能插进来。
 * 一次环顾扫完就做完；一直等着的（找东西扫描没扫完）扫完一圈停两到四秒再扫下一圈，不会自己做完。
 */
public final class LookAround implements Action {

    /** 一侧转多大（度）的下限与上限。类别：玩家常识（站着环顾一般左右各看九十度上下）。 */
    static final float SIDE_MIN_DEGREES = 70f;
    static final float SIDE_MAX_DEGREES = 95f;
    /** 扫的速度（度每秒）：一圈约两秒，比闲看快、比干活瞄准慢。类别：玩家常识。 */
    static final float SWEEP_DEGREES_PER_SECOND = 170f;
    /** 到了一头停多久（刻）的下限与上限。 */
    static final int EDGE_HOLD_MIN_TICKS = 3;
    static final int EDGE_HOLD_MAX_TICKS = 6;
    /** 第一头略低头（俯仰正数是低头）、第二头略抬头的幅度（度）。 */
    static final float LOOK_DOWN_DEGREES = 10f;
    static final float LOOK_UP_DEGREES = 4f;
    /** 一直等着时两圈之间停多久（刻）：两到四秒。 */
    static final int REST_MIN_TICKS = 40;
    static final int REST_MAX_TICKS = 80;
    /** 一个方向最多转多少刻：人按 F8 拿回了角色、镜头不动时不卡在半路。 */
    static final int WAYPOINT_LIMIT_TICKS = 40;
    /** 起扫的俯仰压在平视附近（度）：刚低头挖完脚下的，先抬起来再看。 */
    static final float BASE_PITCH_MIN = -10f;
    static final float BASE_PITCH_MAX = 20f;

    /** 扫一圈要经过的一个方向：偏航、俯仰、到了停几刻。 */
    record Waypoint(float yaw, float pitch, int holdTicks) {}

    private final Function<TickContext, TurnsHead> heads;
    private final RandomGenerator random;
    private final boolean keepsGoing;
    private List<Waypoint> plan;
    private HeadMotion motion;
    private int index;
    private int held;
    private int waited;
    private int resting;
    private boolean done;
    /** 最近一刻拿到的那份转头接缝：暂停与收尾时据它放开站定朝向。 */
    private TurnsHead lastHead;

    LookAround(Function<TickContext, TurnsHead> heads, RandomGenerator random, boolean keepsGoing) {
        this.heads = Objects.requireNonNull(heads, "heads");
        this.random = Objects.requireNonNull(random, "random");
        this.keepsGoing = keepsGoing;
    }

    /** 扫一圈就做完：钻洞每走一段停下看一眼、读了周围却没什么可看时用。 */
    public static LookAround once(RandomGenerator random) {
        return new LookAround(TurnsHead::of, random, false);
    }

    /** 一直扫到被收尾：找东西等扫描时用，扫完一圈停两到四秒再扫。 */
    public static LookAround whileWaiting(RandomGenerator random) {
        return new LookAround(TurnsHead::of, random, true);
    }

    /**
     * 扫一圈经过的方向，纯函数：先往随机的一边转 70–95 度、略低头，再扫到另一边、略抬头，最后回到站定朝向。
     *
     * @param standingYaw 站定朝向
     * @param basePitch   起扫时的俯仰（已压在平视附近）
     */
    static List<Waypoint> plan(float standingYaw, float basePitch, RandomGenerator random) {
        float sign = random.nextBoolean() ? 1f : -1f;
        float first = SIDE_MIN_DEGREES + random.nextFloat() * (SIDE_MAX_DEGREES - SIDE_MIN_DEGREES);
        float second = SIDE_MIN_DEGREES + random.nextFloat() * (SIDE_MAX_DEGREES - SIDE_MIN_DEGREES);
        List<Waypoint> points = new ArrayList<>(3);
        points.add(new Waypoint(Mth.wrapDegrees(standingYaw + sign * first), basePitch + LOOK_DOWN_DEGREES,
                holdTicks(random)));
        points.add(new Waypoint(Mth.wrapDegrees(standingYaw - sign * second), basePitch - LOOK_UP_DEGREES,
                holdTicks(random)));
        points.add(new Waypoint(Mth.wrapDegrees(standingYaw), basePitch, 0));
        return List.copyOf(points);
    }

    private static int holdTicks(RandomGenerator random) {
        return EDGE_HOLD_MIN_TICKS + random.nextInt(EDGE_HOLD_MAX_TICKS - EDGE_HOLD_MIN_TICKS + 1);
    }

    @Override public ActionStatus tick(TickContext context) {
        if (done) return ActionStatus.done();
        TurnsHead head = heads.apply(context);
        if (head == null) return ActionStatus.running();
        lastHead = head;
        if (resting > 0) {
            // 一圈扫完回到站定朝向，停一会儿再扫下一圈：镜头不动就是站着在看。
            resting--;
            return ActionStatus.running();
        }
        if (plan == null) begin(head);
        Waypoint at = plan.get(index);
        boolean arrived = motion.step(head);
        waited++;
        if (!arrived && waited <= WAYPOINT_LIMIT_TICKS) return ActionStatus.running();
        if (held < at.holdTicks()) {
            held++;
            return ActionStatus.running();
        }
        return nextWaypoint(head);
    }

    // 开扫：记下站定朝向（转头期间角色朝哪边按它算），按此刻的朝向排好这一圈经过的方向。
    private void begin(TurnsHead head) {
        head.hold();
        float basePitch = Mth.clamp(head.pitch(), BASE_PITCH_MIN, BASE_PITCH_MAX);
        plan = plan(head.standingYaw(), basePitch, random);
        motion = new HeadMotion(SWEEP_DEGREES_PER_SECOND);
        index = 0;
        startWaypoint();
    }

    // 到了这个方向、也停够了：转向下一个；一圈走完时一次环顾做完，一直等着的停一会儿再扫。
    private ActionStatus nextWaypoint(TurnsHead head) {
        index++;
        if (index < plan.size()) {
            startWaypoint();
            return ActionStatus.running();
        }
        plan = null;
        if (keepsGoing) {
            resting = REST_MIN_TICKS + random.nextInt(REST_MAX_TICKS - REST_MIN_TICKS + 1);
            return ActionStatus.running();
        }
        done = true;
        head.letGo();
        return ActionStatus.done();
    }

    private void startWaypoint() {
        Waypoint at = plan.get(index);
        motion.aim(at.yaw(), at.pitch());
        held = 0;
        waited = 0;
    }

    // 被生存需求打断：放开站定朝向，回来从头扫一圈（镜头早被临时任务转过了）。
    @Override public void pause() {
        release();
        plan = null;
        resting = 0;
    }

    @Override public void close() {
        release();
    }

    private void release() {
        if (lastHead != null) lastHead.letGo();
    }

    // 转头随时停得下：找东西扫描期间，不急的生存需求照样能插进来。
    @Override public Interruptibility interruptibility() {
        return Interruptibility.BETWEEN_ACTIONS;
    }

    @Override public String describe() {
        return resting > 0 ? "站着看" : "原地环顾";
    }
}
