// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 战斗的手脚槽：一段走路的寻路与一下出手的攻击各占一个槽位，带着沿用、重算与被拒的记账。
 * 普通战斗（{@link Battle}）与打龙（{@link DragonCombat}）各持一份、互不合并——dragon.stand() 换普通打法
 * 接手时两边分别收尾；引擎只决定打谁、用什么姿势，走路与出手的纪律都归这里：同一件事、目标没挪远就沿用，
 * 挪开三格以上或换了事重算；出手同一下沿用，打中了清掉等下一下，被拒记下站位接下来绕开它，连着被拒到
 * 上限就报告放弃。
 *
 * <p>动手的接缝 {@link BattleMoves} 从构造函数传入；出手用哪个接缝方法（普通出手还是砍龙头）也由引擎
 * 每次传入。锚点只取水平位置：走位只看目标在平面上挪没挪远。
 */
public final class BattleLimbs {

    /** 目标在平面上挪开这么远才重新寻路（格）。 */
    private static final double REPLAN_AFTER = 3.0;
    /** 被拒一次后这么多刻里绕开刚才的站位，换个方位再出手。 */
    private static final int SIDESTEP_TICKS = 15;
    /** 绕开刚才站位多远（格）。 */
    private static final double SIDESTEP_CLEARANCE = 1.2;

    private final BattleMoves moves;
    /** 连着被拒这么多次算这只打不出去；不超过 Integer.MAX_VALUE 就永远不会因被拒放弃。 */
    private final int maxRejects;

    /** 脚上这段寻路；没在走为 null。 */
    private Action walk;
    /** 这段寻路在办的事；换了事就重算。 */
    private String walkKey;
    /** 起这段路时目标 anchor 的水平位置 {x, z}；走寻路之外起的路（撤离）没有。 */
    private double[] walkAnchor;
    /** 上一段寻路走不通：交给引擎按它的规矩记账（记成走不到、换站位）。 */
    private boolean lastWalkFailed;
    /** 手上这一下出手；没在出手为 null。 */
    private Action strike;
    /** 这一下打的是谁；换人先收手。 */
    private int strikeTarget = -1;
    /** 连着被拒了几次；打中一次清零。 */
    private int rejects;
    /** 被拒的那个站位 {x, z}，还要绕开它几刻。 */
    private double[] sidestepFrom;
    private int sidestepTicks;

    /** @param maxRejects 连着被拒这么多次算打不出去，swing 会报告放弃 */
    public BattleLimbs(BattleMoves moves, int maxRejects) {
        this.moves = moves;
        this.maxRejects = maxRejects;
    }

    /**
     * 脚：跟着一段寻路走。同一件事、目标没挪开 REPLAN_AFTER 就沿用进行中的这段，挪远了或换了事重算；
     * 走不通记进 lastWalkFailed 并收掉，下一刻重算。返回这一刻有没有真实进展。
     */
    public boolean follow(TickContext context, String key, double anchorX, double anchorZ,
                          Supplier<GoalCompiler.Compiled> goal) {
        lastWalkFailed = false;
        // 换了事、目标挪开三格以上，或是没带锚点起的那段（撤离），都不沿用，重新算路。
        if (walk != null && (!key.equals(walkKey) || walkAnchor == null
                || Math.hypot(anchorX - walkAnchor[0], anchorZ - walkAnchor[1]) > REPLAN_AFTER)) {
            dropWalk();
        }
        if (walk == null) {
            walk = moves.walk(goal.get());
            walkKey = key;
            walkAnchor = new double[] {anchorX, anchorZ};
        }
        ActionStatus status = walk.tick(context);
        if (status instanceof ActionStatus.Failed) {
            lastWalkFailed = true;
            dropWalk();
            return false;
        }
        if (status instanceof ActionStatus.Done) dropWalk();
        return status instanceof ActionStatus.Running running && running.progressed();
    }

    /** 接手一段在外面起的寻路（撤离）：只记名字，没有锚点，目标挪远与否由起路的那边自己管。 */
    public void startWalk(String key, Action walk) {
        this.walk = walk;
        this.walkKey = key;
        this.walkAnchor = null;
    }

    /** 上一段寻路走通了没有：follow 走不通为真，收掉后由引擎记账（记成走不到、换站位）。 */
    public boolean lastWalkFailed() {
        return lastWalkFailed;
    }

    /** 一下出手的结算：哪几样有了结果一看便知；都不占就是这一刀没递进去（交互入口被上一下占着），下一刻再挥。 */
    public record Swing(boolean struck, boolean rejected, boolean givenUp) {
        /** 这一刀有了结果（打中或被拒）：跳劈的节奏、引擎自己的记账都以它为准。 */
        public boolean settled() {
            return struck || rejected;
        }
    }

    /**
     * 手：出手一下。同一只沿用进行中的出手，换人先收手；打中了清掉被拒记账等下一下；被拒记下站位、
     * 接下来绕开它换个方位再出手，连着被拒 maxRejects 次就报告放弃（givenUp）。
     */
    public Swing swing(TickContext context, int targetId, double standX, double standZ,
                       BiFunction<TickContext, Integer, Action> newStrike) {
        if (strike != null && strikeTarget != targetId) dropStrike();
        if (strike == null) {
            strike = newStrike.apply(context, targetId);
            strikeTarget = targetId;
        }
        ActionStatus status;
        try {
            status = strike.tick(context);
        } catch (IllegalStateException busy) {
            // 上一下举盾还在等确认，交互入口这一刻不收这一刀：下一刻再挥。不能因此把整场战斗掀翻——
            // 撤离途中被追上还手时，刚举的盾没结清，挥刀抛错会把整场自卫判成出错收场。
            return new Swing(false, false, false);
        }
        if (status instanceof ActionStatus.Done) {
            dropStrike();
            rejects = 0;
            return new Swing(true, false, false);
        }
        if (status instanceof ActionStatus.Failed) {
            dropStrike();
            markSidestep(standX, standZ);
            if (++rejects >= maxRejects) {
                rejects = 0;
                sidestepTicks = 0;
                return new Swing(false, true, true);
            }
            return new Swing(false, true, false);
        }
        return new Swing(false, false, false);
    }

    /** 被拒或要换角度了：记下此刻站的位，接下来 SIDESTEP_TICKS 刻里绕开它。 */
    public void markSidestep(double x, double z) {
        sidestepFrom = new double[] {x, z};
        sidestepTicks = SIDESTEP_TICKS;
    }

    /** 绕开被拒站位的剩余刻数过掉一刻：引擎每刻在走位前调一次。 */
    public void sidestepTick() {
        if (sidestepTicks > 0) sidestepTicks--;
    }

    /** 还在绕开被拒的那个站位。 */
    public boolean sidestepActive() {
        return sidestepTicks > 0;
    }

    /** 要躲的被拒站位：还在绕开时给出它的 hazard，绕开了或没记过就为空。 */
    public Optional<Footwork.Hazard> sidestepHazard() {
        if (sidestepTicks > 0 && sidestepFrom != null) {
            return Optional.of(new Footwork.Hazard(sidestepFrom[0], sidestepFrom[1], SIDESTEP_CLEARANCE));
        }
        return Optional.empty();
    }

    /** 手上这一下出手；没在出手为 null（暂停、能不能打断按动作逐个问）。 */
    public Action strike() {
        return strike;
    }

    /** 脚上这段寻路；没在走为 null。 */
    public Action walk() {
        return walk;
    }

    /** 换了要打的对象：连着被拒的计数从头数，不把上一只的账算到这一只头上。 */
    public void resetRejects() {
        rejects = 0;
    }

    /** 手上的出手收掉。 */
    public void dropStrike() {
        if (strike != null) strike.close();
        strike = null;
        strikeTarget = -1;
    }

    /** 脚上的寻路收掉。 */
    public void dropWalk() {
        if (walk != null) walk.close();
        walk = null;
        walkKey = null;
        walkAnchor = null;
    }

    /** 手脚都收掉（换打法、这一仗收尾时）。 */
    public void drop() {
        dropStrike();
        dropWalk();
    }

    /** 暂停：出手与寻路停在原处，之后照原样接着打。 */
    public void pause() {
        if (strike != null) strike.pause();
        if (walk != null) walk.pause();
    }
}
