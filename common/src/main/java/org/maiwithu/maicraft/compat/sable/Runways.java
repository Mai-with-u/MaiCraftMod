// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.world.phys.Vec3;

/**
 * 固定翼的跑道（纯函数，地面怎么读交进来，只读已加载的，没加载的不当畅通）：
 * 起飞量机头前方畅通多远；降落在一点附近每隔几格一个候选、每个试八个朝向，挑一长条够平的地，
 * 跑道头往外的下滑线下面也要空着；附近没有够长的，迫降时挑最长的一片平地。找跑道分几刻做，一刻只试几条。
 */
final class Runways {

    /** 下滑线：前进 10 格降 1 格（约 6°）。C */
    static final double GLIDE = 0.1;
    /** 跑道头往外看多远的下滑线（格），下滑线下面至少空几格。C：进近时树、山挡着不行 */
    static final double APPROACH_OUT = 120;
    static final int UNDER_GLIDE = 3;
    /** 一条地面的高低差最多几格；逐格量（一格厚的墙、一根树干也不漏）。C */
    static final int ROUGHNESS = 1;
    static final int SAMPLE = 1;
    /** 落地跑道最短多少格；没有实测滑跑距离时按多长。C */
    static final double MIN_LENGTH = 48;
    static final double UNMEASURED_LENGTH = 96;
    /** 迫降的平地最短多少格才算一片：再短落不下。C */
    static final double MIN_FORCED = 16;

    private Runways() {}

    /** 落地要多长：这一趟实测滑跑距离 × 2 再加机身长，至少 48 格；没有实测（空中接手）按 96 格。 */
    static double needed(double rolled, double bodyLength) {
        return Double.isNaN(rolled) ? UNMEASURED_LENGTH : Math.max(MIN_LENGTH, rolled * 2 + bodyLength);
    }

    /**
     * 从一点沿朝向往前一长条的地面：都加载着、不是液体、高低差不超过 1 格，给最高的顶面；不行为 NaN。
     */
    static double surface(Vec3 start, double heading, double length, double width, LandingSites.Ground ground) {
        Runway strip = new Runway(start, heading, length, width, false);
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (double along = 0; along <= length + 1e-6; along += SAMPLE) {
            for (double across = -width / 2; across <= width / 2 + 1e-6; across += SAMPLE) {
                Vec3 at = strip.at(along, across);
                int x = (int) Math.floor(at.x);
                int z = (int) Math.floor(at.z);
                Integer top = ground.top(x, z);
                if (top == null || ground.liquid(x, z)) return Double.NaN;
                lowest = Math.min(lowest, top);
                highest = Math.max(highest, top);
                if (highest - lowest > ROUGHNESS) return Double.NaN;
            }
        }
        return highest == Integer.MIN_VALUE ? Double.NaN : highest;
    }

    /**
     * 跑道头往外 120 格的下滑线下面空不空：跑道头附近地面和跑道一样高也行，远了地面要比下滑线低 3 格以上；
     * 没加载的不当畅通。
     */
    static boolean approachClear(Runway runway, LandingSites.Ground ground) {
        for (double out = 4; out <= APPROACH_OUT + 1e-6; out += 4) {
            double limit = Math.max(runway.y() + ROUGHNESS, runway.y() + out * GLIDE - UNDER_GLIDE);
            for (double across : new double[]{-runway.width() / 2, 0, runway.width() / 2}) {
                Vec3 at = runway.at(-out, across);
                Integer top = ground.top((int) Math.floor(at.x), (int) Math.floor(at.z));
                if (top == null || top > limit) return false;
            }
        }
        return true;
    }

    /** 选好的跑道现在还能落吗：地面还平、没长出挡路的方块（比跑道面高出 1 格以上）。 */
    static boolean blocked(Runway runway, LandingSites.Ground ground) {
        double now = surface(runway.threshold(), runway.heading(), runway.length(), runway.width(), ground);
        return Double.isNaN(now) || now > runway.y() + ROUGHNESS;
    }

    /**
     * 起飞量跑道：从一点沿朝向往前畅通多远——宽是 width 的一条，地面连着（没加载、是液体都算到头；浮在水上起飞的
     * 水面也算）、和起飞处的地面高低差不超过 1 格（高出来的就是挡着的方块）。
     */
    static double clearAhead(Vec3 from, double heading, double width, double max, int startTop, boolean water,
            LandingSites.Ground ground) {
        Runway strip = new Runway(from, heading, max, width, false);
        for (double along = 0; along <= max + 1e-6; along += SAMPLE) {
            for (double across = -width / 2; across <= width / 2 + 1e-6; across += SAMPLE) {
                Vec3 at = strip.at(along, across);
                int x = (int) Math.floor(at.x);
                int z = (int) Math.floor(at.z);
                Integer top = ground.top(x, z);
                if (top == null || (!water && ground.liquid(x, z)) || Math.abs(top - startTop) > ROUGHNESS) return along;
            }
        }
        return max;
    }

    /**
     * 从一点沿朝向前后量一片平地有多长（迫降时挑最长的）：和这一点的地面高低差不超过 1 格、不是液体、都加载着；
     * 量出来的那一片当一条迫降的跑道，跑道头在后面那一头。这一点本身站不住为空。
     */
    static Optional<Runway> measure(Vec3 center, double heading, double width, double half, LandingSites.Ground ground) {
        int x = (int) Math.floor(center.x);
        int z = (int) Math.floor(center.z);
        Integer reference = ground.top(x, z);
        if (reference == null || ground.liquid(x, z)) return Optional.empty();
        Vec3 base = new Vec3(center.x, reference, center.z);
        double forward = reach(base, heading, width, half, reference, ground);
        double back = reach(base, heading + 180, width, half, reference, ground);
        Runway probe = new Runway(base, heading, 0, width, true);
        return Optional.of(new Runway(probe.at(-back, 0), heading, forward + back, width, true));
    }

    // 沿一个朝向往前量到地面不平、是液体或没加载为止。
    private static double reach(Vec3 from, double heading, double width, double max, int reference,
            LandingSites.Ground ground) {
        return Math.max(0, clearAhead(from, heading, width, max, reference, false, ground) - SAMPLE);
    }

    /**
     * 一次找跑道：一点附近每隔几格一个候选、每个八个朝向，先近后远、朝向和飞来的方向顺的先试；一刻试几条，
     * 第一条够长够平、下滑线下面空着的就是。迫降时顺手量每个候选前后的平地，记下最长的一片。
     * 找到一条后接着往下试就是下一条候选。
     */
    static final class Search {

        /** 一个候选：中心与朝向。 */
        private record Candidate(Vec3 center, double heading) {}

        private final List<Candidate> candidates = new ArrayList<>();
        private final double length;
        private final double width;
        private final boolean forced;
        private final LandingSites.Ground ground;
        private int next;
        private Runway longest;

        /**
         * @param center  在哪一点附近找
         * @param range   前后左右多远以内（格）
         * @param step    候选隔几格一个
         * @param arrival 飞来的方向（度）：朝向和它顺的先试，少绕弯
         * @param length  要多长
         * @param width   要多宽
         * @param forced  迫降：顺手记下最长的一片平地
         */
        Search(Vec3 center, int range, int step, double arrival, double length, double width, boolean forced,
                LandingSites.Ground ground) {
            this.length = length;
            this.width = width;
            this.forced = forced;
            this.ground = ground;
            List<Vec3> centers = new ArrayList<>();
            for (int x = -range; x <= range; x += step) {
                for (int z = -range; z <= range; z += step) centers.add(center.add(x, 0, z));
            }
            centers.sort(Comparator.comparingDouble(point -> point.distanceToSqr(center)));
            for (Vec3 point : centers) {
                List<Double> headings = new ArrayList<>();
                for (int k = 0; k < 8; k++) headings.add(k * 45.0);
                headings.sort(Comparator.comparingDouble(heading -> Math.abs(PilotSample.turn(arrival, heading))));
                for (double heading : headings) candidates.add(new Candidate(point, heading));
            }
        }

        /** 再试最多 checks 条：试到一条能落的就给它；这一轮没试到为空。 */
        Optional<Runway> advance(int checks) {
            for (int i = 0; i < checks && next < candidates.size(); i++) {
                Candidate candidate = candidates.get(next++);
                Optional<Runway> runway = check(candidate);
                if (runway.isPresent()) return runway;
            }
            return Optional.empty();
        }

        /** 候选都试过了。 */
        boolean exhausted() {
            return next >= candidates.size();
        }

        /** 迫降时量到的最长那一片平地；不迫降、或一片也不够 16 格为空。 */
        Optional<Runway> longest() {
            return Optional.ofNullable(longest);
        }

        // 中心在候选点、按要的长度摆一条，平、下滑线下面空着就能落；迫降另量前后的平地记下最长的。
        private Optional<Runway> check(Candidate candidate) {
            Runway probe = new Runway(candidate.center(), candidate.heading(), length, width, false);
            Vec3 start = probe.at(-length / 2, 0);
            double top = surface(start, candidate.heading(), length, width, ground);
            if (!Double.isNaN(top)) {
                Runway runway = new Runway(new Vec3(start.x, top, start.z), candidate.heading(), length, width, false);
                if (approachClear(runway, ground)) return Optional.of(runway);
            }
            if (forced) {
                // 迫降的那一片窄一点也行：翼展再各留 1 格。
                measure(candidate.center(), candidate.heading(), Math.max(1, width - 2), length, ground)
                        .filter(found -> found.length() >= MIN_FORCED)
                        .filter(found -> longest == null || found.length() > longest.length())
                        .ifPresent(found -> longest = found);
            }
            return Optional.empty();
        }
    }
}
