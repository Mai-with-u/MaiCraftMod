// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.night;

import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;

import org.maiwithu.maicraft.kernel.interrupt.KeepsCompany;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 夜晚这项生存需求：到了能睡的时间，能弄到床就找空当去睡（夜间休息）；
 * 弄不到床时看处境——安全处或打得过就接着干活，露天又打不过才去备一张床来睡。夜里被打交给自卫，
 * 打不过由战斗引擎边打边撤，这里不再有"原地熬到天亮"的任务。
 * 今晚不去睡的时候——夜里自动去睡被 LLM 关掉（{@link AutoSleep}），或正陪着一个还醒着的人——
 * 不找床、也不备床：露天打不过同样不建任务、不占身体，挨打交给自卫与战斗引擎。
 */
public final class NightfallNeed implements SurvivalNeed {

    /** 夜晚的处境：能不能睡、身在何处、评估结论与自己的血甲。 */
    public record Facts(boolean sleepTime, boolean inShelter,
                        ThreatAssessment.Verdict verdict, double health, int armorPoints) {}

    /**
     * 夜晚的分流，纯函数：要插什么返回急迫程度，接着干活返回 null。
     *
     * @param goesToBed 今晚自己会去睡：夜里自动去睡开着、手上也不是陪着醒着的人的任务
     * @param bedReady  今晚有床：附近肯走过去的范围里有能用的床，或身上带着一张（身上有床恒为真）
     */
    public static Urgency branch(Facts facts, boolean goesToBed, boolean bedReady) {
        if (!facts.sleepTime()) {
            return null;
        }
        if (goesToBed && bedReady) {
            // 能弄到床就找空当去睡："找空当"只有主任务处在两个动作之间才插得进来，
            // 干活干到一半不会把人从活里拽走；没睡成也不影响主任务。身上带着床也走这一条：放下睡，不去备床。
            return Urgency.LATER;
        }
        if (facts.inShelter()) {
            // 地下矿道、屋里、照明充足：怪物进不来，接着干。
            return null;
        }
        if (facts.verdict() == ThreatAssessment.Verdict.OUTMATCHED && goesToBed) {
            // 露天、打不过、今晚又没床：不再原地熬到天亮，去备一张床来睡（跑路做床）；被打时自卫照插，撤离由战斗引擎做。
            return Urgency.SOON;
        }
        // 露天但打得过：边打边干。今晚不去睡（开关关了、陪着醒着的人）时露天打不过也走这里：
        // 备来的床反正不睡，备床只会把身体一次次借走（身上有床时当场做完、下一刻又建），交给自卫与战斗引擎应付。
        return null;
    }

    /** 读夜晚处境的接缝：生产实现读世界时间与脚下环境，测试给固定值。 */
    @FunctionalInterface
    public interface ReadsNight {
        Facts read(TickContext context);
    }

    /** 今晚有没有床的接缝：附近有能用的床、或身上带着床，都算有。生产实现由睡觉能力提供。 */
    @FunctionalInterface
    public interface ReadsBedAvailability {
        boolean bedReady(TickContext context);
    }

    /** 夜间休息怎么落地：生产实现由睡觉能力提供，去找床、睡下、回来。测试换替身。 */
    @FunctionalInterface
    public interface NightRestMoves {
        Task nightRest(TickContext context);
    }

    /** 没床时怎么备一张：生产实现由睡觉能力提供，经拿东西的引擎杀羊取毛、合成出一张床。测试换替身。 */
    @FunctionalInterface
    public interface PreparesNightBed {
        Task prepareBed(TickContext context);
    }

    private final ReadsNight reader;
    private final ReadsBedAvailability beds;
    private final NightRestMoves nightRest;
    private final PreparesNightBed bedPreparer;
    private final AutoSleep autoSleep;
    /** 上一回分流给出的急迫程度：创建任务时要按同一条分路走，不能不管分路先问要夜间休息。 */
    private Urgency lastDecision;

    public NightfallNeed(ReadsNight reader,
            ReadsBedAvailability beds, NightRestMoves nightRest, PreparesNightBed bedPreparer) {
        this(reader, beds, nightRest, bedPreparer, new AutoSleep());
    }

    /** @param autoSleep 夜里自动去睡的开关：关着时不去找床 */
    public NightfallNeed(ReadsNight reader,
            ReadsBedAvailability beds, NightRestMoves nightRest, PreparesNightBed bedPreparer, AutoSleep autoSleep) {
        this.reader = reader;
        this.beds = beds;
        this.nightRest = nightRest;
        this.bedPreparer = bedPreparer;
        this.autoSleep = autoSleep;
    }

    @Override public String name() { return "夜晚"; }

    @Override
    public Urgency urgency(TickContext context) {
        return urgency(context, null);
    }

    @Override
    public Urgency urgency(TickContext context, Task currentTask) {
        Facts facts = reader.read(context);
        if (facts == null) {
            lastDecision = null;
            return null;
        }
        // 夜里自动去睡被关掉（例如多人游戏有人说"晚上别睡"）：今晚不去睡，不找床也不备床；露天打不过不建任务。
        // 正陪着一个人（跟随）而他还醒着：同样今晚不去睡，不自己走开去睡、也不走开去杀羊做床，免得把人跟丢；
        // 他躺下了才去附近找床睡。不去睡时连附近有没有床都不用扫。
        boolean companionAwake = currentTask instanceof KeepsCompany company && !company.companionAsleep();
        boolean goesToBed = !companionAwake && autoSleep.enabled();
        lastDecision = branch(facts, goesToBed, goesToBed && beds.bedReady(context));
        return lastDecision;
    }

    @Override
    public Task createTask(TickContext context) {
        // 按分流的结论建任务：只有"能弄到床"这条分路走夜间休息，"露天又打不过、今晚又没床"走备床。
        // 开关拦住了选床分路、没拦住任务创建，夜里就会照睡照跳夜（实机 10-21 goal 246 之后两夜照睡）——
        // 所以这里不无条件先问夜间休息，而是看 urgency 刚判出的那条路。
        Urgency decision = lastDecision;
        if (decision == null) {
            // 没先问过 urgency：按开关补判一次（读不到处境就不建）；陪人与否只有 urgency 那一问看得到。
            Facts facts = reader.read(context);
            if (facts == null) return null;
            decision = branch(facts, autoSleep.enabled(), autoSleep.enabled() && beds.bedReady(context));
        }
        if (decision == Urgency.LATER) {
            Task rest = nightRest.nightRest(context);
            if (rest != null) {
                return rest;
            }
        }
        if (decision == Urgency.SOON) {
            // 备床的引擎还没接上（没进世界）：不占身体，接着干；被打交给自卫。
            return bedPreparer == null ? null : bedPreparer.prepareBed(context);
        }
        return null;
    }
}
