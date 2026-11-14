// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.night;

import java.util.OptionalLong;

/**
 * 夜里自动去睡的会话设置：默认开（像正常玩家一样，天黑了有床就找空当去睡），LLM 能用 sleep 能力临时关掉——
 * 多人游戏里有玩家说"晚上别睡"或自己今晚要守夜时照办。临时关闭带游戏时间期限（默认一个完整昼夜 1200 秒），
 * 到期自己恢复为开，避免为守一夜关掉后整场忘记再打开。关着时夜晚这项生存需求不找床也不备床，
 * 露天打不过也不建任务，挨打交给自卫与战斗引擎。进世界重置为开。只在客户端线程读写。
 */
public final class AutoSleep {

    /** 不传期限时的默认关闭时长（游戏时间秒）：1200 秒即 24000 刻（一个完整昼夜）。 */
    public static final long DEFAULT_OFF_SECONDS = 1200;
    /** 最短临时关闭时长（游戏时间秒）：1 分钟。 */
    public static final long MIN_OFF_SECONDS = 60;
    /** 最长临时关闭时长（游戏时间秒）：7200 秒（六个昼夜）。 */
    public static final long MAX_OFF_SECONDS = 7200;

    private boolean enabled = true;
    /** 临时关闭到哪一游戏刻自动恢复；未设期限或处于开启状态时为 Long.MIN_VALUE。 */
    private long disabledUntilTick = Long.MIN_VALUE;

    /** 进世界重置为默认：开，清掉上个世界留下的期限。 */
    public void reset() {
        enabled = true;
        disabledUntilTick = Long.MIN_VALUE;
    }

    /** 开：天黑了有床就找空当去睡。 */
    public void enable() {
        enabled = true;
        disabledUntilTick = Long.MIN_VALUE;
    }

    /** 无期限关（仅供不推进游戏刻的纯逻辑单测使用）；生产入口一律走带期限的 {@link #disableForSeconds}。 */
    public void disable() {
        enabled = false;
        disabledUntilTick = Long.MIN_VALUE;
    }

    /**
     * 临时关掉夜里自动去睡若干秒（按游戏刻折算，20 刻为 1 秒），到点经 {@link #tick} 自动恢复为开。
     *
     * @return 实际生效的关闭秒数（裁剪在合法范围内）
     */
    public long disableForSeconds(long nowGameTick, long seconds) {
        long clamped = Math.max(MIN_OFF_SECONDS, Math.min(MAX_OFF_SECONDS, seconds));
        enabled = false;
        disabledUntilTick = nowGameTick + clamped * 20L;
        return clamped;
    }

    /**
     * 每刻推进一次期限：临时关闭到点时自动恢复为开并返回真，调用方据此发一条提醒事件告诉宿主。
     */
    public boolean tick(long nowGameTick) {
        if (!enabled && disabledUntilTick != Long.MIN_VALUE && nowGameTick >= disabledUntilTick) {
            enabled = true;
            disabledUntilTick = Long.MIN_VALUE;
            return true;
        }
        return false;
    }

    /** 临时关闭还剩多少秒（向上取整到整秒）；开着或没设期限时为空。 */
    public OptionalLong remainingSeconds(long nowGameTick) {
        if (enabled || disabledUntilTick == Long.MIN_VALUE) {
            return OptionalLong.empty();
        }
        long remainingTicks = Math.max(0L, disabledUntilTick - nowGameTick);
        return OptionalLong.of((remainingTicks + 19L) / 20L);
    }

    public boolean enabled() {
        return enabled;
    }
}
