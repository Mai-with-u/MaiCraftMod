// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.hunger;

import java.util.Objects;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 临时吃饭任务：身上有合适的吃的就吃一口；没有就做一个有预算的弄吃的，弄到再吃。
 *
 * <p>一次只吃一口：吃完还饿，饥饿需求下一次报急时自然再插进来，按急迫程度决定是立刻接着吃
 * 还是等手上的活到了空当。吃不上、弄不到就以没做成收场并发事件把事实告诉 LLM，
 * 控制循环据此缓一阵再插；主任务不受影响，饿着肚子回去继续干活。同一回饿只说一次，同一段失败说明
 * 十分钟内跨回也不重说（更急了只说更急），弄吃的问遍各处都没有就歇一分钟再找（见 {@link HungerBout}）。
 *
 * <p>弄吃的时弄到第一件这会儿能吃的就回来先吃这一口，不等凑满一次要弄的件数：
 * 三颗心时揣着刚烤好的熟羊肉接着去追鸡，只会在半路被怪打断。
 *
 * <p>确认只认真的：一口的动作做完后再读一次处境，饱食度过线才说"吃上了"——
 * "还不算饿"从来不是"吃上了"的证据。谎报一次进展，死亡态不再恢复的饿度会让
 * 同一条假消息每刻重播，主任务也被牵着反复空转。
 */
public final class EatSoonTask extends PhasedTask<EatSoonTask.Phase> {

    /** 吃饭任务的阶段：吃一口；身上没合适的吃的时先弄吃的。 */
    enum Phase { EAT, FETCH }

    /** 弄吃的的默认预算：一分钟。只为弄一口饭，不为它跑半个地图。 */
    static final long DEFAULT_FETCH_BUDGET_TICKS = 20L * 60;

    /** 同一口接连吃不成几次就收手：游戏一直不让吃时如实交代，不在这里无限重试。 */
    static final int MAX_FAILED_BITES = 3;

    /** 连续半分钟没吃上也没弄到才算卡住。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;

    /**
     * 吃与弄吃的怎么落地。生产实现按处境挑一件随身食物、换到主手原生吃一口（主手那堆
     * 吃完了回到换手一步换下一堆）；弄吃的还没接上时给 null，任务会如实报告。
     */
    public interface FoodMoves {
        /** 按此刻的饥饿处境挑一件随身食物吃一口；身上没有这会儿该吃的东西时为 null。 */
        Action eating(HungerNeed.Facts hunger);

        /** 弄一件能直接吃的：给预算（刻）；还没接入时为 null。 */
        Action fetching(long budgetTicks);

        /**
         * 身上此刻有没有这会儿该吃的一口（和 {@link #eating} 挑的是同一样）。弄吃的路上每刻问一句，
         * 弄到第一件就回来先吃；饥饿需求歇着不找吃的时，也靠它看身上是不是已经有吃的了。
         */
        default boolean carriesBite(HungerNeed.Facts hunger) {
            Action bite = eating(hunger);
            if (bite == null) return false;
            bite.close();
            return true;
        }
    }

    private final FoodMoves moves;
    private final HungerNeed.ReadsFacts reader;
    private final TaskEventSink events;
    private final long fetchBudgetTicks;
    /** 这一回饿：跨几次插进来记着说没说过、该不该歇着。 */
    private final HungerBout bout;

    /** 最近一刻读到的饥饿处境：进入"吃一口"时按它挑吃什么。 */
    private HungerNeed.Facts latest;
    private int failedBites;
    private boolean fetchedSomething;

    EatSoonTask(FoodMoves moves, HungerNeed.ReadsFacts reader, TaskEventSink events, HungerNeed.Facts initial) {
        this(moves, reader, events, initial, new HungerBout());
    }

    EatSoonTask(FoodMoves moves, HungerNeed.ReadsFacts reader, TaskEventSink events,
                HungerNeed.Facts initial, HungerBout bout) {
        this(moves, reader, events, initial, bout, DEFAULT_FETCH_BUDGET_TICKS);
    }

    EatSoonTask(FoodMoves moves, HungerNeed.ReadsFacts reader, TaskEventSink events,
                HungerNeed.Facts initial, HungerBout bout, long fetchBudgetTicks) {
        super("吃饭", Phase.EAT, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.moves = Objects.requireNonNull(moves, "moves");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.events = Objects.requireNonNull(events, "events");
        this.bout = Objects.requireNonNull(bout, "bout");
        this.latest = initial;
        this.fetchBudgetTicks = fetchBudgetTicks;
    }

    @Override
    protected Action enter(Phase phase) {
        if (phase == Phase.FETCH) {
            return moves.fetching(fetchBudgetTicks);
        }
        return latest == null ? null : moves.eating(latest);
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        // 每刻先看还饿不饿：已经不饿（或角色不在了）就收场，不为了"吃过"而吃。
        // 身上还有能吃的、只是饱食度还没掉到线以下时不在这里收工——那正是这次被插进来的
        // 原因（找空当垫一口），真咽下一口才算数。
        latest = reader.read(context);
        // 饱食度没掉到线以下但身上还有能吃的，assess 给 LATER，不会走到这里——
        // 那正是这次被插进来的原因（找空当垫一口），真咽下一口才算数。
        if (latest == null || HungerNeed.assess(latest) == null) {
            return Next.done(TaskResult.done("已经不饿了，回去干活"));
        }
        return phase == Phase.EAT ? eat(context) : fetch(context);
    }

    // 吃一口：身上没合适的吃的先去弄；进食流程没接上就如实交代；咽下一口后再读处境，
    // 饱食度过线才说"吃上了"，还饿也照实说吃了一口，由饥饿需求下一次报急再接着吃。
    private Next<Phase> eat(TickContext context) {
        if (action() == null) {
            if (fetchedSomething) {
                return giveUp(Problem.of(Problem.Kind.NEED_ITEM,
                        "弄来的东西不是这会儿能直接吃的", "给角色一些普通食物"), context);
            }
            if (latest != null && latest.carryingEdible()) {
                // 身上有普通食物却给不出吃的动作（进食流程没接上）：以"不支持"收场，控制循环据此缓一阵再插，
                // 不在每个空当都插进来又立刻收场。真饿着才发事件把事实告诉 LLM，只是想垫一口就不打扰；
                // 同一回饿只说一次。
                if (hungry(latest)) {
                    tellUnhandled("饿了但进食还没接上流程，吃不了一口；主任务照常继续",
                            "进食还是没接上流程，吃不了一口（%s说过了）", "；主任务照常继续", context);
                }
                return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED,
                        "身上有吃的但没法自动吃，等下一个空当或人工指派进食"));
            }
            return Next.go(Phase.FETCH, "身上没有这会儿该吃的东西，去弄");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                // 这一口吃完了再读一次处境：饱食度过线才说"吃上了"；还饿就如实交代，
                // 不把一次动作收尾当成不饿了——死亡态饿度不恢复，谎报会每刻重播。
                HungerNeed.Facts after = reader.read(context);
                latest = after != null ? after : latest;
                if (after != null && !hungry(after)) {
                    yield Next.done(TaskResult.done("吃上了，不再饿着"));
                }
                yield Next.done(TaskResult.done("吃了一口；还饿的话下一次报急再接着吃"));
            }
            case ActionStatus.Failed failed -> {
                failedBites++;
                if (failedBites >= MAX_FAILED_BITES) {
                    yield giveUp(failed.problem(), context);
                }
                // 重新进入"吃一口"：收尾这一口的动作，按此刻的处境重新挑一件再吃。
                yield Next.go(Phase.EAT, "这一口没吃成，再试：" + failed.problem().message());
            }
        };
    }

    // 弄吃的：没接上或弄不到都如实收场；弄到第一件这会儿能吃的就回来先吃，不等凑满。
    private Next<Phase> fetch(TickContext context) {
        if (action() == null) {
            return giveUp(Problem.of(Problem.Kind.NEED_ITEM,
                    "饿了，身上没有这会儿该吃的东西，弄吃的还没接上", "给角色一些普通食物"), context);
        }
        // 上一刻打到的第一块肉、烤好的第一块熟肉、取到的第一块面包已经在身上：换阶段时收掉弄吃的动作，回来先吃这一口。
        // 凑够口粮的事不在饿着、伤着的时候做，吃完还饿由下一次报急再插。
        if (moves.carriesBite(latest)) {
            fetchedSomething = true;
            return Next.go(Phase.EAT, "弄到能吃的了，先回来吃一口");
        }
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                fetchedSomething = true;
                yield Next.go(Phase.EAT, "弄到吃的了，回来吃");
            }
            case ActionStatus.Failed failed -> {
                // 问遍各处都弄不到（不是弄到一半超了预算）：这一回饿先歇一分钟再找，不几秒一问把手上的活停停走走。
                boolean nothingFound = failed.problem().kind() != Problem.Kind.STUCK;
                if (nothingFound) bout.restFromFetching(context.gameTick(), HungerNeed.assess(latest));
                yield giveUp(failed.problem(), nothingFound, context);
            }
        };
    }

    private Next<Phase> giveUp(Problem problem, TickContext context) {
        return giveUp(problem, false, context);
    }

    // 吃不上：以没做成收场，主任务照常继续；要不要把事实告诉 LLM 交给 tellUnhandled 按"近来说过没有"定。
    private Next<Phase> giveUp(Problem problem, boolean resting, TickContext context) {
        // 为回血吃（不饿、伤得重）和真饿着分开说，不把残血想吃一口说成饿了。
        String why = latest != null && !hungry(latest) && latest.eatsToHeal() ? "伤得重想吃点东西回血，但没吃上：" : "饿了但没吃上：";
        String then = resting ? "；先回去继续干活，过一分钟再找吃的" : "；先回去继续干活";
        tellUnhandled(why + problem.message(), "还是没吃上，原因没变（%s说过了）", then, context);
        return Next.fail(problem);
    }

    // 把没吃上的事实告诉 LLM：这一回饿说过了不再说；这段失败说明近来（十分钟内，跨回也算）说过、只是处境更急了，
    // 就只说"更急了"和哪些没变，不把上千字的各来源原因再抄一遍；窗口过了或原因变了才完整说。
    // unchanged 里的 %s 填"前面那份是多久前说的"，例如"刚才""4 分钟前"。
    private void tellUnhandled(String full, String unchanged, String then, TickContext context) {
        long now = context.gameTick();
        switch (bout.tell(latest == null ? null : HungerNeed.assess(latest), full, now)) {
            case FULL -> events.publish(TaskEvent.Kind.NEED_UNHANDLED, full + then);
            case WORSE -> events.publish(TaskEvent.Kind.NEED_UNHANDLED,
                    worseSituation() + "；" + unchanged.formatted(ago(bout.ticksSinceFailureTold(now))) + then);
            case NONE -> { }
        }
    }

    // 更急了说的新事实：饿到掉血，或饿到不能疾跑；读不到处境时只说更急。
    private String worseSituation() {
        if (latest == null) return "饿得更急了";
        return latest.losingHealth()
                ? "饿得更急了：饱食度 " + latest.food() + "/20，已经在掉血"
                : "饿得更急了：饱食度 " + latest.food() + "/20，已经不能疾跑";
    }

    // 前面那份说明是多久前的：不满一分钟说"刚才"，其余按整分钟。
    private static String ago(long ticks) {
        return ticks < 20L * 60 ? "刚才" : ticks / (20L * 60) + " 分钟前";
    }

    /** 真饿着：饱食度掉到疾跑线及以下，或身体已经在扣血。只是没吃饱不算。 */
    private static boolean hungry(HungerNeed.Facts facts) {
        return facts.food() <= HungerNeed.SPRINT_FLOOR || facts.losingHealth();
    }
}
