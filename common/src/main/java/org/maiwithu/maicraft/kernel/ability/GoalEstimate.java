// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.ability;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 一份估算：一件事此刻要花多大功夫——要不要离开这里、知不知道东西在哪、怎么弄、最远走多远、粗估几秒。
 *
 * <p>只给 LLM 看，用来自己排先后：不拦任何操作，不当期限，不当判卡依据，也不改动手时走的路。
 * 动手时以现场为准。估算不进目标结果，不存盘。
 *
 * @param effort         档位
 * @param what           估的是什么：物品与件数，或这一步要做的事
 * @param how            一句话：走哪条路、缺的原料从哪来；弄不到、估不了时写为什么
 * @param farthestBlocks 最远要走到离现在多远（格）；要出去找时是最远找多远；不用离开、弄不到、估不了时为 null
 * @param roughSeconds   粗估秒数，只在不用离开、知道在哪两档给；其余为 null
 * @param items          一次要几样时每样一份；只要一样时为空
 * @param stillLooking   估不了是因为附近还没扫完：等一会儿再估可能就有了。不对外写出
 */
public record GoalEstimate(Effort effort, String what, String how, Double farthestBlocks, Integer roughSeconds,
        List<GoalEstimate> items, boolean stillLooking) {

    /**
     * 档位，从轻到重排：合起来时取最重的。估不了排在要出去找前面——已经知道有一处要出门找，
     * 就照实说要出去找，不因为另一处还没看完改说估不了。
     */
    public enum Effort {
        /** 不用离开：要的东西、缺的原料都在身上，或在身边几格以内。 */
        READY,
        /** 知道在哪，走过去拿：看得见的、记得的、网络里查得到的。 */
        NEARBY,
        /** 估不了：这项能力还不估，或附近还没看完。 */
        UNKNOWN,
        /** 附近没见到，要走着找、碰上才有。 */
        SEARCH,
        /** 按这次的写法弄不到。 */
        BLOCKED;

        /** 两档里重的那一档。 */
        public Effort heavier(Effort other) {
            return compareTo(other) >= 0 ? this : other;
        }

        /** 对外的写法：ready / nearby / unknown / search / blocked。 */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** 给得出秒数的两档：不用离开、知道在哪。 */
        public boolean timed() {
            return this == READY || this == NEARBY;
        }
    }

    /** 身边几格以内算"不用离开"：伸手、走两步就够得着，和用身边的工作台一个感觉。C 玩家常识。 */
    public static final double READY_WITHIN_BLOCKS = 8;
    /** 走路每秒约几格：把代价里的格数折成秒。F 游戏事实（原版步行速度）。 */
    public static final double BLOCKS_PER_SECOND = 4.3;
    /** 粗估秒数取整到几秒：粗估只说个大概，不装精确。 */
    public static final int SECONDS_STEP = 5;

    /** 知道东西在哪时按距离分档：身边几格以内不用离开，再远要走过去。 */
    public static Effort byDistance(double blocks) {
        return blocks <= READY_WITHIN_BLOCKS ? Effort.READY : Effort.NEARBY;
    }

    /** 粗估秒数：取整到 5 秒，最少 5 秒。 */
    public static int roughSeconds(double seconds) {
        long steps = Math.round(seconds / SECONDS_STEP);
        return (int) Math.max(1, steps) * SECONDS_STEP;
    }

    public GoalEstimate {
        Objects.requireNonNull(effort, "effort");
        Objects.requireNonNull(what, "what");
        how = how == null ? "" : how;
        items = items == null ? List.of() : List.copyOf(items);
        // 秒数只在算得出来的两档给；不用离开时不说走多远。要出门找、弄不到、估不了不编数字。
        if (!effort.timed()) roughSeconds = null;
        if (effort == Effort.READY || effort == Effort.BLOCKED || effort == Effort.UNKNOWN) farthestBlocks = null;
    }

    /** 估不了：说清为什么（这项能力还不估、原料链转回了自己）。 */
    public static GoalEstimate unknown(String what, String why) {
        return new GoalEstimate(Effort.UNKNOWN, what, why, null, null, List.of(), false);
    }

    /** 附近还没看完：等一会儿再估可能就有了。 */
    public static GoalEstimate stillLooking(String what, String why) {
        return new GoalEstimate(Effort.UNKNOWN, what, why, null, null, List.of(), true);
    }

    /** 这项能力还没接估算。 */
    public static GoalEstimate notEstimated(String what) {
        return unknown(what, "这项能力还不估");
    }

    /**
     * 一次要几样：每样各估一份，合起来取最重的档位、秒数相加、最远取最大；
     * 只要一样时直接就是那一样。
     */
    public static GoalEstimate several(String what, List<GoalEstimate> parts) {
        if (parts.size() == 1) return parts.getFirst();
        Effort effort = Effort.READY;
        double farthest = 0;
        int seconds = 0;
        boolean looking = false;
        for (GoalEstimate part : parts) {
            effort = effort.heavier(part.effort());
            if (part.farthestBlocks() != null) farthest = Math.max(farthest, part.farthestBlocks());
            if (part.roughSeconds() != null) seconds += part.roughSeconds();
            looking |= part.stillLooking();
        }
        return new GoalEstimate(effort, what, "一次要 " + parts.size() + " 样，逐样看 items",
                farthest > 0 ? farthest : null, seconds, parts, looking);
    }
}
