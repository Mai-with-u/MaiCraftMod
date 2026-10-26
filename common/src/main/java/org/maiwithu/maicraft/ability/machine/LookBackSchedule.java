// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 出口要点开界面才看得见的机器，投完料怎么回头看产物：隔多久走过去看一眼、这一眼新出了几件、
 * 连着几回都没出就收手。纯判断，不动手；看一眼与收产物的动作由 {@link MenuOutputLookBack} 做。
 *
 * <p>真人往通用机械的机器里放了料，不会贴着界面干等，也不会转身就走：估摸一个加工周期回来打开看看，
 * 出了就拿走，还没出就再等一会儿，看了几回都没动静就去查机器是不是没电、配方对不对。
 */
final class LookBackSchedule {

    /**
     * 配方里读不到加工时长时按 10 秒算一个周期：通用机械的基础机器（富集仓、粉碎机、冶金灌注机……）
     * 不装加速升级时一次加工正好 200 刻，机械动力的研磨配方在基础转速下大多 100～250 刻，按它估宁可晚看不早看。
     */
    static final int DEFAULT_CYCLE_TICKS = 200;
    /** 隔得再近也至少等 2 秒：走过去点开界面本身就要一两秒，看得更勤只是来回跑。 */
    static final int MIN_WAIT_TICKS = 40;
    /** 隔得再远也不超过 1 分钟：直播里角色站着不动超过一分钟就该回去看看，和看得见的出口久无新货的上限一致。 */
    static final int MAX_WAIT_TICKS = 20 * 60;
    /**
     * 连着三回看到都没出就收手：第一回是估的一个周期，机器转得慢、电刚供上时可能早了一点；
     * 第二回已经等了约两个周期；三回都没动静，多半是没电、没转、配方不对或输入卡着，再等也是干等，
     * 收手把机器读数交给 LLM 去查。
     */
    static final int EMPTY_LOOKS_LIMIT = 3;
    /** 配方原始定义里表示一次加工要几刻的字段：机械动力一族写 processing_time，原版烧炼一族写 cookingtime。 */
    private static final List<String> DURATION_KEYS = List.of("processing_time", "cookingtime");

    private final int cycleTicks;
    /** 上一眼看完、收走之后出口里还剩几件；开工前是那一眼看到的旧货。 */
    private int leftInExit;
    private int produced;
    private int collected;
    private int looks;
    private int emptyLooks;

    /**
     * @param cycleTicks 估的一个加工周期（刻）
     * @param baseline   开工前出口里已有几件要的东西（投料前看的那一眼）；这些不算这次做的
     */
    LookBackSchedule(int cycleTicks, int baseline) {
        this.cycleTicks = Math.max(1, cycleTicks);
        this.leftInExit = Math.max(0, baseline);
    }

    /** 一个加工周期估多少刻：配方原始定义里写着加工时长就按它（基础转速、不加速），读不到按保守的 10 秒。 */
    static int cycleTicks(JsonObject definition) {
        if (definition == null) return DEFAULT_CYCLE_TICKS;
        for (String key : DURATION_KEYS) {
            JsonElement value = definition.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsInt() > 0) {
                return value.getAsInt();
            }
        }
        return DEFAULT_CYCLE_TICKS;
    }

    /** 投完料到第一次回去看隔几刻：一个周期，夹在 2 秒到 1 分钟之间。 */
    int firstWait() {
        return clamp(cycleTicks);
    }

    /**
     * 记下一眼：看的那一刻出口里有 seen 件，收走了 taken 件。新出的件数是比上一眼收完剩下的多出来的部分，
     * 出口被别的东西（漏斗、别的玩家）拿走了不倒扣；一件没新出就记一回没出。
     *
     * @return 这一眼新出了几件
     */
    int record(int seen, int taken) {
        looks++;
        int fresh = Math.max(0, seen - leftInExit);
        produced += fresh;
        collected += Math.max(0, taken);
        leftInExit = Math.max(0, seen - Math.max(0, taken));
        emptyLooks = fresh > 0 ? 0 : emptyLooks + 1;
        return fresh;
    }

    /** 连着几回都没出，该收手了。 */
    boolean gaveUp() {
        return emptyLooks >= EMPTY_LOOKS_LIMIT;
    }

    /**
     * 看完这一眼之后隔几刻再看：还一件没出就再等一个周期；出过了就按已经等的时间与出的件数估剩下的要多久，
     * 一次看个大概齐，不为每一件跑一趟。
     *
     * @param waitedTicks 投完料到现在一共等了几刻
     * @param wanted      一共要几件
     */
    int nextWait(long waitedTicks, int wanted) {
        if (produced == 0) return clamp(cycleTicks);
        long rest = Math.max(1, wanted - produced);
        long perItem = Math.max(1, waitedTicks / produced);
        return clamp(perItem * rest);
    }

    /** 这次一共新出了几件（开工前就在出口里的不算）。 */
    int produced() {
        return produced;
    }

    /** 一共收进背包几件（含开工前就在出口里、顺手一起拿走的）。 */
    int collected() {
        return collected;
    }

    /** 回去看了几回。 */
    int looks() {
        return looks;
    }

    /** 最后一眼收完还留在出口里几件。 */
    int leftInExit() {
        return leftInExit;
    }

    private static int clamp(long ticks) {
        return (int) Math.max(MIN_WAIT_TICKS, Math.min(MAX_WAIT_TICKS, ticks));
    }
}
