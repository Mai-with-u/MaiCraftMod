// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.maiwithu.maicraft.kernel.interrupt.ControlLoop.RetryWait;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 生存需求的临时任务没做成后的等待：自救没成功先缓一缓，处境变了再试，变得更急马上再试；
 * 接连没做成就缓得更久，最长五分钟。控制循环挑谁插进来时问它"这个需求是不是还在等"。
 * 只在客户端线程读写。
 */
final class NeedRetries {

    /**
     * 临时任务没做成后，同一个需求在同样急（或更缓）时先等多少刻再插进来：五秒。
     * 类别：玩家常识——自救没成功就先缓一缓，处境变了再试；处境变得更急则马上再试。
     */
    static final long RETRY_AFTER_FAILED_TICKS = 100;

    /** 接连没做成时等待逐次加倍，最长五分钟：弄不到吃的这类一时解决不了的事，不每隔几秒就打断一次手上的活。 */
    static final long MAX_RETRY_AFTER_FAILED_TICKS = 20L * 60 * 5;

    /** 临时任务刚没做成的需求：在这一刻之前、且不比当时更急，就先不插。 */
    private final Map<SurvivalNeed, Backoff> backoffs = new HashMap<>();
    /** 各需求接连没做成的次数；处境解除或做成一次就清零。 */
    private final Map<SurvivalNeed, Integer> failureStreaks = new HashMap<>();

    // 记一次没做成：这个需求先缓一阵，接连没做成就缓得更久；为什么没做成一并记下，面板上照实说。
    void failed(SurvivalNeed need, Urgency urgency, long gameTick, String why) {
        int streak = failureStreaks.merge(need, 1, Integer::sum);
        long delay = Math.min(RETRY_AFTER_FAILED_TICKS << Math.min(streak - 1, 12), MAX_RETRY_AFTER_FAILED_TICKS);
        backoffs.put(need, new Backoff(gameTick + delay, urgency, why));
    }

    // 临时任务刚没做成的需求：没到时间、处境也没更急，就先不插。
    boolean waiting(SurvivalNeed need, Urgency urgency, long gameTick) {
        Backoff backoff = backoffs.get(need);
        if (backoff == null) return false;
        if (gameTick >= backoff.untilTick() || urgency.ordinal() > backoff.urgency().ordinal()) {
            backoffs.remove(need);
            return false;
        }
        return true;
    }

    /** 这个需求是不是正在等着再试（不论到没到时间）。 */
    boolean has(SurvivalNeed need) {
        return backoffs.containsKey(need);
    }

    /** 处境已经不需要处理：之前没做成留下的等待一并作废，下次需要时马上能插。 */
    void settled(SurvivalNeed need) {
        backoffs.remove(need);
        failureStreaks.remove(need);
    }

    /** 做成了一次：之前接连没做成的次数清零，下次再出事从五秒等起。 */
    void succeeded(SurvivalNeed need) {
        failureStreaks.remove(need);
    }

    /** 角色不在了：等待作废，接连没做成的次数留着。 */
    void clearWaits() {
        backoffs.clear();
    }

    /** 死过一回：等待与次数一起清零，重生后的处境从头判。 */
    void clear() {
        backoffs.clear();
        failureStreaks.clear();
    }

    /** 正在等着再试的需求，按给定的登记顺序。 */
    List<RetryWait> waits(List<SurvivalNeed> needs) {
        List<RetryWait> waits = new ArrayList<>();
        for (SurvivalNeed need : needs) {
            Backoff backoff = backoffs.get(need);
            if (backoff != null) {
                waits.add(new RetryWait(need.name(), backoff.urgency(), backoff.untilTick(),
                        failureStreaks.getOrDefault(need, 0), backoff.why()));
            }
        }
        return List.copyOf(waits);
    }

    // 临时任务没做成的原因：有问题就用问题本身的说法，没有就用结果的一句话结论。
    static String failureOf(TaskResult result) {
        return result.problem() != null ? result.problem().message() : result.summary();
    }

    /** 一个需求的临时任务没做成后的等待：到哪一刻为止、当时有多急、为什么没做成。 */
    private record Backoff(long untilTick, Urgency urgency, String why) {}
}
