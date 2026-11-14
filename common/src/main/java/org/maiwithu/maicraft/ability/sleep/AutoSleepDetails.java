// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.sleep;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 夜里自动去睡开关操作的结果细节：当前开着还是关着，以及临时关闭还剩多少秒自动恢复。
 *
 * @param autoSleep       此刻夜里自动去睡是否开启
 * @param autoOnInSeconds 临时关闭还剩多少秒（游戏时间）自动恢复为开；开启状态或未计刻时为 null
 */
record AutoSleepDetails(boolean autoSleep, Long autoOnInSeconds) implements ResultDetails {}
