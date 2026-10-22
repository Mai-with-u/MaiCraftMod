// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 一回饿：从饿起来（或伤重想吃点回血）到不再需要吃为止。饥饿需求每次插进来都是新的吃饭任务，
 * 跨任务要记的几件事放在这里：这一回有没有跟 LLM 说过没吃上，同一段失败说明近来说过没有，
 * 弄吃的哪儿都没弄到之后歇到什么时候。
 *
 * <p>同一回饿只说一次：哪儿都弄不到吃的时，每次再试都是同一段上千字的说明，二十秒里刷三遍只会挤掉别的事实。
 * 处境变得更急（饿到不能疾跑、饿到掉血）是新的事实，照样再说，但只说"更急了"这一件，
 * 各来源没弄到的原因没变就不再抄一遍。
 *
 * <p>同一段失败说明跨回也不重说：吃一口腐肉过线、饿的这一回就结束，几分钟后又饿起来是新的一回，
 * 可各来源还是同样没有吃的，说明一字不差——十分钟内说过的不再发（实机 4 分钟后原样再来一遍，
 * 四份全堆进 LLM 的对话历史）。窗口过了才重新完整说一次，免得旧的那份早被整理掉后 LLM 再也不知道。
 *
 * <p>弄不到就歇一阵：附近、箱子里、网络里都没有，几秒后再问一遍多半还是没有，只会把手上的活停停走走。
 * 歇一分钟再找；处境更急了马上再试。只在客户端线程读写。
 */
final class HungerBout {

    /** 弄吃的哪儿都没弄到之后歇多久再找（刻）：一分钟。 */
    static final long REST_AFTER_NOTHING_FOUND_TICKS = 20L * 60;

    /** 同一段失败说明说过之后多久内不重说（刻）：十分钟。 */
    static final long SAME_FAILURE_WINDOW_TICKS = 20L * 60 * 10;

    /** 这次没吃上要怎么告诉 LLM。 */
    enum Telling {
        /** 完整说一遍：这段失败说明近来没说过。 */
        FULL,
        /** 失败说明近来说过、原因没变，只是处境更急了：只说更急这一件新事实。 */
        WORSE,
        /** 近来说过同样的事，不重说。 */
        NONE
    }

    /** 这一回已经把没吃上告诉 LLM 时有多急；还没说过为 null。 */
    private Urgency toldAt;
    /** 弄吃的哪儿都没弄到后歇到哪一刻；没在歇为 -1。 */
    private long restUntil = -1;
    /** 歇下时有多急：处境比这更急就不再歇。 */
    private Urgency restAt;

    /** 最近一次完整说的失败说明（跨回记着，不随一回饿结束清掉）；还没说过为 null。 */
    private String lastFailure;
    /** 最近一次完整说那一刻的游戏刻。 */
    private long lastFailureTick;
    /** 这段失败说明说到现在最急说到哪一档：处境没超过它就没有新事实。 */
    private Urgency lastFailureUrgency;

    /** 不饿了（吃饱、回过血线、重生）：这一回结束，下回饿了重新找；失败说明说没说过照窗口算，不在这里清。 */
    void over() {
        toldAt = null;
        restUntil = -1;
        restAt = null;
    }

    /**
     * 这次没吃上怎么告诉 LLM：同一回饿里同样急（或更缓）不再说；处境更急了、或新的一回饿时，再看这段失败说明
     * 近来说过没有——没说过完整说，说过了且只是更急了就只说更急，说过了且没更急就不说。
     *
     * @param urgency  此刻的饥饿急迫程度；不明时按最缓算
     * @param failure  这次没吃上的原因全文，比对"是不是同一段说明"用
     * @param gameTick 此刻的游戏刻，算说明说过多久了
     */
    Telling tell(Urgency urgency, String failure, long gameTick) {
        Urgency now = urgency == null ? Urgency.LATER : urgency;
        if (toldAt != null && now.compareTo(toldAt) <= 0) return Telling.NONE;
        toldAt = now;
        boolean toldRecently = failure.equals(lastFailure) && gameTick - lastFailureTick < SAME_FAILURE_WINDOW_TICKS;
        if (!toldRecently) {
            lastFailure = failure;
            lastFailureTick = gameTick;
            lastFailureUrgency = now;
            return Telling.FULL;
        }
        if (now.compareTo(lastFailureUrgency) > 0) {
            lastFailureUrgency = now;
            return Telling.WORSE;
        }
        return Telling.NONE;
    }

    /** 失败说明完整说出去之后过了多久（刻）：只说更急时告诉 LLM 前面那份是多久前的。 */
    long ticksSinceFailureTold(long gameTick) {
        return gameTick - lastFailureTick;
    }

    /** 弄吃的问遍各处都没弄到：从这一刻起歇一阵再找，记下当时有多急。 */
    void restFromFetching(long gameTick, Urgency urgency) {
        restUntil = gameTick + REST_AFTER_NOTHING_FOUND_TICKS;
        restAt = urgency == null ? Urgency.LATER : urgency;
    }

    /** 此刻还在歇：没到时候、处境也没更急。到了时候或更急了就不再歇。 */
    boolean resting(long gameTick, Urgency urgency) {
        if (restUntil < 0) return false;
        if (gameTick >= restUntil || urgency != null && urgency.compareTo(restAt) > 0) {
            restUntil = -1;
            restAt = null;
            return false;
        }
        return true;
    }
}
