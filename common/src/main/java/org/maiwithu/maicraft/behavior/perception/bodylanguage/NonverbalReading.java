// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.perception.DirectionWords;

/**
 * 从别的玩家的采样里认肢体动作：蹲起、点头、摇头、原地跳、拿出东西、走开又回头，以及新掉落物是不是他扔的。纯函数。
 *
 * <p>客户端拿到的是服务端同步来的姿势与视角：视角每 2 刻一次、按一圈 256 份取整，所以角度的门槛都留了余量。
 * 下面的数都是命名常量，实机按观感调。
 */
final class NonverbalReading {

    /** 蹲着不到这么久（刻）就站起来算蹲起一下；一直蹲着的不算，观察里写"蹲着"。 */
    static final int CROUCH_MAX_TICKS = 20;
    /** 点头、摇头的来回之间最多隔多久（刻）：1.5 秒。 */
    static final int TURN_GAP_TICKS = 30;
    /** 点头每一下至少点多少度，期间朝向最多偏多少度。 */
    static final float NOD_DEGREES = 15f;
    static final float NOD_YAW_DRIFT = 15f;
    /** 摇头每一下至少晃多少度，期间俯仰最多偏多少度。 */
    static final float SHAKE_DEGREES = 20f;
    static final float SHAKE_PITCH_DRIFT = 15f;
    /** 点头、摇头时人挪了不到这么远（格）才算站着没动。 */
    static final double STILL_BLOCKS = 0.5;
    /** 原地跳：至少升高这么多（格），起跳到落地水平挪动不到这么远（格）。 */
    static final double JUMP_RISE = 0.8;
    static final double JUMP_DRIFT = 1.0;
    /** 换手在这么多刻内又换，只记最后拿着的。 */
    static final int HOLD_SETTLE_TICKS = 10;
    /** 走开又回头：一段肢体动作之后这么久（刻）内走开这么远（格），又把视线转回角色。 */
    static final int WALK_WITHIN_TICKS = 100;
    static final double WALK_AWAY_BLOCKS = 3;
    /** 扔东西：掉落物生成在他眼睛往下这么多（格），出手速度约每刻这么多格，生成点误差按 1 格算。 */
    static final double TOSS_DROP_BELOW_EYE = 0.3;
    static final double TOSS_SPEED = 0.3;
    static final double TOSS_MISS_BLOCKS = 1.0;

    private NonverbalReading() {}

    /** afterTick 之后认出来的蹲起、点头、摇头、原地跳、拿出东西，按时间先后。 */
    static List<NonverbalAct> read(List<PlayerPose> poses, long afterTick) {
        List<NonverbalAct> acts = new ArrayList<>();
        acts.addAll(crouches(poses, afterTick));
        acts.addAll(headTurns(poses, afterTick));
        acts.addAll(jumps(poses, afterTick));
        acts.addAll(holds(poses, afterTick));
        acts.sort((a, b) -> Long.compare(a.tick(), b.tick()));
        return acts;
    }

    // 按下潜行又松开、蹲着不到 1 秒算一下，记在站起来那刻；第一份采样就蹲着的不知道什么时候蹲下的，不算。
    static List<NonverbalAct> crouches(List<PlayerPose> poses, long afterTick) {
        List<NonverbalAct> acts = new ArrayList<>();
        Long down = null;
        boolean toward = false;
        boolean was = true;
        for (int i = 0; i < poses.size(); i++) {
            PlayerPose pose = poses.get(i);
            if (i > 0 && pose.crouching() && !was) {
                down = pose.tick();
                toward = pose.lookingAtMe();
            } else if (pose.crouching()) {
                toward |= pose.lookingAtMe();
            } else if (was && down != null) {
                if (pose.tick() - down <= CROUCH_MAX_TICKS && pose.tick() > afterTick) {
                    acts.add(new NonverbalAct(NonverbalAct.Kind.CROUCH, pose.tick(), 1,
                            toward || pose.lookingAtMe(), ""));
                }
                down = null;
            }
            was = pose.crouching();
        }
        return acts;
    }

    /** afterTick 之后的点头与摇头：一串来回认一次，写几下。 */
    static List<NonverbalAct> headTurns(List<PlayerPose> poses, long afterTick) {
        List<PlayerPose> recent = poses.stream().filter(pose -> pose.tick() > afterTick).toList();
        List<NonverbalAct> acts = new ArrayList<>();
        if (recent.size() < 4) return acts;
        double[] pitch = new double[recent.size()];
        double[] yaw = new double[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            pitch[i] = recent.get(i).pitch();
            // 朝向跨过 ±180 度时接着累加，不让一次转身看成来回晃。
            yaw[i] = i == 0 ? recent.get(i).headYaw()
                    : yaw[i - 1] + Mth.wrapDegrees(recent.get(i).headYaw() - recent.get(i - 1).headYaw());
        }
        acts.addAll(swings(recent, pitch, yaw, NOD_DEGREES, NOD_YAW_DRIFT, NonverbalAct.Kind.NOD));
        acts.addAll(swings(recent, yaw, pitch, SHAKE_DEGREES, SHAKE_PITCH_DRIFT, NonverbalAct.Kind.SHAKE_HEAD));
        return acts;
    }

    // 一个角度的来回：按门槛找出转折点，转折点之间隔不过 1.5 秒的连成一串；一串里至少来回反向两次、
    // 另一个角度没怎么动、人没走动，算点头（或摇头），每一下是去一回。
    private static List<NonverbalAct> swings(List<PlayerPose> poses, double[] angle, double[] other,
            double threshold, double otherDrift, NonverbalAct.Kind kind) {
        List<Integer> points = turningPoints(angle, threshold);
        List<NonverbalAct> acts = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= points.size(); i++) {
            boolean broken = i == points.size()
                    || poses.get(points.get(i)).tick() - poses.get(points.get(i - 1)).tick() > TURN_GAP_TICKS;
            if (!broken) continue;
            List<Integer> run = points.subList(start, i);
            start = i;
            if (run.size() < 4) continue;
            int from = run.get(0);
            int to = run.get(run.size() - 1);
            if (range(other, from, to) >= otherDrift || moved(poses, from, to) >= STILL_BLOCKS) continue;
            boolean toward = false;
            for (int k = from; k <= to; k++) toward |= poses.get(k).lookingAtMe();
            int halfSwings = run.size() - 1;
            acts.add(new NonverbalAct(kind, poses.get(to).tick(), (halfSwings + 1) / 2, toward, ""));
        }
        return acts;
    }

    /**
     * 转折点（按下标）：从第一份起，角度离当前的最高（最低）点退回超过门槛就记下那个点、换方向；
     * 最后一段走过门槛但还没退回的，终点也记上。相邻两点之间就是一下超过门槛的去或回。
     */
    static List<Integer> turningPoints(double[] values, double threshold) {
        List<Integer> points = new ArrayList<>();
        if (values.length == 0) return points;
        int low = 0;
        int high = 0;
        int direction = 0;
        int extreme = 0;
        for (int i = 1; i < values.length; i++) {
            double value = values[i];
            if (direction == 0) {
                if (value > values[high]) high = i;
                if (value < values[low]) low = i;
                if (values[high] - values[low] >= threshold) {
                    direction = high > low ? 1 : -1;
                    points.add(direction == 1 ? low : high);
                    extreme = direction == 1 ? high : low;
                }
            } else if (direction == 1) {
                if (value >= values[extreme]) {
                    extreme = i;
                } else if (values[extreme] - value >= threshold) {
                    points.add(extreme);
                    direction = -1;
                    extreme = i;
                }
            } else if (value <= values[extreme]) {
                extreme = i;
            } else if (value - values[extreme] >= threshold) {
                points.add(extreme);
                direction = 1;
                extreme = i;
            }
        }
        if (direction != 0) points.add(extreme);
        return points;
    }

    // 离地升高 0.8 格以上再落地、水平挪动不到 1 格：原地跳一下，记在落地那刻。走着跳、从边上掉下去的不算。
    static List<NonverbalAct> jumps(List<PlayerPose> poses, long afterTick) {
        List<NonverbalAct> acts = new ArrayList<>();
        PlayerPose lift = null;
        double peak = 0;
        boolean toward = false;
        for (int i = 1; i < poses.size(); i++) {
            PlayerPose previous = poses.get(i - 1);
            PlayerPose pose = poses.get(i);
            if (!pose.onGround() && previous.onGround()) {
                lift = previous;
                peak = pose.y();
                toward = pose.lookingAtMe() || previous.lookingAtMe();
            } else if (!pose.onGround() && lift != null) {
                peak = Math.max(peak, pose.y());
                toward |= pose.lookingAtMe();
            } else if (pose.onGround() && lift != null) {
                double drift = Math.hypot(pose.x() - lift.x(), pose.z() - lift.z());
                if (peak - lift.y() >= JUMP_RISE && drift < JUMP_DRIFT && pose.tick() > afterTick) {
                    acts.add(new NonverbalAct(NonverbalAct.Kind.JUMP, pose.tick(), 1, toward, ""));
                }
                lift = null;
            }
        }
        return acts;
    }

    // 主手换了东西记一下；半秒内接着换（滚轮翻过去）只记最后拿着的。
    static List<NonverbalAct> holds(List<PlayerPose> poses, long afterTick) {
        List<NonverbalAct> acts = new ArrayList<>();
        for (int i = 1; i < poses.size(); i++) {
            PlayerPose pose = poses.get(i);
            if (pose.mainHand().equals(poses.get(i - 1).mainHand()) || pose.tick() <= afterTick) continue;
            NonverbalAct act = new NonverbalAct(NonverbalAct.Kind.HOLD, pose.tick(), 1, pose.lookingAtMe(),
                    pose.mainHand());
            if (!acts.isEmpty() && pose.tick() - acts.get(acts.size() - 1).tick() <= HOLD_SETTLE_TICKS) {
                acts.set(acts.size() - 1, act);
            } else {
                acts.add(act);
            }
        }
        return acts;
    }

    /**
     * 一段肢体动作在 fromTick 结束后，5 秒内他走开 3 格以上、先把视线移开又转回角色：记在转回来那刻，
     * 细节写往哪边走了几格。没有为空。
     */
    static Optional<NonverbalAct> walkedBack(List<PlayerPose> poses, long fromTick) {
        PlayerPose anchor = null;
        for (PlayerPose pose : poses) {
            if (pose.tick() <= fromTick) anchor = pose;
        }
        if (anchor == null) return Optional.empty();
        boolean lookedAway = false;
        for (PlayerPose pose : poses) {
            if (pose.tick() <= fromTick || pose.tick() - fromTick > WALK_WITHIN_TICKS) continue;
            double dx = pose.x() - anchor.x();
            double dz = pose.z() - anchor.z();
            if (!pose.lookingAtMe()) {
                lookedAway = true;
            } else if (lookedAway && Math.hypot(dx, dz) >= WALK_AWAY_BLOCKS) {
                String detail = "往" + DirectionWords.compassOf(dx, dz) + "走了 " + Math.round(Math.hypot(dx, dz))
                        + " 格，又回头看着你";
                return Optional.of(new NonverbalAct(NonverbalAct.Kind.WALK, pose.tick(), 1, true, detail));
            }
        }
        return Optional.empty();
    }

    /** 他此刻是不是还在走开的路上：一段肢体动作之后 5 秒内挪开了 1 格以上，还没回头。 */
    static boolean walkingAway(List<PlayerPose> poses, long fromTick, long now) {
        if (poses.isEmpty() || now - fromTick > WALK_WITHIN_TICKS) return false;
        PlayerPose anchor = null;
        for (PlayerPose pose : poses) {
            if (pose.tick() <= fromTick) anchor = pose;
        }
        PlayerPose last = poses.get(poses.size() - 1);
        return anchor != null && Math.hypot(last.x() - anchor.x(), last.z() - anchor.z()) >= 1.0;
    }

    /**
     * 新掉落物是不是这个人扔的：原版扔东西时掉落物生成在扔的人眼睛往下 0.3 格、初速约每刻 0.3 格顺着视线；
     * 扔的人服务端不同步，只能这样推。按生成后过了几刻往前推出该在的位置，误差 1 格内、
     * 飞的方向大致顺着他的视线才算。返回差了多远（格），不像他扔的为空。
     */
    static Optional<Double> tossMiss(Vec3 eye, Vec3 look, Vec3 itemAt, Vec3 itemVelocity, int age) {
        Vec3 dir = look.normalize();
        Vec3 expected = eye.subtract(0, TOSS_DROP_BELOW_EYE, 0).add(dir.scale(TOSS_SPEED * age));
        double miss = itemAt.distanceTo(expected);
        if (miss > TOSS_MISS_BLOCKS + 0.2 * age) return Optional.empty();
        Vec3 flat = new Vec3(itemVelocity.x, 0, itemVelocity.z);
        Vec3 flatLook = new Vec3(dir.x, 0, dir.z);
        // 朝正下方扔（视线几乎竖直）时水平方向说不清，只看生成点。
        if (flat.length() > 0.05 && flatLook.length() > 0.2 && flat.normalize().dot(flatLook.normalize()) < 0.5) {
            return Optional.empty();
        }
        return Optional.of(miss);
    }

    private static double range(double[] values, int from, int to) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        return max - min;
    }

    private static double moved(List<PlayerPose> poses, int from, int to) {
        double furthest = 0;
        PlayerPose start = poses.get(from);
        for (int i = from; i <= to; i++) {
            furthest = Math.max(furthest, Math.hypot(poses.get(i).x() - start.x(), poses.get(i).z() - start.z()));
        }
        return furthest;
    }
}
