// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.world.phys.Vec3;

/**
 * 固定翼的飞控（纯函数的状态机，每刻吃一个样本吐一个输出）：机翼的升力随速度涨，不能悬停，所以
 * 滑跑（满动力沿机头方向跑，离地了记下实测离地速度；速度不涨先抬头帮一把；前方剩下的地面不够刹停就中止刹住）→
 * 爬升（离地 8 格内直着爬、不压坡度）→ 巡航（朝跑道的进近点飞，没有跑道就朝目的地飞、到了绕圈）→
 * 进近（到跑道延长线上对准）→ 下滑（约 6° 下滑线，速度保持在离地速度的 1.25 倍）→ 拉平（近地收动力）→
 * 落地滑跑（刹停）→ 停下。落不好复飞，同一条跑道 3 次落不下由外面换下一条；最后那条硬落、不再复飞。
 * 离地速度、滑跑距离都是这一趟量出来的，不要 LLM 声明，也不预先判断跑道够不够长。
 * 没有俯仰键靠油门管高度；没有横滚键平着用偏航转弯。翻了、失速先摆平保速度。
 */
final class FixedWingPilot {

    enum Phase { TAKEOFF_ROLL, STOP_TAKEOFF, CLIMB_OUT, CRUISE, APPROACH, DESCENT, FLARE, ROLLOUT, GO_AROUND, LANDED, FAILED }

    /** 离地多高以内不转弯、不压坡度（翼尖会擦地）。C */
    static final double CLEARANCE = 8;
    /** 接地或离地连着几个样本才算数；离地还要比起飞时高多少格。C */
    static final int SURE_SAMPLES = 3;
    static final double LIFT_RISE = 1.5;
    /** 速度连着这么多刻涨不到这么多（格每秒）就算不涨了。C */
    static final int PLATEAU_TICKS = 20;
    static final double PLATEAU_GAIN = 0.2;
    /** 抬头帮一把抬到几度；爬升按几度；拉平抬几度。C */
    static final double ASSIST_PITCH = 10;
    static final double CLIMB_PITCH = 8;
    static final double FLARE_PITCH = 3;
    /** 刹停按每秒几格 × 2 秒估。C */
    static final double STOP_SECONDS = 2;
    /** 满动力滑这么久还没离地、速度也不涨就中止（刻）。C */
    static final int ROLL_TICKS = 600;
    /** 慢到每秒几格算停下。C */
    static final double STOPPED_SPEED = 0.35;
    /** 保速度：实测离地速度的几倍以下先保速度；进近、下滑、巡航各按几倍飞。C */
    static final double SLOW = 1.1;
    static final double APPROACH_SPEED = 1.25;
    static final double CRUISE_SPEED = 1.6;
    /** 满动力连着这么久每秒升不到这么多格，就按此刻高度巡航。F：越高升力越弱 */
    static final int CEILING_TICKS = 100;
    static final double CEILING_RATE = 0.3;
    /** 坡度最多几度。C */
    static final double MAX_BANK = 30;
    /** 进近：跑道上方几格。C */
    static final double PATTERN_HEIGHT = 12;
    /** 朝向差几度以内才下滑；偏多少复飞。C */
    static final double ALIGNED = 15;
    static final double MISALIGNED = 30;
    /** 拉平：离跑道几格高（快的时候按每秒几格 × 0.15）；拉平后几刻还没接地复飞。C */
    static final double FLARE_HEIGHT = 2;
    static final double FLARE_LEAD = 0.15;
    static final int FLARE_TICKS = 100;
    /** 同一条跑道几次落不下换下一条。C */
    static final int ATTEMPTS = 3;
    /** 翻了：横滚、俯仰超过几度。C */
    static final double UPSET_ROLL = 60;
    static final double UPSET_PITCH = 40;
    /** 平飞满动力连着这么久还在掉速、掉高度，算动力没了（刻）。C */
    static final int POWER_LOSS_TICKS = 60;
    /** 空中接手、没有实测离地速度时按这个（格每秒）。C */
    static final double UNMEASURED_LIFTOFF = 10;
    /** 没有跑道时在目的地上空绕圈的半径（格）。C */
    static final double ORBIT_RADIUS = 48;
    /** 正下方是水、船底离水面这么近以内（或泡进水里）算碰到水面，和压着地面一样算接地（格）。C */
    static final double AFLOAT = 0.3;

    /**
     * 这一刻的姿态：机头抬起多少度（抬头为正），右翼往下多少度（右压为正）。
     */
    record Attitude(double pitch, double roll) {}

    /**
     * 这一刻外面量的：起飞时机头前方畅通多远（没量为 NaN），机身正下方地面的顶面（不知道为 NaN；正下方是水时是水面），
     * 选好的跑道上长出了挡路的方块，正下方是不是水。
     */
    record Situation(double clearAhead, double groundBelow, boolean runwayBlocked, boolean waterBelow) {

        /** 正下方不是水。 */
        Situation(double clearAhead, double groundBelow, boolean runwayBlocked) {
            this(clearAhead, groundBelow, runwayBlocked, false);
        }
    }

    /** 有哪些操纵：俯仰、横滚、偏航、地上的转向（前轮打方向）、刹车。 */
    record Controls(boolean pitch, boolean roll, boolean yaw, boolean steer, boolean brake) {}

    /**
     * 这一刻的输出，连续量，由按键混合调成开关键。
     *
     * @param power 动力 0～1（负的是倒车）
     * @param pitch 俯仰 -1～1，正的抬头
     * @param roll  横滚 -1～1，正的往右压
     * @param yaw   偏航 -1～1，正的往右
     * @param steer 地上的转向 -1～1，正的往右
     * @param brake 刹车
     */
    record Command(double power, double pitch, double roll, double yaw, double steer, boolean brake) {
        static final Command IDLE = new Command(0, 0, 0, 0, 0, false);

        Command {
            power = PilotCommand.clamp(power, -1, 1);
            pitch = PilotCommand.clamp(pitch, -1, 1);
            roll = PilotCommand.clamp(roll, -1, 1);
            yaw = PilotCommand.clamp(yaw, -1, 1);
            steer = PilotCommand.clamp(steer, -1, 1);
        }
    }

    private final Controls controls;
    private final double halfLength;
    private final double span;
    private final List<String> transitions = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final Takeoff takeoff = new Takeoff();
    private final Landing landing = new Landing();
    private double cruise;
    private Vec3 aim;
    private Phase phase;
    private long phaseStart = Long.MIN_VALUE;
    private int airborne;
    private int grounded;
    private PilotSample last;
    private Attitude lastAttitude;
    private double pitchRate;
    private double rollRate;
    private long since = Long.MIN_VALUE;
    /** 保速度要开多少油门：按实测慢慢修，开多少能不快不慢事先不知道。 */
    private double throttle = 0.55;
    /** 保高度要多抬或多压几度：同样按实测慢慢修（度）。 */
    private double pitchTrim;
    private boolean recovering;
    private boolean stopRequested;
    private boolean powerLost;
    private String failure = "";

    /** 滑跑与离地的记录。 */
    private static final class Takeoff {
        Vec3 start;
        double heading;
        double rolled;
        double top;
        double liftoffSpeed = Double.NaN;
        /** 这一次刚离开地面那一刻的速度：升力刚够托起机身，就是离地速度。 */
        double unstuck = Double.NaN;
        boolean leftGround;
        double markSpeed;
        long markTick = Long.MIN_VALUE;
        boolean plateau;
        String why = "";
    }

    /** 落地的记录：要落的跑道、是不是给目的地挑的、硬落、这条试了几次、一共复飞几次、冲出跑道几格。 */
    private static final class Landing {
        Runway runway;
        boolean forDestination;
        boolean committed;
        int attempts;
        int goArounds;
        boolean wantsNext;
        boolean unplanned;
        double overrun;
        boolean onRunway;
    }

    /**
     * @param controls    有哪些操纵
     * @param halfLength  机身半长（格）：起飞刹停按它留余量
     * @param span        翼展（格）：对准跑道按它
     * @param cruise      巡航高度（船底的世界 y）
     * @param aim         目的地：没有跑道时朝它飞、到了绕圈
     * @param inAir       接手时已经在天上（没有实测离地速度）
     */
    FixedWingPilot(Controls controls, double halfLength, double span, double cruise, Vec3 aim, boolean inAir) {
        this.controls = controls;
        this.halfLength = halfLength;
        this.span = span;
        this.cruise = cruise;
        this.aim = aim;
        this.phase = inAir ? Phase.CRUISE : Phase.TAKEOFF_ROLL;
        takeoff.leftGround = inAir;
    }

    Phase phase() {
        return phase;
    }

    List<String> transitions() {
        return List.copyOf(transitions);
    }

    /** 途中值得写进结果的事：抬头帮了一把、巡航高度降了、失速摆平过、动力没了。 */
    List<String> notes() {
        return List.copyOf(notes);
    }

    String failure() {
        return failure;
    }

    /** 滑了几格；离地前就是到此刻滑了几格。 */
    double rolled() {
        return takeoff.rolled;
    }

    /** 实测离地速度（格每秒）；没离地、空中接手为 NaN。 */
    double liftoffSpeed() {
        return takeoff.liftoffSpeed;
    }

    boolean leftGround() {
        return takeoff.leftGround;
    }

    /** 起飞的地方（滑跑开始时船底中心）与朝向；空中接手为 null。 */
    Vec3 takeoffStart() {
        return takeoff.start;
    }

    double takeoffHeading() {
        return takeoff.heading;
    }

    int goArounds() {
        return landing.goArounds;
    }

    Runway runway() {
        return landing.runway;
    }

    /** 这条跑道连着 3 次落不下，要外面换下一条。 */
    boolean wantsNextRunway() {
        return landing.wantsNext;
    }

    /** 平飞满动力保不住速度和高度：燃料烧完、过载停转。 */
    boolean powerLost() {
        return powerLost;
    }

    /** 停在为目的地挑的那条跑道上了。 */
    boolean arrived() {
        return phase == Phase.LANDED && landing.forDestination && landing.onRunway && !landing.unplanned;
    }

    /** 还没到跑道就碰到了地面（巡航撞山、压到树）。 */
    boolean unplannedTouchdown() {
        return landing.unplanned;
    }

    /** 落地滑跑冲出跑道几格。 */
    double overrun() {
        return landing.overrun;
    }

    double pitchRate() {
        return pitchRate;
    }

    double rollRate() {
        return rollRate;
    }

    /** 往这条跑道落：换跑道时从进近点重新来；committed 是最后一次，对没对准都落下去、不再复飞。 */
    void land(Runway runway, boolean forDestination, boolean committed) {
        landing.runway = runway;
        landing.forDestination = forDestination;
        landing.committed = committed;
        landing.attempts = 0;
        landing.wantsNext = false;
        if (phase == Phase.APPROACH || phase == Phase.DESCENT || phase == Phase.FLARE) {
            go(Phase.CRUISE, "换一条跑道：" + runway.describe());
        }
    }

    /** 不再往原来那条跑道落（就近找新的），在这一点上空绕圈等。 */
    void orbit(Vec3 center) {
        aim = center;
        landing.runway = null;
        landing.forDestination = false;
        if (phase == Phase.APPROACH || phase == Phase.DESCENT || phase == Phase.FLARE) go(Phase.CRUISE, "就近找跑道，先绕圈");
    }

    /** 被叫先停：还在滑跑就中止起飞刹住；在天上由外面就近找跑道。 */
    void stop() {
        stopRequested = true;
    }

    /** 前方地形高了，巡航高度往上抬。 */
    void raiseCruise(double altitude) {
        if (altitude > cruise) cruise = altitude;
    }

    double cruise() {
        return cruise;
    }

    Command tick(PilotSample sample, Attitude attitude, Situation situation) {
        if (phaseStart == Long.MIN_VALUE) phaseStart = sample.tick();
        if (takeoff.start == null && phase == Phase.TAKEOFF_ROLL) {
            takeoff.start = sample.position();
            takeoff.heading = sample.heading();
        }
        if (lastAttitude != null) {
            pitchRate = (attitude.pitch() - lastAttitude.pitch()) * 20;
            rollRate = (attitude.roll() - lastAttitude.roll()) * 20;
        }
        // 浮在水上、落到水面都算接地：水没有碰撞，船底泡在水里时接地探头只会说悬空，离水面才算离地。
        boolean touching = sample.grounded() || afloat(sample, situation);
        airborne = !touching && VehicleAttitude.AIRBORNE.equals(sample.contact()) ? airborne + 1 : 0;
        grounded = touching ? grounded + 1 : 0;
        Command command = steer(sample, attitude, situation);
        last = sample;
        lastAttitude = attitude;
        return command;
    }

    // 先看翻没翻、失速没有，再按阶段走。
    private Command steer(PilotSample sample, Attitude attitude, Situation situation) {
        double height = height(sample, situation);
        if (flying()) {
            Command saved = rescue(sample, attitude, height);
            if (saved != null) return saved;
            if (phase == Phase.GO_AROUND && grounded >= SURE_SAMPLES) {
                go(sample, Phase.ROLLOUT, "复飞时碰到了跑道，收动力刹住");
            } else if (phase != Phase.DESCENT && takeoff.leftGround && grounded >= SURE_SAMPLES) {
                landing.unplanned = true;
                go(sample, Phase.ROLLOUT, "还没到跑道就碰到了地面，收动力刹住");
            }
        }
        return switch (phase) {
            case TAKEOFF_ROLL -> roll(sample, attitude, situation);
            case STOP_TAKEOFF -> stopTakeoff(sample, attitude);
            case CLIMB_OUT -> climb(sample, attitude, height);
            case CRUISE -> cruise(sample, attitude, height);
            case APPROACH -> approach(sample, attitude, height);
            case DESCENT -> descend(sample, attitude, height, situation);
            case FLARE -> flare(sample, attitude);
            case ROLLOUT -> rollout(sample, attitude);
            case GO_AROUND -> goAround(sample, attitude, height);
            case LANDED, FAILED -> Command.IDLE;
        };
    }

    private boolean flying() {
        return phase == Phase.CLIMB_OUT || phase == Phase.CRUISE || phase == Phase.APPROACH
                || phase == Phase.DESCENT || phase == Phase.GO_AROUND;
    }

    // 翻了、失速：离地 8 格以上先摆平机翼、压机头、满动力，恢复了再接着飞；离地 8 格以内翻了就松手交给坠落自救。
    private Command rescue(PilotSample sample, Attitude attitude, double height) {
        boolean upset = Math.abs(attitude.roll()) > UPSET_ROLL || Math.abs(attitude.pitch()) > UPSET_PITCH;
        if (upset && height < CLEARANCE && !sample.grounded()) {
            fail(sample, "离地不到 " + (int) CLEARANCE + " 格时翻了（横滚 " + Math.round(attitude.roll()) + "°、俯仰 "
                    + Math.round(attitude.pitch()) + "°），松开所有键");
            return Command.IDLE;
        }
        // 没有俯仰键的慢下来就是在降高度（升力随速度涨，没有失速一说），只看翻没翻。
        boolean stalling = controls.pitch() && sample.flatSpeed() < liftoff() * 0.9;
        if (!recovering && height >= CLEARANCE && (upset || stalling)) {
            recovering = true;
            notes.add((upset ? "翻了" : "失速") + "（每秒 " + round(sample.flatSpeed()) + " 格），先摆平机翼、保速度");
        }
        if (!recovering) return null;
        if (Math.abs(attitude.roll()) < 20 && Math.abs(attitude.pitch()) < 15
                && (!controls.pitch() || sample.flatSpeed() >= liftoff() * SLOW)) {
            recovering = false;
            return null;
        }
        return new Command(1, controls.pitch() ? attitudeServo(-5 - attitude.pitch(), pitchRate) : 0,
                controls.roll() ? attitudeServo(-attitude.roll(), rollRate) : 0, 0, 0, false);
    }

    // 滑跑：满动力沿起飞时的机头方向跑，俯仰不动；离地就爬升；速度不涨先抬头帮一把；前方不够刹停就中止。
    private Command roll(PilotSample sample, Attitude attitude, Situation situation) {
        double speed = sample.flatSpeed();
        takeoff.rolled = sample.flatDistance(takeoff.start);
        takeoff.top = Math.max(takeoff.top, speed);
        // 离地速度记刚离开地面那一刻的：等确认离地（升高 1.5 格）时已经又快了一截。
        if (airborne == 1) takeoff.unstuck = speed;
        if (airborne >= SURE_SAMPLES && sample.position().y > takeoff.start.y + LIFT_RISE) {
            takeoff.leftGround = true;
            takeoff.liftoffSpeed = Math.max(1, Double.isNaN(takeoff.unstuck) ? speed : takeoff.unstuck);
            go(sample, Phase.CLIMB_OUT, "离地了：滑了 " + Math.round(takeoff.rolled) + " 格，离地时每秒 "
                    + round(takeoff.liftoffSpeed) + " 格");
            return settle(sample, attitude);
        }
        if (takeoff.markTick == Long.MIN_VALUE || sample.tick() - takeoff.markTick >= PLATEAU_TICKS) {
            takeoff.plateau = takeoff.markTick != Long.MIN_VALUE && speed - takeoff.markSpeed < PLATEAU_GAIN;
            takeoff.markSpeed = speed;
            takeoff.markTick = sample.tick();
        }
        String why = null;
        if (stopRequested) {
            why = "按要求先停下";
        } else if (!Double.isNaN(situation.clearAhead()) && situation.clearAhead() < speed * STOP_SECONDS + halfLength) {
            why = "前方畅通的地面只剩 " + Math.round(situation.clearAhead()) + " 格，不够每秒 " + round(speed) + " 格刹停";
        } else if (sample.tick() - phaseStart >= ROLL_TICKS && takeoff.plateau) {
            why = "满动力滑了 " + ROLL_TICKS / 20 + " 秒，速度涨到每秒 " + round(takeoff.top) + " 格就不涨了";
        }
        if (why != null) {
            takeoff.why = why;
            go(sample, Phase.STOP_TAKEOFF, why + "，还没离地，中止起飞");
            return settle(sample, attitude);
        }
        if (takeoff.plateau && controls.pitch() && notes.stream().noneMatch(note -> note.startsWith("速度不涨"))) {
            notes.add("速度不涨了（每秒 " + round(speed) + " 格），抬头帮它离地");
        }
        double pitch = takeoff.plateau && controls.pitch() ? attitudeServo(ASSIST_PITCH - attitude.pitch(), pitchRate) : 0;
        return ground(sample, attitude, takeoff.heading, 1, pitch, false);
    }

    // 中止起飞：收动力、刹车、保持方向，慢下来就停；按要求停的停住等，自己中止的照实失败。
    private Command stopTakeoff(PilotSample sample, Attitude attitude) {
        if (sample.flatSpeed() < STOPPED_SPEED) {
            if (stopRequested) {
                go(sample, Phase.LANDED, "按要求停住了");
            } else {
                fail(sample, "中止起飞：" + takeoff.why + "；滑了 " + Math.round(takeoff.rolled) + " 格、最快每秒 "
                        + round(takeoff.top) + " 格，没离地");
            }
            return Command.IDLE;
        }
        return ground(sample, attitude, takeoff.heading, 0, 0, true);
    }

    // 爬升：离地 8 格内沿跑道方向直着爬、机翼摆平；之后转向航线，满动力爬到巡航高度；爬不动就按此刻高度巡航。
    private Command climb(PilotSample sample, Attitude attitude, double height) {
        if (sample.position().y >= cruise - 2) {
            go(sample, Phase.CRUISE, "爬到巡航高度 " + Math.round(cruise));
            return settle(sample, attitude);
        }
        if (height >= CLEARANCE && sample.velocity().y < CEILING_RATE) {
            if (since == Long.MIN_VALUE) since = sample.tick();
            if (sample.tick() - since >= CEILING_TICKS) {
                notes.add("满动力升不上去（越高升力越弱），巡航高度从 " + Math.round(cruise) + " 降到 "
                        + Math.round(sample.position().y));
                cruise = sample.position().y;
                go(sample, Phase.CRUISE, "升不上去了，按此刻高度巡航");
                return settle(sample, attitude);
            }
        } else {
            since = Long.MIN_VALUE;
        }
        boolean low = height < CLEARANCE;
        double heading = low && takeoff.start != null ? takeoff.heading : course(sample);
        double pitch = sample.flatSpeed() < liftoff() * SLOW ? 0 : CLIMB_PITCH;
        return air(sample, attitude, heading, low, 1, pitch);
    }

    // 巡航：有跑道朝它的进近点飞、按离进近点的远近往进近高度降；没有就朝目的地飞，到了绕圈等跑道。
    private Command cruise(PilotSample sample, Attitude attitude, double height) {
        double altitude = cruise;
        Runway runway = landing.runway;
        if (runway != null) {
            Vec3 point = runway.at(-Runways.APPROACH_OUT, 0);
            double distance = sample.flatDistance(point);
            altitude = Math.max(runway.y() + PATTERN_HEIGHT,
                    Math.min(cruise, runway.y() + PATTERN_HEIGHT + distance * Runways.GLIDE));
            if (distance < Math.max(24, sample.flatSpeed() * 3)) {
                go(sample, Phase.APPROACH, "到了进近点，转向对准跑道");
                return settle(sample, attitude);
            }
        }
        Command command = level(sample, attitude, course(sample), altitude, CRUISE_SPEED, height < CLEARANCE);
        watchPower(sample, command.power());
        return command;
    }

    // 进近：跑道上方 12 格对着跑道中线飞，朝向差、离中线都够小才下滑；到了跑道头还没对准就复飞。
    private Command approach(PilotSample sample, Attitude attitude, double height) {
        Runway runway = landing.runway;
        if (runway == null) {
            go(sample, Phase.CRUISE, "跑道不要了，回去绕圈");
            return settle(sample, attitude);
        }
        double along = runway.along(sample.position());
        double lateral = runway.lateral(sample.position());
        double off = Math.abs(PilotSample.turn(sample.heading(), runway.heading()));
        if (off < ALIGNED && Math.abs(lateral) <= span / 2 && along < -20) {
            go(sample, Phase.DESCENT, "对准了跑道，开始下滑");
        } else if (along > -20) {
            if (!landing.committed) return startGoAround(sample, attitude, height, "没对准跑道就到了跑道头");
            go(sample, Phase.DESCENT, "最后一次，对没对准都往下落");
        }
        if (phase == Phase.DESCENT) return settle(sample, attitude);
        return level(sample, attitude, centerline(runway, lateral), runway.y() + PATTERN_HEIGHT, APPROACH_SPEED, false);
    }

    // 下滑：沿 6° 下滑线降，对着中线修；近地拉平；落不好复飞（最后一次不复飞）。
    private Command descend(PilotSample sample, Attitude attitude, double height, Situation situation) {
        Runway runway = landing.runway;
        if (grounded >= SURE_SAMPLES) {
            go(sample, Phase.ROLLOUT, "接地了，收动力刹车");
            return settle(sample, attitude);
        }
        double above = sample.position().y - runway.y();
        if (above <= Math.max(FLARE_HEIGHT, sample.flatSpeed() * FLARE_LEAD)) {
            go(sample, Phase.FLARE, "离跑道 " + round(above) + " 格，收动力拉平");
            return settle(sample, attitude);
        }
        double along = runway.along(sample.position());
        double lateral = runway.lateral(sample.position());
        if (!landing.committed) {
            String why = missed(sample, runway, along, lateral, above, situation.runwayBlocked());
            if (why != null) return startGoAround(sample, attitude, height, why);
        }
        double aimAt = Math.min(16, runway.length() * 0.2);
        // 下滑线从跑道头往里一点（瞄准点）往外抬，最高到进近高度。
        double glide = runway.y() + Math.min(PATTERN_HEIGHT, Math.max(0, aimAt - along) * Runways.GLIDE);
        return level(sample, attitude, centerline(runway, lateral), glide, APPROACH_SPEED, height < CLEARANCE);
    }

    // 下滑中落不好的几种情形；都没有为 null。
    private String missed(PilotSample sample, Runway runway, double along, double lateral, double above, boolean blocked) {
        double off = Math.abs(PilotSample.turn(sample.heading(), runway.heading()));
        if (Math.abs(lateral) > span) return "离跑道中线 " + Math.round(Math.abs(lateral)) + " 格";
        if (off > MISALIGNED) return "朝向和跑道偏了 " + Math.round(off) + "°";
        if (along > runway.length() / 2 && above > 1) return "过了跑道一半还离地 " + round(above) + " 格";
        if (controls.pitch() && sample.flatSpeed() < liftoff() * SLOW) {
            return "速度掉到每秒 " + round(sample.flatSpeed()) + " 格";
        }
        if (blocked) return "跑道上有挡路的方块";
        return null;
    }

    // 拉平：收动力、微微抬头、对着跑道方向，等它接地；5 秒还没接地复飞（最后一次不复飞）。
    private Command flare(PilotSample sample, Attitude attitude) {
        if (grounded >= SURE_SAMPLES) {
            go(sample, Phase.ROLLOUT, "接地了，收动力刹车");
            return settle(sample, attitude);
        }
        if (sample.tick() - phaseStart >= FLARE_TICKS && !landing.committed) {
            return startGoAround(sample, attitude, CLEARANCE, "拉平 " + FLARE_TICKS / 20 + " 秒还没接地");
        }
        double pitch = controls.pitch() ? FLARE_PITCH : 0;
        double heading = landing.runway != null ? landing.runway.heading() : sample.heading();
        return air(sample, attitude, heading, true, 0, pitch);
    }

    // 落地滑跑：收动力、刹车、保持方向，慢下来就停；记下冲出跑道几格、停没停在跑道上。
    private Command rollout(PilotSample sample, Attitude attitude) {
        Runway runway = landing.runway;
        if (runway != null && !landing.unplanned) {
            landing.overrun = Math.max(landing.overrun, runway.along(sample.position()) - runway.length());
        }
        if (sample.flatSpeed() < STOPPED_SPEED) {
            landing.onRunway = runway != null && Math.abs(runway.lateral(sample.position())) <= runway.width()
                    && runway.along(sample.position()) >= -8 && runway.along(sample.position()) <= runway.length() + 8;
            go(sample, Phase.LANDED, landing.overrun > 1 ? "停下了，冲出跑道 " + Math.round(landing.overrun) + " 格" : "停下了");
            return Command.IDLE;
        }
        double heading = runway != null && !landing.unplanned ? runway.heading() : sample.heading();
        return ground(sample, attitude, heading, 0, 0, true);
    }

    // 复飞：满动力、抬头，沿跑道方向爬回进近高度以上，再绕回进近点。
    private Command goAround(PilotSample sample, Attitude attitude, double height) {
        Runway runway = landing.runway;
        double floor = runway != null ? runway.y() + PATTERN_HEIGHT + 4 : cruise;
        if (sample.position().y >= floor && airborne >= SURE_SAMPLES) {
            go(sample, Phase.CRUISE, "爬回进近高度，绕回进近点重来");
            return settle(sample, attitude);
        }
        double heading = runway != null ? runway.heading() : sample.heading();
        double pitch = sample.flatSpeed() < liftoff() * SLOW ? 0 : CLIMB_PITCH;
        return air(sample, attitude, heading, height < CLEARANCE, 1, pitch);
    }

    private Command startGoAround(PilotSample sample, Attitude attitude, double height, String why) {
        landing.goArounds++;
        landing.attempts++;
        if (landing.attempts >= ATTEMPTS) landing.wantsNext = true;
        go(sample, Phase.GO_AROUND, why + "，复飞");
        return settle(sample, attitude);
    }

    // 刚换了阶段的这一刻：不接着走下一个阶段，只给一个保守的输出——地上刹住，拉平收动力，
    // 爬升与复飞满动力，别的稳住朝向、机翼摆平；下一刻再按新阶段飞。
    private Command settle(PilotSample sample, Attitude attitude) {
        return switch (phase) {
            case STOP_TAKEOFF, ROLLOUT -> ground(sample, attitude, sample.heading(), 0, 0, true);
            case FLARE -> air(sample, attitude, sample.heading(), true, 0, controls.pitch() ? FLARE_PITCH : 0);
            case CLIMB_OUT, GO_AROUND -> air(sample, attitude, sample.heading(), true, 1, CLIMB_PITCH);
            case LANDED, FAILED -> Command.IDLE;
            default -> air(sample, attitude, sample.heading(), true, 0.6, 0);
        };
    }

    // 平飞满动力还在掉速、掉高度：动力没了（燃料烧完、过载停转），交给外面就近迫降。
    private void watchPower(PilotSample sample, double power) {
        if (powerLost || last == null) return;
        boolean sinking = power >= 0.99 && sample.flatSpeed() < last.flatSpeed() && sample.velocity().y < 0
                && sample.flatSpeed() < liftoff() * CRUISE_SPEED;
        if (!sinking) {
            since = Long.MIN_VALUE;
            return;
        }
        if (since == Long.MIN_VALUE) since = sample.tick();
        if (sample.tick() - since >= POWER_LOSS_TICKS) {
            powerLost = true;
            notes.add("平飞满动力还在掉速、掉高度，动力像是没了（燃料烧完或停转）");
        }
    }

    // 朝哪飞：有跑道朝进近点；没有朝目的地，到了上空绕圈（往右绕）。
    private double course(PilotSample sample) {
        if (landing.runway != null) return sample.headingTo(landing.runway.at(-Runways.APPROACH_OUT, 0));
        double distance = sample.flatDistance(aim);
        double toward = sample.headingTo(aim);
        if (distance > ORBIT_RADIUS * 1.2) return toward;
        return toward + 90 - Math.max(-45, Math.min(45, (distance - ORBIT_RADIUS) * 1.5));
    }

    // 对着跑道中线飞：在中线右边就往左偏一点切回来，最多偏 30°。
    private static double centerline(Runway runway, double lateral) {
        return runway.heading() - Math.max(-30, Math.min(30, lateral * 3));
    }

    // 天上按高度与速度飞：有俯仰键俯仰管高度、油门管速度；没有俯仰键油门管高度，慢了加满。
    private Command level(PilotSample sample, Attitude attitude, double heading, double altitude, double speedFactor,
            boolean wingsLevel) {
        double y = sample.position().y;
        double speed = sample.flatSpeed();
        double wanted = liftoff() * speedFactor;
        if (controls.pitch()) {
            double vertical = Math.max(-2, Math.min(3, (altitude - y) * 0.25));
            // 离要的高度 6 格以内时按高度差慢慢修俯仰配平：只靠比例总差一截落不到位。
            if (Math.abs(altitude - y) < 6) pitchTrim = Math.max(-8, Math.min(8, pitchTrim + (altitude - y) * 0.01));
            double pitch = Math.toDegrees(Math.atan2(vertical, Math.max(1, speed))) + (vertical - sample.velocity().y) * 1.5
                    + pitchTrim;
            pitch = Math.max(-12, Math.min(CLIMB_PITCH, pitch));
            if (speed < liftoff() * SLOW) pitch = Math.min(pitch, 0);
            // 油门 = 慢慢修出来的配平 + 速度差 × 0.2：配平按速度差一点点加减，不快不慢时就停在那里。
            throttle = Math.max(0.1, Math.min(1, throttle + (wanted - speed) * 0.002));
            double power = Math.max(0.1, Math.min(1, throttle + (wanted - speed) * 0.2));
            return air(sample, attitude, heading, wingsLevel, power, pitch);
        }
        double power = Math.max(0, Math.min(1, 0.55 + (altitude - y) * 0.1 - sample.velocity().y * 0.1));
        return air(sample, attitude, heading, wingsLevel, power, 0);
    }

    // 天上：偏航修朝向，有横滚就压坡度配上（离地近时机翼摆平），俯仰按要的角度；没有俯仰键不按。
    private Command air(PilotSample sample, Attitude attitude, double heading, boolean wingsLevel, double power,
            double pitchAngle) {
        double error = PilotSample.turn(sample.heading(), heading);
        double yaw = controls.yaw() ? AirshipPilot.servo(error, sample.turnRate()) : 0;
        double bank = wingsLevel || !controls.roll() ? 0 : Math.max(-MAX_BANK, Math.min(MAX_BANK, error * 0.65));
        double roll = controls.roll() ? attitudeServo(bank - attitude.roll(), rollRate) : 0;
        double pitch = controls.pitch() ? attitudeServo(pitchAngle - attitude.pitch(), pitchRate) : 0;
        return new Command(power, pitch, roll, yaw, 0, false);
    }

    // 地上：偏航与前轮转向保持方向，机翼摆平；pitch 是已经算好的俯仰输出。
    private Command ground(PilotSample sample, Attitude attitude, double heading, double power, double pitch,
            boolean brake) {
        double error = PilotSample.turn(sample.heading(), heading);
        double turn = AirshipPilot.servo(error, sample.turnRate());
        double roll = controls.roll() ? attitudeServo(-attitude.roll(), rollRate) : 0;
        return new Command(power, pitch, roll, controls.yaw() ? turn : 0, controls.steer() ? turn : 0,
                brake && controls.brake());
    }

    /** 正下方是水、船底贴着水面或泡在水里：浮着或刚落到水面上。 */
    static boolean afloat(PilotSample sample, Situation situation) {
        return situation.waterBelow() && !Double.isNaN(situation.groundBelow())
                && sample.position().y - situation.groundBelow() <= AFLOAT;
    }

    // 离地多高：知道正下方的地面就按它，不然按起飞时的高度。
    private double height(PilotSample sample, Situation situation) {
        if (!Double.isNaN(situation.groundBelow())) return sample.position().y - situation.groundBelow();
        double base = takeoff.start != null ? takeoff.start.y : landing.runway != null ? landing.runway.y() : Double.NaN;
        return Double.isNaN(base) ? CLEARANCE : sample.position().y - base;
    }

    // 实测离地速度；空中接手没有实测按默认。
    private double liftoff() {
        return Double.isNaN(takeoff.liftoffSpeed) ? UNMEASURED_LIFTOFF : takeoff.liftoffSpeed;
    }

    // 姿态的舵：角度差 × 3 − 角速度 × 0.8（都按弧度），太小的不按。
    static double attitudeServo(double errorDegrees, double rateDegrees) {
        double value = Math.toRadians(errorDegrees) * 3 - Math.toRadians(rateDegrees) * 0.8;
        return Math.abs(value) < 0.025 ? 0 : value;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private void go(PilotSample sample, Phase next, String why) {
        if (phase == next) return;
        transitions.add(phase.name().toLowerCase(Locale.ROOT) + " → " + next.name().toLowerCase(Locale.ROOT) + "：" + why);
        phase = next;
        phaseStart = sample.tick();
        since = Long.MIN_VALUE;
    }

    // 外面换跑道这类没有样本的阶段变化：从下一刻起算。
    private void go(Phase next, String why) {
        if (phase == next) return;
        transitions.add(phase.name().toLowerCase(Locale.ROOT) + " → " + next.name().toLowerCase(Locale.ROOT) + "：" + why);
        phase = next;
        phaseStart = Long.MIN_VALUE;
        since = Long.MIN_VALUE;
    }

    private void fail(PilotSample sample, String why) {
        failure = why;
        go(sample, Phase.FAILED, why);
    }
}
