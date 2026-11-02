// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.Locale;
import java.util.random.RandomGenerator;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 闲时看的节奏：刚闲下来站多久、两回闲看隔多久、闲久了怎么放慢、同一样东西多久内不重看、读完多久还算数。
 * 全是纯函数，随机数从外面给；时长按刻算（一秒二十刻）。类别都是玩家常识，数值按所有者看画面的观感调。
 */
final class LookPace {

    /** 刚闲下来先站定多久：干完活先停一下，不是一闲就东张西望。 */
    static final int SETTLE_TICKS = 20;
    /** 两回闲看之间最短、最长隔多久：三到七秒。 */
    static final int GAP_MIN_TICKS = 60;
    static final int GAP_MAX_TICKS = 140;
    /** 闲了这么久之后间隔放慢一倍：一分钟。 */
    static final long SLOWER_AFTER_TICKS = 20 * 60;
    /** 闲了这么久之后只对说话的人、走近的玩家和声音有反应：五分钟（多半是 Agent 停了，挂机的人本来就站着）。 */
    static final long REACTIVE_ONLY_AFTER_TICKS = 20 * 60 * 5;
    /** 读完隔多久再动：零点二到零点五秒，人也是先看到再转头。 */
    static final int READ_DELAY_MIN_TICKS = 4;
    static final int READ_DELAY_MAX_TICKS = 10;
    /** 读后看的请求最多等多久：八秒后角色还没闲下来，那件事已经过去了。 */
    static final int REQUEST_EXPIRES_TICKS = 160;
    /** 正在看的这一下最少停多久才让给新请求：转到位后零点八秒；开着界面的一点五秒。 */
    static final int MIN_HOLD_TICKS = 16;
    static final int MIN_SCREEN_TICKS = 30;
    /** 读自己多久内不重开背包：主播 Agent 会定期看一眼自己，没有这条背包会被反复打开。 */
    static final int SELF_COOLDOWN_TICKS = 20 * 30;
    /** 读周围多久内不重看一遍。 */
    static final int SCENE_COOLDOWN_TICKS = 20 * 15;
    /** 同一个目标对象多久内不重看。 */
    static final int SAME_TARGET_COOLDOWN_TICKS = 20 * 20;
    /** 闲看转头的速度（度每秒）：慢慢地看。 */
    static final float GLANCE_DEGREES_PER_SECOND = 90f;
    /** 读后看转头的速度（度每秒）：有了要看的东西，转得快一些。 */
    static final float READ_DEGREES_PER_SECOND = 150f;

    private LookPace() {}

    /** 一个读后看的冷却：同样的读在这么多刻内不重看。 */
    record Cooldown(String key, int ticks) {}

    /** 下一回闲看隔多久：三到七秒随机；闲了一分钟后放慢一倍。 */
    static int glanceGap(long idleTicks, RandomGenerator random) {
        int gap = GAP_MIN_TICKS + random.nextInt(GAP_MAX_TICKS - GAP_MIN_TICKS + 1);
        return idleTicks >= SLOWER_AFTER_TICKS ? gap * 2 : gap;
    }

    /** 闲了五分钟后只对说话的人、走近的玩家和声音有反应，不再随便看。 */
    static boolean reactiveOnly(long idleTicks) {
        return idleTicks >= REACTIVE_ONLY_AFTER_TICKS;
    }

    /** 读完隔多久再动。 */
    static int readDelay(RandomGenerator random) {
        return READ_DELAY_MIN_TICKS + random.nextInt(READ_DELAY_MAX_TICKS - READ_DELAY_MIN_TICKS + 1);
    }

    /** 请求到了这一刻还没开始就作废。 */
    static boolean expired(long arrivedTick, long nowTick) {
        return nowTick - arrivedTick > REQUEST_EXPIRES_TICKS;
    }

    /** 这次读归哪一份冷却：自己、周围各一份，观察编号、地标、位置、配方按对象各一份。 */
    static Cooldown cooldownOf(WhatWasRead what) {
        return switch (what) {
            case WhatWasRead.Self self -> new Cooldown("self", SELF_COOLDOWN_TICKS);
            case WhatWasRead.Surroundings around -> new Cooldown("scene", SCENE_COOLDOWN_TICKS);
            case WhatWasRead.Seen seen -> new Cooldown("seen:" + seen.id().trim().toLowerCase(Locale.ROOT),
                    SAME_TARGET_COOLDOWN_TICKS);
            case WhatWasRead.Places places -> new Cooldown("places:" + placeKey(places), SAME_TARGET_COOLDOWN_TICKS);
            case WhatWasRead.RecipePage page -> new Cooldown("recipe:" + page.itemId() + (page.uses() ? ":uses" : ""),
                    SAME_TARGET_COOLDOWN_TICKS);
        };
    }

    /** 上次同样的读是 lastTick（没读过给 null），到 nowTick 还在冷却里没有。 */
    static boolean coolingDown(Long lastTick, long nowTick, int cooldownTicks) {
        return lastTick != null && nowTick - lastTick < cooldownTicks;
    }

    /** 在 min 到 max 刻之间随机取一个时长。 */
    static int between(int minTicks, int maxTicks, RandomGenerator random) {
        return minTicks + random.nextInt(maxTicks - minTicks + 1);
    }

    private static String placeKey(WhatWasRead.Places places) {
        if (places.places().isEmpty()) return "none";
        WorldPosition first = places.places().get(0);
        return first.dimension() + ":" + first.x() + "," + first.y() + "," + first.z() + "+" + places.places().size();
    }
}
