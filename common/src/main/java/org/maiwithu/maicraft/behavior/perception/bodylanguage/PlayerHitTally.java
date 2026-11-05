// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

/**
 * 被同一名玩家打中的每一下归拍一下还是连打，全仓只有这一份账。
 *
 * <p>连打：这名玩家 10 秒内打中角色至少 3 下、且这 10 秒里让角色累计掉了至少 6 点血；或者这一下掉了血、让角色的有效血量
 * （护甲折算后）落在拒战线以下；或者是箭、三叉戟这类会掉血的投射物（射你不是打招呼）。从变成连打的那一下起，
 * 他接下来打中的都算连打，直到他 10 秒没再打中。其余都是拍一下，不看他拿的是什么：拿着剑砍一下多半也只是叫你。
 * 两条一起看：只看拿没拿武器，一剑就成了真打；只看下数，朋友喂喂喂连拍三下就成了挑衅；掉血是硬指标，
 * 空手连拍二十下才掉得到六点。
 *
 * <p>这本账只决定角色身体怎么反应、事件走哪条，不替 LLM 判断他是好意还是恶意。正在和他切磋时不记，
 * 切磋结束从零记（{@link #forget}）。只在客户端线程读写。
 */
public final class PlayerHitTally {

    /** 看多久里的（刻）：10 秒。 */
    static final int WINDOW_TICKS = 200;
    /** 10 秒内打中几下、累计掉多少血（点）算连打。 */
    static final int BEATING_HITS = 3;
    static final float BEATING_LOST = 6f;

    /** 这一下归哪类。 */
    public enum Verdict {
        /** 拍一下："喂，看我"，回头看他，不退开、不停工。 */
        POKE,
        /** 连打：真打，交给挨打事件与自卫。 */
        BEATING
    }

    private final Map<UUID, Ledger> ledgers = new HashMap<>();

    /**
     * 记被他打中的一下，返回它归哪类。
     *
     * @param lost             这一下让角色掉了多少血（点）；没掉为 0
     * @param effectiveAfter   这一下之后角色的有效血量（护甲折算后）
     * @param harmfulMissile   是箭、三叉戟这类投射物且掉了血
     */
    public Verdict hit(UUID who, long tick, float lost, double effectiveAfter, boolean harmfulMissile) {
        Ledger ledger = ledgers.computeIfAbsent(who, key -> new Ledger());
        // 他 10 秒没再打中：连打到此为止，账从这一下重新记。
        if (ledger.beating && tick - ledger.lastHit > WINDOW_TICKS) ledger.beating = false;
        ledger.lastHit = tick;
        ledger.blows.addLast(new Blow(tick, lost));
        while (!ledger.blows.isEmpty() && tick - ledger.blows.peekFirst().tick() > WINDOW_TICKS) {
            ledger.blows.removeFirst();
        }
        float total = 0;
        for (Blow blow : ledger.blows) total += blow.lost();
        boolean beating = ledger.beating || harmfulMissile
                || lost > 0 && effectiveAfter < ThreatAssessment.RETREAT_LINE_EFFECTIVE_HEALTH
                || ledger.blows.size() >= BEATING_HITS && total >= BEATING_LOST;
        ledger.beating = beating;
        return beating ? Verdict.BEATING : Verdict.POKE;
    }

    /** 切磋开打、收场时从零记他：切磋里的出手不算，切磋完再打按新的一笔账。 */
    public void forget(UUID who) {
        ledgers.remove(who);
    }

    /** 换世界、断线、死了：账全部丢掉。 */
    public void clear() {
        ledgers.clear();
    }

    /** 打中的一下：哪一刻、掉了多少血。 */
    private record Blow(long tick, float lost) {}

    /** 一个人这 10 秒的账：每一下的刻与掉血、最后一下在哪刻、是不是已经算连打。 */
    private static final class Ledger {
        final Deque<Blow> blows = new ArrayDeque<>();
        long lastHit = Long.MIN_VALUE;
        boolean beating;
    }
}
