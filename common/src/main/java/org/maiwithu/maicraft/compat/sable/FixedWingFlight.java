// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 开固定翼的手上那一半：每刻量现场交给飞控——滑跑时每 5 刻量一次机头前方畅通多远，下滑、拉平时每 10 刻看跑道上
 * 长没长出挡路的方块，爬升、巡航时每 5 刻看前方地形，高了就抬巡航高度；离目的地 128 格内开始分几刻找落地的跑道
 * （目的地 ±32 → 放宽到 ±96 → 飞回起飞的跑道），同一条 3 次落不下换下一条候选，起飞跑道也 3 次落不下就硬落一次。
 * 被叫先停、时间用完、动力没了：就近（此刻位置 ±128 格）找跑道降，没有够长的挑最长的一片平地迫降，不回起飞跑道。
 * 舵的方向飞着核对，反了这一趟反着按，记下哪个键实际是什么；飞控的输出照操纵表调成开关键。
 * 地面只读已加载的区块，含树叶的高度图（树冠也挡路），没加载的不当畅通。
 */
final class FixedWingFlight {

    /** 离目的地多近开始找落地的跑道（格）。C */
    static final double SEARCH_FROM = 128;
    /** 找跑道的范围：目的地附近、放宽、就近停下（格）；候选隔几格一个。C */
    static final int NEAR_RANGE = 32;
    static final int WIDE_RANGE = 96;
    static final int STOP_RANGE = 128;
    static final int STEP = 8;
    static final int STOP_STEP = 16;
    /** 一刻最多试几条候选跑道：费时的扫描分几刻做。 */
    static final int CHECKS_PER_TICK = 24;
    /** 隔几刻量一次前方、看一次跑道上有没有挡路的。 */
    static final int AHEAD_EVERY = 5;
    static final int BLOCKED_EVERY = 10;
    /** 起飞时往前量多远（格）；船底至少比前方地形高几格。C */
    static final double MAX_AHEAD = 256;
    static final double TERRAIN_GAP = 6;

    private enum Stage { NEAR, WIDE, STOP }

    private final FixedWingPilot pilot;
    private final ControlSurfaceCheck check = new ControlSurfaceCheck();
    private final KeyMixer mixer;
    private final Map<String, String> roles;
    private final Vec3 destination;
    private final String forward;
    private final double span;
    private final double length;
    private final boolean water;
    private final List<String> notes = new ArrayList<>();
    private final Map<String, String> flown = new LinkedHashMap<>();
    private final Set<ControlSurfaceCheck.Axis> told = EnumSet.noneOf(ControlSurfaceCheck.Axis.class);
    private Runways.Search search;
    private Runways.Search previous;
    private Stage stage;
    private Runway departure;
    private double clearAhead = Double.NaN;
    private boolean blocked;
    private Integer startTop;
    private boolean stopping;
    private boolean raised;
    private String landing = "起飞跑道是机头前方的地面；离目的地 128 格内再找落地的跑道";

    /**
     * @param keys    操纵表里的角色 → 键码
     * @param roles   操纵表里的角色 → 输入（打字机:W），舵反了时写明哪个键实际是什么
     * @param forward 车头朝结构自己的哪根水平轴
     * @param start   接手这一刻的样本
     * @param cruise  巡航高度（船底的世界 y）
     * @param water   起飞时浮在水上：前方水面也当跑道
     */
    FixedWingFlight(Map<String, Integer> keys, Map<String, String> roles, SableStructures.Structure ship, String forward,
            PilotSample start, Vec3 destination, double cruise, boolean water) {
        this.mixer = new KeyMixer(keys);
        this.roles = Map.copyOf(roles);
        this.destination = destination;
        this.forward = forward;
        this.water = water;
        int[] size = size(ship, forward);
        this.length = size[0];
        this.span = size[1];
        FixedWingPilot.Controls controls = new FixedWingPilot.Controls(has("pitch_up", "pitch_down"),
                has("roll_left", "roll_right"), has("yaw_left", "yaw_right"), has("steer_left", "steer_right"), has("brake"));
        this.pilot = new FixedWingPilot(controls, length / 2.0, span, cruise, destination, !start.grounded());
    }

    /** 这一刻该按着哪些键。 */
    Set<Integer> fly(ClientLevel level, SableStructures.Structure ship, PilotSample sample, long now) {
        LandingSites.Ground ground = ground(level);
        Vec3 at = sample.position();
        Integer below = ground.top((int) Math.floor(at.x), (int) Math.floor(at.z));
        if (startTop == null) startTop = below != null ? below : (int) Math.round(at.y);
        watch(sample, now, ground);
        searchRunway(sample, ground);
        FixedWingPilot.Attitude attitude = attitude(ship.pose(), forward);
        FixedWingPilot.Command command = pilot.tick(sample, attitude,
                new FixedWingPilot.Situation(clearAhead, below == null ? Double.NaN : below, blocked));
        if (departure == null && pilot.leftGround() && pilot.takeoffStart() != null) {
            Vec3 start = pilot.takeoffStart();
            departure = new Runway(new Vec3(start.x, startTop, start.z), pilot.takeoffHeading(),
                    Math.max(Runways.MIN_LENGTH, pilot.rolled() + (Double.isNaN(clearAhead) ? 0 : clearAhead)), span + 4, false);
        }
        if (pilot.wantsNextRunway() && search == null) nextRunway(sample);
        if (pilot.powerLost() && !stopping) {
            notes.add("动力没了，就近找跑道迫降");
            stop(sample, ground);
        }
        checkControls(command, sample);
        return mixer.tick(now, demands(command));
    }

    // 隔几刻量一次现场：滑跑看前方畅通多远，下滑、拉平看跑道上有没有挡路的，爬升、巡航看前方地形。
    private void watch(PilotSample sample, long now, LandingSites.Ground ground) {
        FixedWingPilot.Phase phase = pilot.phase();
        if (phase == FixedWingPilot.Phase.TAKEOFF_ROLL && (now % AHEAD_EVERY == 0 || Double.isNaN(clearAhead))) {
            double heading = pilot.takeoffStart() != null ? pilot.takeoffHeading() : sample.heading();
            clearAhead = Runways.clearAhead(sample.position(), heading, span + 2, MAX_AHEAD, startTop, water, ground);
        }
        boolean touchingDown = phase == FixedWingPilot.Phase.DESCENT || phase == FixedWingPilot.Phase.FLARE;
        if (!touchingDown || pilot.runway() == null) {
            blocked = false;
        } else if (now % BLOCKED_EVERY == 0) {
            blocked = Runways.blocked(pilot.runway(), ground);
        }
        if ((phase == FixedWingPilot.Phase.CLIMB_OUT || phase == FixedWingPilot.Phase.CRUISE) && now % AHEAD_EVERY == 0) {
            Vec3 toward = pilot.runway() != null ? pilot.runway().at(-Runways.APPROACH_OUT, 0) : destination;
            OptionalDouble highest = LandingSites.highestAhead(sample.position(), toward, span / 2,
                    Math.max(48, sample.flatSpeed() * 5), ground);
            if (highest.isPresent() && highest.getAsDouble() + TERRAIN_GAP > pilot.cruise()) {
                pilot.raiseCruise(highest.getAsDouble() + TERRAIN_GAP);
                if (!raised) notes.add("前方地形高，巡航高度抬到 " + Math.round(pilot.cruise()));
                raised = true;
            }
        }
    }

    // 找跑道：离目的地 128 格内开始；一刻试几条，找到就交给飞控，试完了按阶段放宽或回起飞跑道。
    private void searchRunway(PilotSample sample, LandingSites.Ground ground) {
        if (search == null && stage == null && !stopping && pilot.runway() == null && pilot.leftGround()
                && sample.flatDistance(destination) < SEARCH_FROM) {
            start(Stage.NEAR, destination, sample, ground);
        }
        if (search == null) return;
        Optional<Runway> found = search.advance(CHECKS_PER_TICK);
        if (found.isPresent()) {
            use(found.get(), stage != Stage.STOP);
            previous = search;
            search = null;
            return;
        }
        if (!search.exhausted()) return;
        Runways.Search done = search;
        search = null;
        switch (stage) {
            case NEAR -> start(Stage.WIDE, destination, sample, ground);
            case WIDE -> {
                notes.add("目的地附近 ±" + WIDE_RANGE + " 格没找到够长够平的跑道，飞回起飞的跑道");
                landDeparture(false);
            }
            case STOP -> {
                Optional<Runway> longest = done.longest();
                if (longest.isPresent()) {
                    notes.add("附近没有够长的跑道，迫降在最长的一片平地（" + Math.round(longest.get().length()) + " 格）");
                    use(longest.get(), false);
                } else {
                    notes.add("附近 ±" + STOP_RANGE + " 格没有能降的平地，绕圈等");
                }
            }
        }
    }

    private void start(Stage next, Vec3 around, PilotSample sample, LandingSites.Ground ground) {
        stage = next;
        int range = next == Stage.NEAR ? NEAR_RANGE : next == Stage.WIDE ? WIDE_RANGE : STOP_RANGE;
        double measured = Double.isNaN(pilot.liftoffSpeed()) ? Double.NaN : pilot.rolled();
        search = new Runways.Search(around, range, next == Stage.STOP ? STOP_STEP : STEP, sample.headingTo(around),
                Runways.needed(measured, length), span + 4, next == Stage.STOP, ground);
    }

    // 这条跑道连着 3 次落不下：接着试下一条候选；候选试完、或已经是起飞跑道、迫降那片，照规矩往下走。
    private void nextRunway(PilotSample sample) {
        Runway current = pilot.runway();
        if (current == departure || (current != null && current.forced())) {
            notes.add((current == departure ? "起飞跑道" : "迫降的那片地") + "也连着 " + FixedWingPilot.ATTEMPTS
                    + " 次落不下，这次照最后那样硬落，不再复飞");
            pilot.land(current, false, true);
            return;
        }
        if (previous != null && !previous.exhausted() && stage != Stage.STOP) {
            notes.add("这条跑道连着 " + FixedWingPilot.ATTEMPTS + " 次落不下，换下一条候选");
            search = previous;
            previous = null;
            pilot.orbit(destination);
            return;
        }
        landDeparture(false);
    }

    private void use(Runway runway, boolean forDestination) {
        pilot.land(runway, forDestination, false);
        landing = (forDestination ? "落在目的地附近够长够平的一条：" : runway.forced() ? "迫降在最长的一片平地：" : "就近落在：")
                + runway.describe();
    }

    private void landDeparture(boolean committed) {
        if (departure == null) {
            notes.add("没有起飞跑道可回（空中接手的），绕圈等");
            return;
        }
        pilot.land(departure, false, committed);
        landing = "回起飞的跑道：" + departure.describe();
    }

    /** 被叫先停、时间用完、动力没了：滑跑中刹住；在天上就近找跑道（原来挑的就在附近就照落），不回起飞跑道。 */
    void stop(ClientLevel level, PilotSample sample) {
        stop(sample, ground(level));
    }

    // 还在地上（滑跑、落地滑跑、停住了）交给飞控自己刹；在天上原来挑的跑道就在附近就照落，远了先绕圈、就近找。
    private void stop(PilotSample sample, LandingSites.Ground ground) {
        if (stopping) return;
        stopping = true;
        pilot.stop();
        if (!pilot.leftGround() || pilot.phase() == FixedWingPilot.Phase.ROLLOUT
                || pilot.phase() == FixedWingPilot.Phase.LANDED || pilot.phase() == FixedWingPilot.Phase.FAILED) {
            return;
        }
        Runway current = pilot.runway();
        if (current != null && sample.flatDistance(current.threshold()) <= STOP_RANGE) {
            landing = "就近落在原来挑的那条：" + current.describe();
            return;
        }
        pilot.orbit(sample.position());
        start(Stage.STOP, sample.position(), sample, ground);
    }

    // 舵的方向飞着核对：反了这一趟反着按，写明哪个键实际是什么（操纵表里推出来、试过的那行改成试过，LLM 定的不改）。
    private void checkControls(FixedWingPilot.Command command, PilotSample sample) {
        check.observe(ControlSurfaceCheck.Axis.PITCH, command.pitch(), pilot.pitchRate());
        check.observe(ControlSurfaceCheck.Axis.ROLL, command.roll(), pilot.rollRate());
        check.observe(ControlSurfaceCheck.Axis.YAW, command.yaw(), sample.turnRate());
        tell(ControlSurfaceCheck.Axis.PITCH, "pitch_up", "pitch_down", "机头往下", "机头往上");
        tell(ControlSurfaceCheck.Axis.ROLL, "roll_right", "roll_left", "往左压", "往右压");
        tell(ControlSurfaceCheck.Axis.YAW, "yaw_right", "yaw_left", "往左转", "往右转");
    }

    private void tell(ControlSurfaceCheck.Axis axis, String plus, String minus, String plusActually, String minusActually) {
        if (!check.reversed(axis) || !told.add(axis)) return;
        for (String[] pair : new String[][]{{plus, minus, plusActually}, {minus, plus, minusActually}}) {
            String control = roles.get(pair[0]);
            if (control == null) continue;
            flown.put(control, pair[1]);
            notes.add(control + " 操纵表里是 " + pair[0] + "，飞着按下去" + pair[2] + "，这一趟反着用");
        }
    }

    // 飞控的输出换成每个角色要按多少：舵反了的那一对正负对调。
    private Map<String, Double> demands(FixedWingPilot.Command command) {
        double pitch = check.reversed(ControlSurfaceCheck.Axis.PITCH) ? -command.pitch() : command.pitch();
        double roll = check.reversed(ControlSurfaceCheck.Axis.ROLL) ? -command.roll() : command.roll();
        double yaw = check.reversed(ControlSurfaceCheck.Axis.YAW) ? -command.yaw() : command.yaw();
        Map<String, Double> demands = new HashMap<>();
        demands.put("power", Math.max(0, command.power()));
        demands.put("reverse", Math.max(0, -command.power()));
        demands.put("pitch_up", Math.max(0, pitch));
        demands.put("pitch_down", Math.max(0, -pitch));
        demands.put("roll_right", Math.max(0, roll));
        demands.put("roll_left", Math.max(0, -roll));
        demands.put("yaw_right", Math.max(0, yaw));
        demands.put("yaw_left", Math.max(0, -yaw));
        demands.put("steer_right", Math.max(0, command.steer()));
        demands.put("steer_left", Math.max(0, -command.steer()));
        demands.put("brake", command.brake() ? 1.0 : 0.0);
        return demands;
    }

    FixedWingPilot.Phase phase() {
        return pilot.phase();
    }

    String failure() {
        return pilot.failure();
    }

    List<String> transitions() {
        return pilot.transitions();
    }

    /** 途中的事：飞控记的与这里记的（找跑道、舵反了、地形抬高）。 */
    List<String> notes() {
        List<String> all = new ArrayList<>(pilot.notes());
        all.addAll(notes);
        if (pilot.unplannedTouchdown()) all.add("不是按进近落的：还没到跑道就碰到了地面");
        if (pilot.overrun() > 1) all.add("落地滑跑冲出跑道 " + Math.round(pilot.overrun()) + " 格");
        return all;
    }

    String landing() {
        return landing;
    }

    boolean arrived() {
        return pilot.arrived();
    }

    int goArounds() {
        return pilot.goArounds();
    }

    /** 起飞的经过：滑了几格、离地速度（没离地、空中接手为 null）、离没离地。 */
    double rolled() {
        return pilot.rolled();
    }

    Double liftoffSpeed() {
        return Double.isNaN(pilot.liftoffSpeed()) ? null : Math.round(pilot.liftoffSpeed() * 10) / 10.0;
    }

    boolean leftGround() {
        return pilot.leftGround();
    }

    /** 飞着看出来的：输入 → 实际的角色（舵接反了的那几个）。 */
    Map<String, String> flown() {
        return Map.copyOf(flown);
    }

    private boolean has(String... wanted) {
        for (String role : wanted) {
            if (roles.containsKey(role)) return true;
        }
        return false;
    }

    // 机身长（沿车头那根轴）与翼展（另一根水平轴），按结构的方块范围。
    private static int[] size(SableStructures.Structure ship, String forward) {
        int x = ship.localMax().getX() - ship.localMin().getX() + 1;
        int z = ship.localMax().getZ() - ship.localMin().getZ() + 1;
        return forward.endsWith("x") ? new int[]{x, z} : new int[]{z, x};
    }

    /**
     * 姿态：机头抬起多少度（抬头为正），右翼往下多少度（右压为正）——按车头那根轴、它右手的那根轴、竖轴此刻在世界里朝哪算。
     */
    static FixedWingPilot.Attitude attitude(SableStructures.Pose pose, String forward) {
        double sign = forward.startsWith("-") ? -1 : 1;
        Vec3 noseLocal = forward.endsWith("x") ? new Vec3(sign, 0, 0) : new Vec3(0, 0, sign);
        Vec3 nose = turned(pose, noseLocal);
        Vec3 up = turned(pose, new Vec3(0, 1, 0));
        Vec3 right = turned(pose, noseLocal.cross(new Vec3(0, 1, 0)));
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, nose.y))));
        double roll = Math.toDegrees(Math.atan2(-right.y, up.y));
        return new FixedWingPilot.Attitude(pitch, roll);
    }

    // 结构自己的一个方向此刻在世界里朝哪。
    private static Vec3 turned(SableStructures.Pose pose, Vec3 local) {
        return pose.toWorld(pose.rotationPoint().add(local)).subtract(pose.position()).normalize();
    }

    // 读世界的地面：只读已经加载的区块；用含树叶的高度图（树冠也挡路），没加载答不知道。
    private static LandingSites.Ground ground(ClientLevel level) {
        return new LandingSites.Ground() {
            @Override public Integer top(int x, int z) {
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return null;
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            }

            @Override public boolean liquid(int x, int z) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                return !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
            }
        };
    }
}
