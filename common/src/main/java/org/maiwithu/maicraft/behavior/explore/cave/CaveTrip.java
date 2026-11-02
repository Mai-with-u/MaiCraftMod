// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 这一趟钻洞的账：一路看见的矿（按格记，收场时聚成矿脉）、没走的岔路、过不去的方向、走了几段多远。
 * 钻洞每看一眼往里记一次，收场时按角色此刻的位置写成结果里的岔路。
 */
final class CaveTrip {

    /** 同一种挡路离已记的那处这么近就算同一处：一片地下湖只说一次"那边是水"。 */
    static final int SAME_BLOCKED = 8;
    /** 一刻最多算几格路：掉下去、被水冲走的那一下不算走路。 */
    static final double MAX_STEP_PER_TICK = 4;

    private final Map<BlockPos, String> ores = new LinkedHashMap<>();
    private final List<CaveLegChoice.Branch> branches = new ArrayList<>();
    private final List<WalkableArea.Blocked> blocked = new ArrayList<>();
    private int legs;
    private int failures;
    private double walkedBlocks;
    private WorldPosition lastFeet;

    /** 记下这一眼看见的矿；返回有没有看见以前没见过的矿。 */
    boolean noteOres(List<CaveWorld.Sighting> sightings) {
        boolean fresh = false;
        for (CaveWorld.Sighting sighting : sightings) {
            if (sighting.ore() && ores.putIfAbsent(sighting.pos(), sighting.blockId()) == null) {
                fresh = true;
            }
        }
        return fresh;
    }

    /** 一路看见的矿，按格。 */
    Map<BlockPos, String> ores() {
        return ores;
    }

    /** 记下这一眼看见、没去的岔路：离已记的岔路不到一个新地方远的算同一条。 */
    void noteBranches(List<CaveLegChoice.Branch> seen) {
        for (CaveLegChoice.Branch branch : seen) {
            boolean known = branches.stream().anyMatch(existing ->
                    existing.at().distSqr(branch.at()) < CaveLegChoice.FRESH * CaveLegChoice.FRESH);
            if (!known) branches.add(branch);
        }
    }

    /** 已经走过、或走过去没走到的岔路划掉。 */
    void dropBranches(Predicate<BlockPos> gone) {
        branches.removeIf(branch -> gone.test(branch.at()));
    }

    /** 取出离脚下最近的一条没走的岔路；没有为空。 */
    Optional<CaveLegChoice.Branch> takeNearestBranch(BlockPos feet) {
        Optional<CaveLegChoice.Branch> nearest = branches.stream()
                .min(Comparator.comparingDouble(branch -> branch.at().distSqr(feet)));
        nearest.ifPresent(branches::remove);
        return nearest;
    }

    /** 还有几条没走的岔路。 */
    int branchCount() {
        return branches.size();
    }

    /** 记下量路量到的过不去的地方：同一种离已记的不到 8 格的算同一处。 */
    void noteBlocked(List<WalkableArea.Blocked> found) {
        for (WalkableArea.Blocked candidate : found) {
            boolean known = blocked.stream().anyMatch(existing -> existing.reason() == candidate.reason()
                    && existing.at().distSqr(candidate.at()) <= SAME_BLOCKED * SAME_BLOCKED);
            if (!known) blocked.add(candidate);
        }
    }

    /** 记一段走完了（走到没走到都算一段）。 */
    void noteLeg(boolean arrived) {
        legs++;
        if (!arrived) failures++;
    }

    int legs() {
        return legs;
    }

    int failures() {
        return failures;
    }

    /** 每刻记一次脚下：两刻之间挪了多远就是走了多远；换了维度、一下挪太远的不算。 */
    void noteFeet(WorldPosition feet) {
        if (feet == null) return;
        if (lastFeet != null && lastFeet.dimension().equals(feet.dimension())) {
            double dx = feet.x() - lastFeet.x();
            double dy = feet.y() - lastFeet.y();
            double dz = feet.z() - lastFeet.z();
            double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (step <= MAX_STEP_PER_TICK) walkedBlocks += step;
        }
        lastFeet = feet;
    }

    /** 这一趟走了多少格路。 */
    int walkedBlocks() {
        return (int) walkedBlocks;
    }

    /** 结果里的岔路：没走的在前，过不去的在后；方位、远近、高低都按角色此刻站的地方说。 */
    List<CaveDetails.Branch> describeBranches(BlockPos feet) {
        List<CaveDetails.Branch> described = new ArrayList<>();
        for (CaveLegChoice.Branch branch : branches) {
            described.add(relative(feet, branch.at(), null));
        }
        for (WalkableArea.Blocked place : blocked) {
            described.add(relative(feet, place.at(), blockedWord(place)));
        }
        return described;
    }

    private static CaveDetails.Branch relative(BlockPos feet, BlockPos at, String blockedBy) {
        int dx = at.getX() - feet.getX();
        int dz = at.getZ() - feet.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        return new CaveDetails.Branch(DirectionWords.compassOf(dx, dz), distance, at.getY() - feet.getY(), blockedBy);
    }

    private static String blockedWord(WalkableArea.Blocked place) {
        return switch (place.reason()) {
            case WATER -> "水";
            case LAVA -> "岩浆";
            case DROP -> "往下" + place.depth() + "格的落差";
        };
    }
}
