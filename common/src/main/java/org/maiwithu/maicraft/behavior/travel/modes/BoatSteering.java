// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.modes;

import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 划船的转向（纯函数）：每刻决定前进、倒退还是左右转。
 * 身体只有一套按键：按住前进（前进键）划，左右转用平移键（A/D）——1.21 的原版船头不跟着视线走，
 * 转向要按平移。前方顶到岸、搁浅、睡莲、冰就先倒退几刻再接着转过去；
 * 连着几个观察窗都没前进说明这个靠岸点到不了，换一个，不在这里原地死磕。
 */
public final class BoatSteering {

    /** 朝向对准到这个误差（度）以内就不必再转。 */
    static final float AIMED_DEGREES = 10.0F;
    /** 顶到东西先倒退这么多刻，退开再转。 */
    public static final int REVERSE_TICKS = 10;
    /** 多久看一次前进没前进（刻）。 */
    public static final int WINDOW_TICKS = 20;
    /** 连着几个观察窗没前进就换靠岸点。 */
    public static final int MAX_STALLED_WINDOWS = 3;
    /** 离靠岸点这么近（格）就开始贴岸冲滩：船要蹭上岸边才够得着下船点，靠岸前的岸不算障碍。 */
    public static final double BEACH_DISTANCE = 4.0;
    /** 一个观察窗里离靠岸点真近了这么多格才算前进：倒退再划回来的来回蹭动攒不出新纪录。 */
    public static final double PROGRESS_BLOCKS = 0.75;

    private BoatSteering() {}

    /** 一刻的操舵：前进多少（1 划、-1 倒）、平移多少（-1 右转、+1 左转）、要不要换靠岸点。 */
    public record Decision(float forward, float strafe, boolean switchLanding) {}

    /** 划船的进度账：连着几个观察窗没前进，还剩几刻倒退。 */
    public record Pace(int stalledWindows, int reverseTicksLeft) {}

    /** 目标朝向相对当前船头差多少（度，正数要向右转，已归一到 -180..180）。 */
    public static float yawError(float currentYaw, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (dx * dx + dz * dz < 1.0e-4) return 0;
        float target = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float error = target - currentYaw;
        while (error > 180) error -= 360;
        while (error < -180) error += 360;
        return error;
    }

    /**
     * 一刻的操舵决定。
     *
     * @param yawError     目标朝向差（正数要向右转）
     * @param blockedAhead 前方顶到岸、搁浅、睡莲或冰
     * @param pace         划船的进度账（调用方每个观察窗结算一次）
     */
    public static Decision decide(float yawError, boolean blockedAhead, Pace pace) {
        // 连着几个窗都没前进：这个靠岸点到不了，换一个，别原地死磕。
        if (pace.stalledWindows() >= MAX_STALLED_WINDOWS) return new Decision(0, 0, true);
        // 还在倒退退障：退的同时往要转的方向带一把，退开就有角度了。
        if (pace.reverseTicksLeft() > 0) return new Decision(-1, yawError > 0 ? -1 : 1, false);
        if (blockedAhead) return new Decision(-1, yawError > 0 ? -1 : 1, false);
        // 对准了直划；差得多就边划边转。船头向右转是平移键右（-1）：船头向右转朝向角变大。
        if (Math.abs(yawError) <= AIMED_DEGREES) return new Decision(1, 0, false);
        return new Decision(1, yawError > 0 ? -1 : 1, false);
    }

    /**
     * 前方挡路的算不算障碍：离靠岸点比冲滩距离近时不算——最后这几格是故意往岸上蹭的，
     * 原版船顶上岸就停住、蹭得进冲滩距离以内就下船；把靠岸前的岸当障碍，
     * 船会在三格开外倒退避让与前进互相抵消，在出发位置附近打转永远靠不了岸。
     *
     * @param read               读一格方块；没加载的格给空（没加载不当成挡路）
     * @param position           船此刻的位置
     * @param yaw                船头朝向
     * @param distanceToLanding  船到靠岸点的水平距离（格）
     */
    public static boolean obstacleAhead(Function<BlockPos, Optional<BlockState>> read, Vec3 position, float yaw,
            double distanceToLanding) {
        return distanceToLanding > BEACH_DISTANCE && blockedAhead(read, position, yaw);
    }

    /**
     * 观察窗的进展账：离靠岸点比记过的最好成绩真近了这么多格才算前进；
     * 还没有纪录（bestSoFar 为负，比如刚换靠岸点）时这一窗先立账。
     * 倒退避让再划回来的来回蹭动离岸忽远忽近，攒不出新纪录，早晚判成没前进换靠岸点。
     */
    public static boolean approachingLanding(double distanceToLanding, double bestSoFar) {
        return bestSoFar < 0 || distanceToLanding < bestSoFar - PROGRESS_BLOCKS;
    }

    /**
     * 前方顶到东西没有：船头方向 1.5 格与 3 格处，水面那格与上面一格，
     * 有实方块（岸）、睡莲或冰都算顶到了；那里不是水（地面比水面高或直接是实底）也算搁浅顶住了。
     *
     * @param read     读一格方块；没加载的格给空（没加载不当成挡路，船开过去自然停下再看）
     * @param position 船此刻的位置
     * @param yaw      船头朝向
     */
    public static boolean blockedAhead(Function<BlockPos, Optional<BlockState>> read, Vec3 position, float yaw) {
        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));
        int surfaceY = (int) Math.floor(position.y);
        for (double ahead : new double[]{1.5, 3.0}) {
            int x = (int) Math.floor(position.x + dx * ahead);
            int z = (int) Math.floor(position.z + dz * ahead);
            // 水面那格不是水（岸、搁浅的实底）或上面一格有实遮挡、睡莲、冰，都算顶到了。
            if (blocking(read, new BlockPos(x, surfaceY, z))) return true;
            if (blocking(read, new BlockPos(x, surfaceY + 1, z))) return true;
        }
        return false;
    }

    /** 这一格挡不挡船：实方块、睡莲、冰都挡；水、空气和水里的草、海带不挡（船穿得过去）。 */
    private static boolean blocking(Function<BlockPos, Optional<BlockState>> read, BlockPos cell) {
        Optional<BlockState> state = read.apply(cell);
        if (state.isEmpty()) return false;
        BlockState found = state.get();
        if (found.isAir() || isWater(found) || isWaterPlant(found)) return false;
        return true;
    }

    private static boolean isWater(BlockState state) {
        return state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.BUBBLE_COLUMN;
    }

    /** 长在水里的植物：海草（含高株两格）、海带（含长条），船身能直接穿过去。 */
    private static boolean isWaterPlant(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS
                || block == Blocks.KELP || block == Blocks.KELP_PLANT;
    }
}
