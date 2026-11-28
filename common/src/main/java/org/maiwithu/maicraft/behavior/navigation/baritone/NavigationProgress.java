// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

/**
 * 记录导航最近是否有实际进展：位置累计变化至少四分之一格，或确认完成一次原生操作。首次采样只记起点，不算进展。
 * 同时监视水平小半径原地往返折返：仅在真正持路行走期间跟踪，角色在 2 格半径小圈内滞留超过 8 秒（160 刻，
 * 晚于执行段单步卡住超时以保留切门与列障碍自愈机会）且水平累计走动至少 4 格（不计原地起跳竖直位移），
 * 才判定陷入原地往返折返；走出小圈（含竖直爬升超过 2 格）、算路空档、完成挖垫/切门等原生交互、
 * 暂停续走或换目标时均重置折返锚点，不误伤正常绕路长途步行、原地起跳重试或挖硬方块。
 */
final class NavigationProgress {
    private static final double MIN_DISPLACEMENT_SQUARED = 0.25 * 0.25;
    /** 判定原地往返折返的小圈半径（格）：正常推进、绕墙或竖直爬升超过 2 格即刷新锚点。 */
    static final double PACING_RADIUS = 2.0;
    private static final double PACING_RADIUS_SQUARED = PACING_RADIUS * PACING_RADIUS;
    /** 在同一小圈内水平往返超过这么多游戏刻（8 秒）即判止损：晚于执行段单步超时（约 105–130 刻）避免抢跑切门自愈。 */
    static final int PACING_LIMIT_TICKS = 160;
    /** 小圈内水平累计走动至少这么多格才算往返折返（排除原地不动卡门、原地起跳与蜘蛛网极慢单向移动）。 */
    static final double PACING_MIN_TRAVEL = 4.0;
    /** 忽略单刻小于此水平距离的静止微抖动，避免碰撞箱轻颤被误算成行走路程。 */
    private static final double PACING_MIN_STEP_SQUARED = 0.05 * 0.05;

    private double x, y, z;
    private boolean sampled;
    private long started = Long.MIN_VALUE;
    private long confirmed = Long.MIN_VALUE;

    /** 原地小半径往返折返的锚点、进入时刻、上一刻水平坐标与圈内水平累计路程。 */
    private double anchorX, anchorY, anchorZ;
    private double lastX, lastZ;
    private long pacingStartTick = Long.MIN_VALUE;
    private double pacingTravel;

    void observe(double nextX, double nextY, double nextZ, long tick) {
        observe(nextX, nextY, nextZ, tick, true);
    }

    /**
     * 记录一刻的位置采样。
     *
     * @param trackPacing 仅当此刻有路线在走时为真；算路期无路线时清空折返窗口，防止水中漂流或受推挤提前攒满折返
     */
    void observe(double nextX, double nextY, double nextZ, long tick, boolean trackPacing) {
        if (started == Long.MIN_VALUE) started = tick;
        if (trackPacing) {
            observePacing(nextX, nextY, nextZ, tick);
        } else {
            resetPacing();
        }
        double dx = nextX - x, dy = nextY - y, dz = nextZ - z;
        if (!sampled || dx * dx + dy * dy + dz * dz >= MIN_DISPLACEMENT_SQUARED) {
            if (sampled) confirmed = tick;
            x = nextX;
            y = nextY;
            z = nextZ;
            sampled = true;
        }
    }

    // 跟踪水平小半径往返折返：水平离开 2 格圈或竖直爬升/下降超 2 格立即刷新锚点；圈内只累计水平步长，不计原地跳跃。
    private void observePacing(double nextX, double nextY, double nextZ, long tick) {
        if (pacingStartTick == Long.MIN_VALUE || tick < pacingStartTick) {
            setPacingAnchor(nextX, nextY, nextZ, tick);
            return;
        }
        double stepX = nextX - lastX, stepZ = nextZ - lastZ;
        double flatStepSq = stepX * stepX + stepZ * stepZ;
        lastX = nextX;
        lastZ = nextZ;
        double fromAnchorX = nextX - anchorX, fromAnchorZ = nextZ - anchorZ;
        if (fromAnchorX * fromAnchorX + fromAnchorZ * fromAnchorZ > PACING_RADIUS_SQUARED
                || Math.abs(nextY - anchorY) > PACING_RADIUS) {
            setPacingAnchor(nextX, nextY, nextZ, tick);
            return;
        }
        if (flatStepSq >= PACING_MIN_STEP_SQUARED) {
            pacingTravel += Math.sqrt(flatStepSq);
        }
    }

    private void setPacingAnchor(double px, double py, double pz, long tick) {
        anchorX = px;
        anchorY = py;
        anchorZ = pz;
        lastX = px;
        lastZ = pz;
        pacingStartTick = tick;
        pacingTravel = 0.0;
    }

    /** 确认完成一次原生交互（挖块、垫块、切门、列障碍）：刷新进展并重置小圈折返锚点。 */
    void confirm(long tick) {
        confirmed = tick;
        resetPacing();
    }

    /** 只重置小圈折返锚点（算路期、重算绕行、动态换目标时调用，避免误判折返）。 */
    void resetPacing() {
        pacingStartTick = Long.MIN_VALUE;
        pacingTravel = 0.0;
    }

    /** 从这一刻重新起算：人操作过的那段、暂停过的那段不算"长时间没进展"或原地折返。 */
    void restart(long tick) {
        started = tick;
        confirmed = Long.MIN_VALUE;
        sampled = false;
        resetPacing();
    }
    long confirmedTick() { return confirmed; }

    boolean recent(long tick, int grace) {
        return confirmed != Long.MIN_VALUE && age(tick, confirmed) <= Math.max(0, grace);
    }

    int stalledTicks(long tick) {
        return age(tick, confirmed == Long.MIN_VALUE ? started : confirmed);
    }

    /** 是否已陷入原地水平小半径往返折返：在同一 2 格圈内滞留超 8 秒且水平累计走动至少 4 格。 */
    boolean pacing(long tick) {
        return pacingStartTick != Long.MIN_VALUE && tick >= pacingStartTick
                && tick - pacingStartTick > PACING_LIMIT_TICKS
                && pacingTravel >= PACING_MIN_TRAVEL;
    }

    private static int age(long tick, long since) {
        if (since == Long.MIN_VALUE || tick < since) return Integer.MAX_VALUE;
        return (int) Math.min(Integer.MAX_VALUE, tick - since);
    }
}
