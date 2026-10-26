// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.world.phys.Vec3;

/**
 * 装了飞行稳定器的飞艇的飞控（纯函数的状态机，每刻吃一个样本吐一个输出）：高度交给高度稳定器、船身交给姿态稳定器，
 * 这里只给"船底该在多高"和"水平往哪走、多快"，不调升力、不转向（线速度耦合器沿机体四个方向直接推，松开就刹）。
 * 起飞先把目标定在离地约 8 格、到了姿态稳了再走 → 巡航目标是巡航高度，离它还差 6 格以上先不往前走 →
 * 到进近范围越近越慢地移到落点上方 → 落点上方水平 1 格内、水平速度 &lt; 0.3 格/秒才降：
 * 目标每次比此刻低一截（离地 12 格以上每秒约 4 格、以内约 1 格），近地不再修水平，目标压到地面下一点点 → 接地交给停稳确认。
 * 巡航中碰到地面也按落地交给停稳确认，结果写明不是按进近落的。
 */
final class StabilizedPilot {

    enum Phase { CLIMB_OUT, CRUISE, APPROACH, DESCENT, LANDED, FAILED }

    /** 巡航速度（每秒几格）。 */
    static final double CRUISE_SPEED = 6;
    /** 离巡航高度还差这么多格以上先不往前走：先升上去再走，不斜着撞山。 */
    static final double CLIMB_GAP = 6;
    /** 进近时速度按剩下的距离给：每格每秒多少。 */
    static final double APPROACH_GAIN = 0.4;
    /** 落地时目标压到地面下这么多，免得悬在地面上一点点。 */
    static final double SINK = 0.3;
    /** 下降中离地这么高以内不再修水平。 */
    static final double NO_CORRECTION_HEIGHT = 2;

    /**
     * 这一刻要的。
     *
     * @param altitude 船底的目标高度（世界 y）
     * @param vx       世界里想要的水平速度，向东（格每秒）
     * @param vz       向南
     */
    record Command(double altitude, double vx, double vz) {}

    private final double startY;
    private final List<String> transitions = new ArrayList<>();
    private Phase phase = Phase.CLIMB_OUT;
    private long phaseStart = Long.MIN_VALUE;
    private long nearSince = Long.MIN_VALUE;
    private int groundedSamples;
    private boolean unplannedTouchdown;
    private String failure = "";

    /** @param startY 起飞时船底的高度 */
    StabilizedPilot(double startY) {
        this.startY = startY;
    }

    Phase phase() {
        return phase;
    }

    List<String> transitions() {
        return List.copyOf(transitions);
    }

    boolean unplannedTouchdown() {
        return unplannedTouchdown;
    }

    String failure() {
        return failure;
    }

    Command tick(PilotSample sample, AirshipPilot.Course course) {
        if (phaseStart == Long.MIN_VALUE) phaseStart = sample.tick();
        groundedSamples = sample.grounded() ? groundedSamples + 1 : 0;
        double y = sample.position().y;
        double distance = sample.flatDistance(course.touchdown());
        boolean stable = sample.tilt() < AirshipPilot.STABLE_TILT && Math.abs(sample.tiltRate()) < AirshipPilot.STABLE_TILT_RATE;
        switch (phase) {
            case CLIMB_OUT -> {
                // 先把目标定在离地约 8 格：高度稳定器把船抬上去，到了、姿态稳了再走。
                if (y >= startY + AirshipPilot.CLEARANCE - 1 && stable) {
                    go(sample, distance < approachRadius(sample) ? Phase.APPROACH : Phase.CRUISE,
                            "离地 " + Math.round(y - startY) + " 格、姿态稳了");
                } else if (sample.tick() - phaseStart >= AirshipPilot.LIFTOFF_TICKS && y < startY + 1.5) {
                    fail(sample, "设了目标高度 " + AirshipPilot.LIFTOFF_TICKS / 20 + " 秒没离地（高度稳定器出力不够，"
                            + "或接的飞控计算机没有能用的推进器）");
                    return hold(y);
                } else {
                    return new Command(startY + AirshipPilot.CLEARANCE, 0, 0);
                }
            }
            case CRUISE -> {
                if (touchedDown(sample)) return hold(y);
                if (distance < approachRadius(sample)) go(sample, Phase.APPROACH, "到了进近范围，离落点 " + Math.round(distance) + " 格");
            }
            case APPROACH -> {
                if (touchedDown(sample)) return hold(y);
                nearSince = distance <= 3 ? (nearSince == Long.MIN_VALUE ? sample.tick() : nearSince) : Long.MIN_VALUE;
                if (distance <= AirshipPilot.OVERHEAD && sample.flatSpeed() < AirshipPilot.OVERHEAD_SPEED) {
                    go(sample, Phase.DESCENT, "到了落点上方、减了速，垂直下降");
                } else if (nearSince != Long.MIN_VALUE && sample.tick() - nearSince >= AirshipPilot.SETTLE_TICKS) {
                    go(sample, Phase.DESCENT, "在落点上方磨了 " + AirshipPilot.SETTLE_TICKS / 20 + " 秒对不准，偏 "
                            + Math.round(distance * 10) / 10.0 + " 格就地下降");
                }
            }
            case DESCENT -> {
                if (groundedSamples >= AirshipPilot.TOUCHDOWN_SAMPLES) {
                    go(sample, Phase.LANDED, "接地了，松开所有键");
                    return hold(y);
                }
            }
            case LANDED, FAILED -> {
                return hold(y);
            }
        }
        return command(sample, course, distance);
    }

    // 各阶段的输出：巡航按巡航速度朝落点走（差得多先升高），进近越近越慢，下降目标每次比此刻低一截、离地远时顺手修水平。
    private Command command(PilotSample sample, AirshipPilot.Course course, double distance) {
        double y = sample.position().y;
        return switch (phase) {
            case CRUISE -> y < course.cruiseAltitude() - CLIMB_GAP ? new Command(course.cruiseAltitude(), 0, 0)
                    : toward(sample, course, course.cruiseAltitude(), CRUISE_SPEED);
            case APPROACH -> toward(sample, course, course.cruiseAltitude(),
                    distance < 0.5 ? 0 : Math.min(CRUISE_SPEED, distance * APPROACH_GAIN));
            case DESCENT -> {
                double height = y - course.touchdown().y;
                double rate = height > AirshipPilot.SLOW_DOWN_HEIGHT ? AirshipPilot.FAST_DESCENT_RATE : AirshipPilot.DESCENT_RATE;
                double target = Math.max(course.touchdown().y - SINK, y - rate);
                double speed = distance > 0.5 && height > NO_CORRECTION_HEIGHT ? Math.min(1, distance * 0.5) : 0;
                yield toward(sample, course, target, speed);
            }
            default -> hold(y);
        };
    }

    // 朝落点水平走：方向是落点减此刻位置，速度给多少就多少。
    private static Command toward(PilotSample sample, AirshipPilot.Course course, double altitude, double speed) {
        Vec3 here = sample.position();
        double dx = course.touchdown().x - here.x;
        double dz = course.touchdown().z - here.z;
        double length = Math.hypot(dx, dz);
        if (length < 1e-6 || speed <= 0) return new Command(altitude, 0, 0);
        return new Command(altitude, dx / length * speed, dz / length * speed);
    }

    // 不动：目标就是此刻的高度，水平不推（耦合器松开就刹）。
    private static Command hold(double y) {
        return new Command(y, 0, 0);
    }

    // 巡航与进近中碰到地面：不是按进近落的，照样交给停稳确认。
    private boolean touchedDown(PilotSample sample) {
        if (groundedSamples < AirshipPilot.TOUCHDOWN_SAMPLES) return false;
        unplannedTouchdown = true;
        go(sample, Phase.LANDED, "还没到落点就碰到了地面，松开所有键");
        return true;
    }

    private static double approachRadius(PilotSample sample) {
        return Math.max(24, sample.flatSpeed() * 3);
    }

    private void go(PilotSample sample, Phase next, String why) {
        if (phase == next) return;
        transitions.add(phase.name().toLowerCase(Locale.ROOT) + " → " + next.name().toLowerCase(Locale.ROOT) + "：" + why);
        phase = next;
        phaseStart = sample.tick();
    }

    private void fail(PilotSample sample, String why) {
        failure = why;
        go(sample, Phase.FAILED, why);
    }
}
