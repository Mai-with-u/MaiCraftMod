// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fight;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.combat.Battle;
import org.maiwithu.maicraft.behavior.combat.Battlefield;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.Footwork;
import org.maiwithu.maicraft.behavior.combat.SparPartner;
import org.maiwithu.maicraft.behavior.combat.Sparring;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 切磋：和点名的一位玩家友好过招。认人 → 开打（同一个战斗引擎、同一套出刀节拍、举盾、跳劈）→ 收手退开。
 *
 * <p>和点名打玩家不同：点名就是许可；不撤离，拒战线不管用，停手线管；只用近战，他先拉弓才拿弓；
 * 出手前估这一刀会把他打倒就不砍。任何一方到停手线、他停手、他走开、半分钟谁都没打中谁，就停手退到他够不着外。
 * 开打时登记"正在和他切磋"，收场时清掉：这期间他打中角色的不算拍一下、不发挨打事件、自卫不为他插进来。
 */
final class SparTask extends PhasedTask<SparTask.Phase> implements ThreatResponder {

    /** 认人 → 打 → 收手退开。 */
    enum Phase { CHECK, FIGHT, BACK_OFF }

    /** 收手后最多退几刻：退到他够不着就停。 */
    static final int BACK_OFF_TICKS = 40;

    private final SparInput input;
    private final CombatSenses senses;
    private final FightMoves moves;
    private final SparView view;
    private final SparPartner partner;
    private SparView.Opponent opponent;
    private SparView.Reading last;
    private Battle battle;
    private Action battleHand;
    private Action tie;
    private long startedAt;
    private long lastMyHit;
    private int hits;
    private boolean theyShot;
    private String weapon = "空手";
    private double myHealth;
    private Sparring.Ending ended;
    private int backedOff;

    /**
     * 结果细节：拿什么打的、我打中他几下，和切磋怎么收的。
     *
     * @param spar 收手原因（`ended_by`）、他打中我几下、各剩多少血（读不到他的血为空）、和谁打的
     */
    record SparDetails(String weapon, int hits, Spar spar) implements ResultDetails {}

    /**
     * 切磋这一场怎么收的。
     *
     * @param opponent 和谁打的；没认出点名的玩家（没开打就收场）为空，结果里不写
     */
    record Spar(String endedBy, int hitsTaken, double myHealth, Double theirHealth, OpponentPlayer opponent) {}

    /**
     * 切磋对手是哪位玩家：点名时给的是观察编号，宿主光看目标认不出是谁，按这里的 UUID 记这一局和谁打的。
     *
     * @param name 玩家名
     * @param uuid 玩家的 UUID
     */
    record OpponentPlayer(String name, String uuid) {}

    SparTask(SparInput input, CombatSenses senses, FightMoves moves, SparView view, SparPartner partner) {
        super("切磋", Phase.CHECK, new ProgressTracker(Sparring.NO_HITS_TICKS + 200, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.senses = Objects.requireNonNull(senses, "senses");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.view = Objects.requireNonNull(view, "view");
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    @Override protected Action enter(Phase phase) {
        return null;
    }

    // 开打时建好的引擎和"正在切磋"的登记带进打的阶段；收手退开时引擎收尾，登记照样带着，任务结束才清。
    @Override protected boolean carriesInto(Phase nextPhase, Action hand) {
        return hand == tie || nextPhase == Phase.FIGHT && hand == battleHand;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case CHECK -> check(context);
            case FIGHT -> fight(context);
            case BACK_OFF -> backOff(context);
        };
    }

    // 认人：点名的要是此刻在这个世界里的一位玩家；我的血已经在停手线下就不开打。
    private Next<Phase> check(TickContext context) {
        opponent = view.find(context, input.opponent());
        if (opponent == null) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE, "找不到要切磋的玩家：走了、下线了，或者点名的不是玩家",
                    "observe 看看他在不在，用玩家名或他的观察编号点名"));
        }
        myHealth = view.myHealth(context);
        if (myHealth <= input.stopAt()) {
            return Next.done(TaskResult.done("没开打：我只剩 " + points(myHealth) + " 点，已经在停手线下"));
        }
        partner.begin(opponent.uuid());
        startedAt = context.gameTick();
        lastMyHit = startedAt;
        battle = new Battle(senses, moves, new SparRoster(), ThreatAssessment.VIGILANCE_RADIUS);
        battleHand = battle.asAction();
        hold(battleHand);
        tie = new EndsSpar();
        hold(tie);
        return Next.go(Phase.FIGHT, "和玩家 " + opponent.name() + " 切磋，停手线 " + input.stopAt() + " 点");
    }

    // 每刻先看该不该收手（这一刀会把他打倒就不砍），不收再交引擎打一刻。
    private Next<Phase> fight(TickContext context) {
        long now = context.gameTick();
        last = view.read(context, opponent.entityId());
        myHealth = view.myHealth(context);
        if (last == null) return stop(Sparring.Ending.THEY_LEFT);
        if (last.drawingBow()) theyShot = true;
        long theyHit = Math.max(startedAt, partner.lastHitTaken());
        Optional<Sparring.Ending> ending = Sparring.ending(new Sparring.Moment(now, myHealth, last.health(),
                last.distance(), last.reachesMe(), last.standsDown(), theyHit, Math.max(theyHit, lastMyHit),
                view.myBlow(context)), input.stopAt());
        if (ending.isPresent()) return stop(ending.get());
        Battle.Moment moment = battle.tick(context);
        if (moment.struck() || moment.fired()) weapon = moves.mainHand(context);
        if (moment.struck()) {
            hits++;
            lastMyHit = now;
        }
        if (moment.progressed()) recordProgress(moment.doing());
        return moment.state() == Battle.State.FINISHED ? stop(Sparring.Ending.THEY_LEFT) : Next.stay();
    }

    private Next<Phase> stop(Sparring.Ending ending) {
        ended = ending;
        return Next.go(Phase.BACK_OFF, "停手：" + ending.words);
    }

    // 收手：放下盾，面朝他往后退到他够不着的地方，看着他；退够了或退了两秒就收场。
    private Next<Phase> backOff(TickContext context) {
        moves.lowerGuard();
        SparView.Reading now = opponent == null ? null : view.read(context, opponent.entityId());
        if (now != null && now.reachesMe() && ++backedOff < BACK_OFF_TICKS) {
            moves.step(context, opponent.entityId(), new Footwork.Step(now.awayX(), now.awayZ(), false, false));
            return Next.stay();
        }
        if (opponent != null) moves.step(context, opponent.entityId(), Footwork.Step.STAND);
        if (now != null) last = now;
        partner.end();
        return Next.done(TaskResult.done(summary()));
    }

    // 那句话：谁先停、各打中几下、各剩多少。
    private String summary() {
        String theirs = last == null || Double.isNaN(last.health()) ? "看不到他的血" : "他剩 " + points(last.health()) + " 点";
        return "切磋结束：" + ended.words + "；我打中他 " + hits + " 下，他打中我 " + partner.hitsTaken() + " 下；我剩 "
                + points(myHealth) + " 点，" + theirs;
    }

    private static String points(double health) {
        return health == Math.rint(health) ? String.valueOf((int) health) : String.format(Locale.ROOT, "%.1f", health);
    }

    @Override protected ResultDetails details() {
        Sparring.Ending by = ended == null ? Sparring.Ending.CANCELLED : ended;
        Double theirs = last == null || Double.isNaN(last.health()) ? null : last.health();
        return new SparDetails(weapon, hits, new Spar(by.value, partner.hitsTaken(), myHealth, theirs, opponentPlayer()));
    }

    // 认出了点名的玩家就把他的名字和 UUID 写进结果：不管是打完、被取消还是没开打（血已在停手线下），
    // 宿主都能按 UUID 记下这一局是和谁；没认出（点名的不是玩家、人不在）时不写，不拿名字凑。
    private OpponentPlayer opponentPlayer() {
        if (opponent == null || opponent.uuid() == null) return null;
        return new OpponentPlayer(opponent.name(), opponent.uuid().toString());
    }

    // 切磋自己接得住这名对手：别的怪真打过来时自卫照插。
    @Override public boolean confidentAgainstCurrentThreats() {
        return true;
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case CHECK -> "认人";
            case FIGHT -> opponent == null ? "切磋" : "和玩家 " + opponent.name() + " 切磋";
            case BACK_OFF -> "收手退开";
        };
    }

    // 名单：只打他；别的怪真打过来照样还手。不撤离，他没先拉弓就只用近战。
    private final class SparRoster implements Battlefield.Roster {
        @Override public boolean wanted(CombatSenses.Threat threat, boolean attacker, boolean chasing) {
            return threat.entityId() == opponent.entityId()
                    || attacker && threat.kind() != ThreatAssessment.Kind.PLAYER;
        }

        @Override public boolean primary(CombatSenses.Threat threat) {
            return threat.entityId() == opponent.entityId();
        }

        @Override public List<Integer> namedIds() {
            return List.of(opponent.entityId());
        }

        @Override public boolean standsGround() {
            return true;
        }

        @Override public boolean meleeOnly() {
            return !theyShot;
        }
    }

    /** "正在和他切磋"的登记：任务怎么收场（打完、被取消、角色没了）都在收尾时清掉，不留到下一件事。 */
    private final class EndsSpar implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.running();
        }

        @Override public void close() {
            partner.end();
        }

        @Override public String describe() {
            return "记着在和他切磋";
        }
    }
}
