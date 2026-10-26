// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;

/**
 * 用飞行稳定器开飞艇的手上那一半：开之前打开没开的稳定器、给最大水平速度还是 0 的线速度耦合器设上速度，读回核对；
 * 飞的时候把飞控要的船底高度换成高度稳定器认的高度（结构转动点的世界 y）设下去——差得够多、隔够久才改，
 * 改了隔一会儿读回核对，60 刻还没收下就照实停；把想要的水平速度换到机体轴上，按耦合器的最大速度折成 ±x、±z 四个键
 * 各按多少，交给 4 刻一组的调法按下去。改设置和在稳定器界面里保存一样，只在坐着够得着（8 格内）时发得出去。
 */
final class StabilizedFlight {

    /** 线速度耦合器最大水平目标速度是 0 时设成这个（格每秒），和按键开飞艇的巡航速度一样。 */
    static final double SPEED = 6;
    /** 改了设置等读回最多几刻。 */
    static final int CONFIRM_TICKS = 60;
    /** 目标高度最多隔几刻改一次、差多少格才改；隔几刻读一次稳定器。 */
    static final int SEND_EVERY = 10;
    static final double SEND_STEP = 0.5;
    /** 坐着离稳定器多远还发得出去：服务器只认 8 格内，留半格。 */
    static final double REACH = 7.5;

    private final String structureId;
    private final VehicleDriver.Stabilized plan;
    private final KeyMixer mixer;
    private final List<String> notes = new ArrayList<>();
    private FlightStabilizers.Setup setup;
    private long readAt = Long.MIN_VALUE;
    private long primedAt = Long.MIN_VALUE;
    private boolean primed;
    private double sent = Double.NaN;
    private long sentAt = Long.MIN_VALUE;
    private long unconfirmedSince = Long.MIN_VALUE;
    private String problem;

    /** @param keys 沿机体的方向（+x / -x / +z / -z）→ 键码 */
    StabilizedFlight(String structureId, VehicleDriver.Stabilized plan, Map<String, Integer> keys) {
        this.structureId = structureId;
        this.plan = plan;
        this.mixer = new KeyMixer(keys);
    }

    /** 要改设置的稳定器坐着够不够得着；够不着说哪个离多远，都够得着为 null。 */
    static String outOfReach(VehicleDriver.Stabilized plan, SableStructures.Structure ship, Vec3 eye) {
        List<BlockPos> parts = new ArrayList<>(plan.altitudeHolds());
        parts.addAll(plan.switchOn());
        parts.addAll(plan.slowCouplers());
        for (BlockPos part : parts) {
            double distance = ship.pose().toWorld(Vec3.atCenterOf(part)).distanceTo(eye);
            if (distance > REACH) {
                return (plan.altitudeHolds().contains(part) ? "高度稳定器" : "稳定器") + "离驾驶位 "
                        + Math.round(distance * 10) / 10.0 + " 格，超过 8 格，坐着改不了它的设置";
            }
        }
        return null;
    }

    /** 开之前的准备做完了（打开、设速度都读回对了）。 */
    boolean primed() {
        return primed;
    }

    /** 这一趟改了什么设置，一条一句。 */
    List<String> notes() {
        return List.copyOf(notes);
    }

    /** 飞的时候高度稳定器不收目标高度的原因；没有为 null。 */
    String problem() {
        return problem;
    }

    /** 最后设下去的目标高度（转动点的世界 y）；还没设为 NaN。 */
    double target() {
        return sent;
    }

    /**
     * 开之前：打开没开的稳定器、给最大水平速度是 0 的耦合器设上速度，再读回核对。
     *
     * @return 还在等为 null；好了为空串；做不下去为原因
     */
    String prime(long now) {
        if (primed) return "";
        if (primedAt == Long.MIN_VALUE) {
            primedAt = now;
            for (BlockPos part : plan.switchOn()) plan.stabilizers().switchOn(structureId, part);
            for (BlockPos part : plan.slowCouplers()) plan.stabilizers().setHorizontalSpeed(structureId, part, SPEED);
            if (!plan.switchOn().isEmpty()) notes.add("打开了 " + plan.switchOn().size() + " 个没开的稳定器");
            if (!plan.slowCouplers().isEmpty()) notes.add("线速度耦合器的最大水平速度是 0，设成每秒 " + (int) SPEED + " 格");
        }
        refresh(now, true);
        boolean on = plan.switchOn().stream().allMatch(part -> part(part).map(FlightStabilizers.Stabilizer::on).orElse(false));
        boolean fast = plan.slowCouplers().stream().allMatch(part -> part(part)
                .map(found -> found.maxX() > 0 && found.maxZ() > 0).orElse(false));
        if (on && fast) {
            primed = true;
            return "";
        }
        if (now - primedAt < CONFIRM_TICKS) return null;
        return (on ? "" : "打开稳定器") + (on || fast ? "" : "、") + (fast ? "" : "设线速度耦合器的速度")
                + "后 " + CONFIRM_TICKS / 20 + " 秒读回还没变";
    }

    /**
     * 飞的这一刻：目标高度差得够多、隔够久就设下去，读回核对；水平速度折成四个方向的键。
     *
     * @return 这一刻要按住的键
     */
    Set<Integer> fly(long now, StabilizedPilot.Command command, SableStructures.Structure ship) {
        refresh(now, false);
        SableStructures.Pose pose = ship.pose();
        double target = command.altitude() + (pose.position().y - ship.worldBounds().minY);
        if (unconfirmedSince != Long.MIN_VALUE && confirmed()) unconfirmedSince = Long.MIN_VALUE;
        if (unconfirmedSince != Long.MIN_VALUE && now - unconfirmedSince > CONFIRM_TICKS) {
            problem = "高度稳定器 " + CONFIRM_TICKS / 20 + " 秒没收下目标高度（离得超过 8 格，或被红石切了模式）";
        }
        // 还没设过就马上设；设过的差够半格、隔够 10 刻才改（sentAt 起始是最小值，不能拿来相减）。
        if (Double.isNaN(sent) || (Math.abs(target - sent) >= SEND_STEP && now - sentAt >= SEND_EVERY)) {
            for (BlockPos hold : plan.altitudeHolds()) {
                if (!plan.stabilizers().holdAltitude(structureId, hold, target)) problem = "高度稳定器那一格读不到了，设不了目标高度";
            }
            sent = target;
            sentAt = now;
            if (unconfirmedSince == Long.MIN_VALUE) unconfirmedSince = now;
        }
        Vec3 origin = pose.toLocal(pose.position());
        Vec3 body = pose.toLocal(pose.position().add(command.vx(), 0, command.vz())).subtract(origin);
        return mixer.tick(now, demands(body.x, body.z, maxSpeed(true), maxSpeed(false)));
    }

    /**
     * 沿机体的水平速度折成四个方向的键各按多少（0～1）：按耦合器沿那根轴的最大速度折算，
     * 有一根超了就两根一起按比例缩，保住方向。耦合器那根轴的最大速度是 0 时那根轴不按。
     */
    static Map<String, Double> demands(double bodyX, double bodyZ, double maxX, double maxZ) {
        double x = maxX > 0 ? bodyX / maxX : 0;
        double z = maxZ > 0 ? bodyZ / maxZ : 0;
        double over = Math.max(1, Math.max(Math.abs(x), Math.abs(z)));
        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put(x >= 0 ? "+x" : "-x", Math.abs(x) / over);
        demands.put(z >= 0 ? "+z" : "-z", Math.abs(z) / over);
        return demands;
    }

    // 隔一会儿读一次稳定器（刚改了设置、开之前每次都读）。
    private void refresh(long now, boolean always) {
        if (!always && setup != null && now - readAt < SEND_EVERY) return;
        plan.stabilizers().read(structureId).ifPresent(found -> setup = found);
        readAt = now;
    }

    // 读回：要设的高度稳定器都在保持设定高度、目标就是最后设下去的那个。
    private boolean confirmed() {
        return setup != null && plan.altitudeHolds().stream().allMatch(hold -> part(hold)
                .map(found -> found.mode().equals("hold_set") && Math.abs(found.target() - sent) < 0.05).orElse(false));
    }

    // 耦合器沿机体 x 或 z 的最大速度：几个耦合器取小的那个；读不到按开之前设的。
    private double maxSpeed(boolean alongX) {
        if (setup == null) return SPEED;
        return setup.stabilizers().stream().filter(part -> part.kind().equals(FlightStabilizers.VELOCITY_COUPLER) && part.working())
                .mapToDouble(part -> alongX ? part.maxX() : part.maxZ()).min().orElse(SPEED);
    }

    private Optional<FlightStabilizers.Stabilizer> part(BlockPos at) {
        return setup == null ? Optional.empty()
                : setup.stabilizers().stream().filter(found -> found.local().equals(at)).findFirst();
    }
}
