// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 留在箱式加工站里正在加工的一批：哪座站、进出料口、烧什么原料、预计出几件成品、
 * 投完关箱时进料箱还剩几件原料（回来发现出料箱 0 产出时用来核对机器有没有在抽料）、挂上的计时编号。
 *
 * @param stationName         加工站名字
 * @param via                 用途途径，例如 {@code smelt}
 * @param input               投料容器位置（带维度）
 * @param output              取货容器位置（带维度）
 * @param recipeId            所走配方的注册 ID
 * @param resultItemId        目标成品的物品 ID
 * @param resultName          成品给人看的名字
 * @param ingredientItemId    投入的原料物品 ID
 * @param ingredientName      原料给人看的名字
 * @param loaded              这一批投进去的原料件数
 * @param inputCountAfterFeed 投完料关箱那一刻进料箱里该原料的总件数（0 表示投进瞬间已被抽走）
 * @param makes               这一批预计产出成品件数
 * @param leftAtGameTick      登记离开时的游戏刻
 * @param readyAtGameTick     预计可以回来检查取货的游戏刻
 * @param timerId             挂上的计时编号；计时挂满没挂上为 null
 */
public record LeftStationBatch(
        String stationName,
        String via,
        WorldPosition input,
        WorldPosition output,
        String recipeId,
        String resultItemId,
        String resultName,
        String ingredientItemId,
        String ingredientName,
        int loaded,
        int inputCountAfterFeed,
        int makes,
        long leftAtGameTick,
        long readyAtGameTick,
        Integer timerId) {

    private static final long TICKS_PER_SECOND = 20L;

    public LeftStationBatch {
        if (stationName == null || stationName.isBlank()) throw new IllegalArgumentException("加工站名不能为空");
        Objects.requireNonNull(via, "via");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(resultItemId, "resultItemId");
        Objects.requireNonNull(ingredientItemId, "ingredientItemId");
        if (loaded <= 0) throw new IllegalArgumentException("投入件数至少为 1：" + loaded);
        if (makes <= 0) throw new IllegalArgumentException("预计产出件数至少为 1：" + makes);
        if (inputCountAfterFeed < 0) throw new IllegalArgumentException("进料箱剩余件数不能为负：" + inputCountAfterFeed);
    }

    /** 挂上计时编号后的同一批记录。 */
    public LeftStationBatch withTimer(Integer id) {
        return new LeftStationBatch(stationName, via, input, output, recipeId, resultItemId, resultName,
                ingredientItemId, ingredientName, loaded, inputCountAfterFeed, makes, leftAtGameTick,
                readyAtGameTick, id);
    }

    /** 预计还要等多久才回来检查（秒），至少 1 秒。 */
    public long readyInSeconds() {
        return Math.max(1L, (Math.max(0L, readyAtGameTick - leftAtGameTick) + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
    }

    /** 出料箱的坐标写法：(x, y, z)。 */
    public String whereOutput() {
        return "(" + output.x() + ", " + output.y() + ", " + output.z() + ")";
    }

    /** 计时到点时原样带回的备注，写清回哪座站取什么。 */
    public String timerNote() {
        return "回加工站「" + stationName + "」出料箱 " + whereOutput() + " 取" + resultName + " ×" + makes;
    }

    /** 给调用方看的一句话说明：投了多少原料进哪座站、约多少秒后去出料箱取。 */
    public String sentence() {
        return "加工站「" + stationName + "」已投入 " + loaded + " 个" + ingredientName + "，预计产出 "
                + makes + " 个" + resultName + "，约 " + readyInSeconds() + " 秒后到出料箱 " + whereOutput() + " 取货";
    }

    /** 到点回来怎么继续：给出计时编号与再次 obtain 的提示。 */
    public String suggestion() {
        String when = timerId != null
                ? "计时 " + timerId + " 到点会在事件里叫你（timer_due），到时"
                : "计时挂满了没挂上，约 " + readyInSeconds() + " 秒后";
        return when + "再 obtain " + resultItemId + "（件数照 remaining 写）即可先回「" + stationName + "」取货"
                + (timerId != null ? "；不打算回来取了可以 timer(operation=cancel, timer_id=" + timerId + ")" : "");
    }
}
