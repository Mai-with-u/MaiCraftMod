// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore.cave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkReport;
import org.maiwithu.maicraft.behavior.navigation.WalkRun;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 钻洞的离线测试替身：用方块格摆一片洞，演走到与转头，不碰游戏对象。 */
final class CaveFakes {

    private CaveFakes() {}

    /**
     * 假洞：地表（y=70）以下全是石头，挖开的格是空气；地表以上是见天的空气。一列里头顶还有石头的格天空光为 0，
     * 没有的为 15。脚下踩实心、脚头两格空、不在液体里算能站。看不见的格单独登记，默认都看得见。
     */
    static final class Cave implements CaveWorld {
        static final int SURFACE_Y = 70;
        final Set<BlockPos> carved = new HashSet<>();
        final Map<BlockPos, Liquid> liquids = new HashMap<>();
        final Set<BlockPos> hidden = new HashSet<>();
        final List<Sighting> sightings = new ArrayList<>();
        BlockPos feet = new BlockPos(0, 64, 0);
        String dimension = "minecraft:overworld";

        /** 挖一条隧道：from 到 to 这一片（脚的那一层）连同头上一格挖空，脚下、头顶、两侧还是石头。 */
        Cave tunnel(BlockPos from, BlockPos to) {
            for (BlockPos cell : BlockPos.betweenClosed(from, to)) {
                carved.add(cell.immutable());
                carved.add(cell.above().immutable());
            }
            return this;
        }

        /** 挖开一片露天：这一片从脚的那一层一直挖到地表，头顶见天。 */
        Cave openGround(BlockPos from, BlockPos to) {
            for (BlockPos cell : BlockPos.betweenClosed(from, to)) {
                for (int y = cell.getY(); y < SURFACE_Y; y++) {
                    carved.add(new BlockPos(cell.getX(), y, cell.getZ()));
                }
            }
            return this;
        }

        /** 这一格是液体：挖开并灌上水或岩浆。 */
        Cave flood(BlockPos cell, Liquid liquid) {
            carved.add(cell.immutable());
            liquids.put(cell.immutable(), liquid);
            return this;
        }

        boolean solid(BlockPos cell) {
            return cell.getY() < SURFACE_Y && !carved.contains(cell);
        }

        @Override public WorldPosition currentSpot() {
            return new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), dimension);
        }

        @Override public Float facingYaw() {
            return 180f;
        }

        @Override public boolean loaded(BlockPos cell) {
            return true;
        }

        @Override public boolean standable(BlockPos at) {
            return solid(at.below()) && roomy(at) && roomy(at.above());
        }

        @Override public boolean roomy(BlockPos cell) {
            return !solid(cell) && liquid(cell) == Liquid.NONE;
        }

        @Override public Liquid liquid(BlockPos cell) {
            return liquids.getOrDefault(cell, Liquid.NONE);
        }

        @Override public int skyLight(BlockPos cell) {
            for (int y = cell.getY() + 1; y < SURFACE_Y; y++) {
                if (solid(new BlockPos(cell.getX(), y, cell.getZ()))) return 0;
            }
            return 15;
        }

        @Override public boolean visible(BlockPos cell) {
            return !hidden.contains(cell);
        }

        @Override public boolean prepareSight(int radius, List<String> interestBlocks) {
            return true;
        }

        @Override public List<Sighting> blocksInSight(int radius, List<String> interestBlocks) {
            return sightings.stream().filter(sighting -> !hidden.contains(sighting.pos())).toList();
        }
    }

    /**
     * 走到替身：记下每次出发的目的地与目标；走不到的登记在集合里（或全都走不到），第一刻就报走不通；
     * 其余每刻把脚往目的地挪两格（不能站时挪到它旁边能站的那格），到了报走到。
     */
    static final class Walks implements WalkTo {
        final Cave cave;
        final List<BlockPos> started = new ArrayList<>();
        final List<GoalCompiler.Compiled> goals = new ArrayList<>();
        final Set<BlockPos> unreachable = new HashSet<>();
        /** 哪里都走不到：一段都没走成的演法。 */
        boolean nothingReachable;
        /** 下一段走在半空：叫它先停稳时答应收尾，落地才报"已停下"。 */
        boolean nextAirborne;

        Walks(Cave cave) {
            this.cave = cave;
        }

        @Override public WalkRun start(GoalCompiler.Compiled target, TerrainPermit permit) {
            BlockPos destination = target.goal().center();
            started.add(destination);
            goals.add(target);
            boolean airborne = nextAirborne;
            nextAirborne = false;
            return new Run(this, destination, airborne);
        }

        // 能站的落脚点：目的地本身，或它旁边一格能站的地方（矿、岩浆本身站不上去）。
        BlockPos landing(BlockPos destination) {
            if (cave.standable(destination)) return destination;
            for (BlockPos near : BlockPos.betweenClosed(destination.offset(-2, -2, -2), destination.offset(2, 2, 2))) {
                if (cave.standable(near)) return near.immutable();
            }
            return destination;
        }
    }

    /** 一段演出来的走到：第一刻就结算；悬在半空的那段要等收尾后下一刻落地才报"已停下"。 */
    static final class Run implements WalkRun {
        private final Walks walks;
        private final BlockPos destination;
        private boolean airborne;
        private WalkReport last = WalkReport.planning();
        private boolean stopRequested;

        Run(Walks walks, BlockPos destination, boolean airborne) {
            this.walks = walks;
            this.destination = destination;
            this.airborne = airborne;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (airborne) {
                if (stopRequested) {
                    // 落地站稳：停在半路，不是走到。
                    airborne = false;
                    last = WalkReport.stopped(walks.cave.feet, false);
                    return ActionStatus.done();
                }
                last = WalkReport.onTheWay(walks.cave.feet, false);
                return ActionStatus.progressed();
            }
            if (walks.nothingReachable || walks.unreachable.contains(destination)) {
                last = WalkReport.failed(Problem.of(Problem.Kind.UNREACHABLE, "这里走不过去"), walks.cave.feet, false);
                return ActionStatus.failed(last.problem());
            }
            // 一刻挪两格，路上每一步都落在走过的地方里，和真的走过去一样。
            BlockPos target = walks.landing(destination);
            BlockPos feet = walks.cave.feet;
            double dx = target.getX() - feet.getX();
            double dy = target.getY() - feet.getY();
            double dz = target.getZ() - feet.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= 2) {
                walks.cave.feet = target;
                last = WalkReport.arrived(target, false);
                return ActionStatus.done();
            }
            walks.cave.feet = feet.offset((int) Math.round(dx * 2 / distance), (int) Math.round(dy * 2 / distance),
                    (int) Math.round(dz * 2 / distance));
            last = WalkReport.onTheWay(walks.cave.feet, false);
            return ActionStatus.progressed();
        }

        @Override public WalkReport report() {
            return last;
        }

        @Override public boolean resume() {
            return false;
        }

        @Override public boolean stop() {
            stopRequested = true;
            return !airborne;
        }

        @Override public boolean stopSafely() {
            stopRequested = true;
            return airborne;
        }

        @Override public boolean stoppedSafely() {
            return !airborne;
        }

        @Override public void pause() { }

        @Override public Interruptibility interruptibility() {
            return airborne ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        @Override public void close() { }

        @Override public String describe() {
            return "演出来的走到";
        }
    }

    /** 转头看一圈的替身：转两刻就转完。 */
    static final class Look implements Action {
        private int ticks;

        @Override public ActionStatus tick(TickContext context) {
            return ++ticks >= 2 ? ActionStatus.done() : ActionStatus.running();
        }

        @Override public Interruptibility interruptibility() {
            return Interruptibility.BETWEEN_ACTIONS;
        }

        @Override public void close() { }

        @Override public String describe() {
            return "转头看一圈";
        }
    }

    /** 本刻上下文替身：只有刻号。 */
    static final class Tick implements TickContext {
        private long gameTick;

        @Override public long gameTick() {
            return gameTick++;
        }

        @Override public PlayerContext player() {
            throw new IllegalStateException("钻洞测试不应该碰到角色对象");
        }
    }
}
