// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 装炉就走留下的一炉：哪台炉子、烧什么、投入口里还在烧几件、预计什么时候烧好、挂的计时。
 * 回来再要同一样东西时按它认那台炉子、少备原料；收场时按它把这一炉说给 LLM。
 *
 * @param furnace               炉子在哪（带维度）
 * @param kind                  什么炉：熔炉、高炉或烟熏炉
 * @param recipeId              烧的是哪条配方：回来时只认同一条配方
 * @param resultItemId          烧出来的东西
 * @param resultName            烧出来的东西给人看的名字
 * @param ingredientItemId      投入口里的原料
 * @param ingredientName        原料给人看的名字
 * @param fuelItemId            自己添进燃料槽的是什么，回来收拾时只收回这一种；没添过为 null
 * @param loaded                走的时候投入口里还没烧的原料件数
 * @param makes                 这一炉烧完出几件成品
 * @param ingredientsInBackpack 走的时候背包里还有几件这种原料没装
 * @param leftAtGameTick        走的那一刻的游戏时间
 * @param readyAtGameTick       按配方时长估的烧完时刻（游戏时间）
 * @param timerId               挂上的计时编号；计时挂满了没挂上为 null
 */
public record LeftCooking(WorldPosition furnace, WorkstationRecipe.Kind kind, String recipeId, String resultItemId,
        String resultName, String ingredientItemId, String ingredientName, String fuelItemId, int loaded, int makes,
        int ingredientsInBackpack, long leftAtGameTick, long readyAtGameTick, Integer timerId) {

    /** 每秒的游戏刻数。 */
    private static final long TICKS_PER_SECOND = 20;

    public LeftCooking {
        Objects.requireNonNull(furnace, "furnace");
        Objects.requireNonNull(kind, "kind");
        if (loaded <= 0) throw new IllegalArgumentException("留下的一炉至少要有一件在烧：" + loaded);
    }

    /** 挂上计时以后的同一炉。 */
    public LeftCooking withTimer(Integer id) {
        return new LeftCooking(furnace, kind, recipeId, resultItemId, resultName, ingredientItemId, ingredientName,
                fuelItemId, loaded, makes, ingredientsInBackpack, leftAtGameTick, readyAtGameTick, id);
    }

    /** 走的时候估这一炉还要烧几秒：不足一秒算一秒。 */
    public long readyInSeconds() {
        return (Math.max(0, readyAtGameTick - leftAtGameTick) + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    /** 说给人听的约几分钟：不足一分钟算一分钟。 */
    public long readyInMinutes() {
        return Math.max(1, (readyInSeconds() + 59) / 60);
    }

    /** 炉子在哪的写法：(x, y, z)。 */
    public String where() {
        return "(" + furnace.x() + ", " + furnace.y() + ", " + furnace.z() + ")";
    }

    /** 计时的备注：到点时原样带回，一句话说清回哪去取什么。 */
    public String timerNote() {
        return "回 " + where() + " 的" + kind.displayName() + "取" + resultName + " ×" + makes;
    }

    /** 这一炉怎么说：哪台炉子在哪、烧几件什么、约几分钟好、背包里还剩几件原料没装。 */
    public String sentence() {
        return where() + " 的" + kind.displayName() + "里在烧 " + loaded + " 个" + ingredientName + "，约 "
                + readyInMinutes() + " 分钟烧好；"
                + (ingredientsInBackpack > 0 ? "背包里还有 " + ingredientsInBackpack + " 个" + ingredientName + "没装"
                        : "背包里没有剩的" + ingredientName);
    }

    /** 到点怎么做：计时编号、再 obtain 一次先取烧好的；计时挂满了没挂上时说约几分钟后自己回来。 */
    public String suggestion() {
        String when = timerId != null
                ? "计时 " + timerId + " 到点会在事件里叫你（timer_due），到时"
                : "计时挂满了没挂上，约 " + readyInMinutes() + " 分钟后";
        return when + "再 obtain " + resultItemId + "（件数照 remaining 里还差的写）：先回这台取烧好的，"
                + "背包里还有原料就装下一炉"
                + (timerId != null ? "；不打算回来取了可以 timer(operation=cancel, timer_id=" + timerId + ")" : "");
    }
}
