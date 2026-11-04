// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.survival.burning.FireResistancePotions.CarriedPotion;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 着火自救临时任务：出岩浆 → 出火 → 灭火 → 收工，每一步现场没这事就跳过，每刻按新处境重看一次。
 *
 * <p>泡在岩浆里：朝 3 格内最近的岸转身，按住前进和跳（岩浆里往上游，贴着岸借跳上去）；20 刻没挪近就换下一个岸。
 * 3 格内没有岸，依次试：喝防火药水（喝下去岩浆就伤不着，再慢慢找岸）、在身边垫一块方块踩上去、朝 16 格内最近的岸游，
 * 游的时候发一条事件说清为什么没喝没垫、往哪游。站在火里：往旁边能落脚的一格迈出来。身上着火：估着够烧死，
 * 2 格内有水先跳水，水更远或没有水就先喝药；估着烧不死，4 格内有水、不在下界就走进水里；都没有就收工，火自己会灭。
 * 估着够烧死又没药、没水，发处理不了的事件后收场。
 */
public final class BurningTask extends PhasedTask<BurningTask.Phase> {

    /** 每一步：出岩浆、喝药、垫块、往远处的岸游、出火、走进水里。 */
    public enum Phase { LEAVE_LAVA, DRINK, PLACE_FOOTHOLD, SWIM_TO_SHORE, LEAVE_FIRE, INTO_WATER }

    /** 朝一个岸这么多刻没挪近就换下一个。 */
    static final int EXIT_STALL_TICKS = 20;
    /** 离目标近了这么多（格）才算挪近。 */
    private static final double MIN_GAIN = 0.05;

    /** 动手的部件：找岸、转身按键、喝药、垫块。 */
    public interface Moves {
        /** 3 格内的岩浆出口，最近的在前。 */
        List<BlockPos> lavaExits(TickContext context);

        /** 16 格内最近的岸；没有为空。 */
        Optional<BlockPos> farShore(TickContext context);

        /**
         * 朝这一格中心走（游）一刻：转身按前进，jump 为真时按住跳；返回这一刻离那一格脚位的距离，上下也算
         * （贴着岸往上蹿时水平几乎不变、脚在升高，也要算靠近）。
         */
        double steer(TickContext context, BlockPos cell, boolean jump);

        /** 喝下这一瓶：换到主手再按住喝完；换不了手时为空。 */
        Optional<Action> drink(TickContext context, CarriedPotion potion);

        /** 在身边垫一块落脚；垫不了时说清为什么。 */
        Foothold foothold(TickContext context);
    }

    /** 垫一块落脚：给得出放方块的动作，或者说清为什么垫不了。 */
    public sealed interface Foothold {
        /** 垫得了：拿什么垫、垫在哪、放方块的动作。 */
        record Placing(Action action, String filler, BlockPos cell) implements Foothold {}

        /** 垫不了，为什么。 */
        record Unavailable(String why) implements Foothold {}
    }

    private final BurningNeed.View view;
    private final Moves moves;
    private final TaskEventSink events;
    /** 走不过去的岸：20 刻没挪近就记下，换下一个。 */
    private final Set<BlockPos> exitsGivenUp = new HashSet<>();
    /** 这次自救做成了哪些事，收工时照这个说。 */
    private final List<String> achieved = new ArrayList<>();
    /** 试过没做成的事，处理不了时照这个说。 */
    private final List<String> failed = new ArrayList<>();
    private BlockPos target;
    private double best = Double.MAX_VALUE;
    private int stall;
    private boolean drinkTried;
    private boolean footholdTried;
    private boolean shoreTold;
    private BlockPos shore;
    private boolean shoreSearched;
    private boolean wasInLava;
    private boolean wasInFire;
    private Action pending;
    private CarriedPotion drinking;
    private Foothold.Placing placing;

    public BurningTask(BurningNeed.View view, Moves moves, TaskEventSink events) {
        super("着火自救", Phase.LEAVE_LAVA, new ProgressTracker(100, 20L * 60));
        this.view = Objects.requireNonNull(view, "view");
        this.moves = Objects.requireNonNull(moves, "moves");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    protected Action enter(Phase phase) {
        return phase == Phase.DRINK || phase == Phase.PLACE_FOOTHOLD ? pending : null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        BurningNeed.Reading reading = view.read(context);
        if (reading == null) {
            // 这一刻读不到处境（角色上下文短暂缺失）：先不动，下一刻再看。
            return Next.stay();
        }
        if (phase == Phase.DRINK) return afterDrinking(context, reading);
        if (phase == Phase.PLACE_FOOTHOLD) return afterPlacing(context, reading);
        return decide(context, reading, phase);
    }

    // 出岩浆 → 出火 → 灭火 → 收工：按这一刻的现场挑该做的那一步，前面的事没了就往后走。
    private Next<Phase> decide(TickContext context, BurningNeed.Reading reading, Phase phase) {
        if (reading.inLava()) {
            wasInLava = true;
            return leaveLava(context, reading, phase);
        }
        if (wasInLava) {
            wasInLava = false;
            achieved.add("出了岩浆");
        }
        if (reading.inFire() && reading.fireStep() != null) {
            wasInFire = true;
            return walkTo(context, reading, reading.fireStep(), Phase.LEAVE_FIRE, phase, "站在火里，往旁边迈一步");
        }
        if (wasInFire) {
            wasInFire = false;
            achieved.add("迈出了火");
        }
        if (reading.onFire() && !reading.fireResistant()) return douse(context, reading, phase);
        if (phase == Phase.INTO_WATER && !reading.onFire()) achieved.add("走进水里灭了火");
        return Next.done(TaskResult.done(summary(reading)));
    }

    // 泡在岩浆里：先朝 3 格内最近的岸出去；没有岸再喝药、垫块，都不行朝远处的岸游。
    private Next<Phase> leaveLava(TickContext context, BurningNeed.Reading reading, Phase phase) {
        List<BlockPos> exits = new ArrayList<>(moves.lavaExits(context));
        exits.removeAll(exitsGivenUp);
        if (!exits.isEmpty()) {
            BlockPos exit = exits.get(0);
            double distance = moves.steer(context, exit, true);
            if (!exit.equals(target)) aimAt(exit);
            if (closer(distance, "朝岸边挪近了")) {
                return to(Phase.LEAVE_LAVA, phase, "朝 " + exit.toShortString() + " 的岸出去");
            }
            if (++stall >= EXIT_STALL_TICKS) {
                // 这个岸半天挪不过去（隔着东西、借不上力）：换下一个，下一刻按新的岸走。
                exitsGivenUp.add(exit);
                target = null;
            }
            return to(Phase.LEAVE_LAVA, phase, "朝 " + exit.toShortString() + " 的岸出去");
        }
        if (!reading.fireResistant() && reading.potion() != null && !drinkTried) {
            Optional<Next<Phase>> drink = startDrinking(context, reading.potion(), "3 格内没有能上去的岸，先喝防火药水");
            if (drink.isPresent()) return drink.get();
        }
        if (!footholdTried) {
            footholdTried = true;
            switch (moves.foothold(context)) {
                case Foothold.Placing place -> {
                    placing = place;
                    pending = place.action();
                    return Next.go(Phase.PLACE_FOOTHOLD, "3 格内没有能上去的岸，垫一块 " + place.filler() + " 落脚");
                }
                case Foothold.Unavailable none -> failed.add("垫不了方块（" + none.why() + "）");
            }
        }
        return swimToShore(context, reading, phase);
    }

    // 近处没岸、药和垫块都不成：朝 16 格内最近的岸游，第一次游时发事件说清为什么、往哪游。
    private Next<Phase> swimToShore(TickContext context, BurningNeed.Reading reading, Phase phase) {
        if (!shoreSearched) {
            shoreSearched = true;
            shore = moves.farShore(context).orElse(null);
            target = null;
        }
        if (!shoreTold && !reading.fireResistant()) {
            shoreTold = true;
            events.publish(TaskEvent.Kind.NEED_UNHANDLED, "泡在岩浆里（" + reading.feet().toShortString()
                    + "），3 格内没有能上去的岸，" + String.join("，", lacks(reading)) + "，"
                    + (shore == null ? "16 格内也找不到岸" : "正往" + bearing(reading.feet(), shore) + "约 "
                    + Math.round(Math.sqrt(reading.feet().distSqr(shore))) + " 格外的岸游"));
        }
        if (shore == null) {
            // 找不到岸：按住跳浮在岩浆面上，等药效或有人来救；卡住由进度跟踪收场，下一刻按新处境重来。
            moves.steer(context, reading.feet(), true);
            return to(Phase.SWIM_TO_SHORE, phase, "16 格内找不到岸，浮在岩浆面上");
        }
        double distance = moves.steer(context, shore, true);
        if (!shore.equals(target)) aimAt(shore);
        closer(distance, "朝远处的岸游近了");
        return to(Phase.SWIM_TO_SHORE, phase, "朝 " + shore.toShortString() + " 的岸游");
    }

    // 身上着火：估着够烧死，2 格内有水先跳水、水更远就先喝药；估着烧不死，4 格内有水、不在下界就走进水里。
    private Next<Phase> douse(TickContext context, BurningNeed.Reading reading, Phase phase) {
        BurningSteps.Water water = reading.water();
        if (reading.burn().lethal()) {
            if (water != null && water.steps() <= BurningNeed.NEAR_WATER_STEPS) {
                return walkTo(context, reading, water.cell(), Phase.INTO_WATER, phase, "估着这把火够烧死，跳进身边的水里");
            }
            if (reading.potion() != null && !drinkTried) {
                Optional<Next<Phase>> drink = startDrinking(context, reading.potion(), "估着这把火够烧死，先喝防火药水");
                if (drink.isPresent()) return drink.get();
            }
            if (water != null) {
                return walkTo(context, reading, water.cell(), Phase.INTO_WATER, phase, "估着这把火够烧死，走进 "
                        + water.steps() + " 步外的水里");
            }
            events.publish(TaskEvent.Kind.NEED_UNHANDLED, "身上着火（" + reading.feet().toShortString() + "），估着还要烧约 "
                    + Math.round(reading.burn().remainingTicks() / 20.0) + " 秒、掉 " + reading.burn().damage()
                    + " 点血，够把角色烧死：" + (drinkTried ? "防火药水没喝成" : "身上没有防火药水")
                    + "，" + BurningNeed.WATER_STEPS + " 步内也没有走得到的水" + triedSoFar());
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "身上着火估着够烧死，没有药也没有水，只能熬着"));
        }
        if (water != null && !reading.waterBoils()) {
            return walkTo(context, reading, water.cell(), Phase.INTO_WATER, phase, "身上着火，走进 " + water.steps() + " 步外的水里");
        }
        return Next.done(TaskResult.done(summary(reading)));
    }

    // 朝落脚的那一格（火外的邻格、水）走一刻；挪近了记进展，挪不近由进度跟踪收场。
    private Next<Phase> walkTo(TickContext context, BurningNeed.Reading reading, BlockPos cell, Phase want, Phase phase,
                               String why) {
        double distance = moves.steer(context, cell, cell.getY() > reading.feet().getY());
        if (!cell.equals(target)) aimAt(cell);
        closer(distance, "朝 " + cell.toShortString() + " 挪近了");
        return to(want, phase, why);
    }

    // 开始喝药：换手不成就记下，接着往后试别的办法。
    private Optional<Next<Phase>> startDrinking(TickContext context, CarriedPotion potion, String why) {
        drinkTried = true;
        Optional<Action> drink = moves.drink(context, potion);
        if (drink.isEmpty()) {
            failed.add("防火药水换不到手上");
            return Optional.empty();
        }
        drinking = potion;
        pending = drink.get();
        return Optional.of(Next.go(Phase.DRINK, why));
    }

    // 喝完了按新处境往下走；没喝成记下来，接着试别的办法。
    private Next<Phase> afterDrinking(TickContext context, BurningNeed.Reading reading) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordChange(new Change(Change.Kind.ITEM_CONSUMED, drinking.itemId(), 1, "着火时喝下的防火药水"));
                achieved.add("喝了防火药水");
                yield decide(context, reading, Phase.DRINK);
            }
            case ActionStatus.Failed failure -> {
                failed.add("防火药水没喝成（" + failure.problem().message() + "）");
                recordAttempt("喝防火药水", failure.problem().message());
                yield decide(context, reading, Phase.DRINK);
            }
        };
    }

    // 垫好了就按新的岸往上走；垫不上如实记下，接着往远处的岸游。
    private Next<Phase> afterPlacing(TickContext context, BurningNeed.Reading reading) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordChange(new Change(Change.Kind.BLOCK_PLACED, placing.filler(), 1,
                        "泡在岩浆里垫来落脚，垫在 " + placing.cell().toShortString()));
                achieved.add("在岩浆里垫了一块 " + placing.filler() + " 落脚");
                exitsGivenUp.clear();
                yield decide(context, reading, Phase.PLACE_FOOTHOLD);
            }
            case ActionStatus.Failed failure -> {
                failed.add("方块没垫上（" + failure.problem().message() + "）");
                recordAttempt("在岩浆里垫一块落脚", failure.problem().message());
                yield decide(context, reading, Phase.PLACE_FOOTHOLD);
            }
        };
    }

    // 换了要去的那一格：挪近的记录从头算。
    private void aimAt(BlockPos cell) {
        target = cell;
        best = Double.MAX_VALUE;
        stall = 0;
    }

    // 这一刻比之前离目标更近了：记一次真实进展。
    private boolean closer(double distance, String what) {
        if (distance < best - MIN_GAIN) {
            best = distance;
            stall = 0;
            recordProgress(what);
            return true;
        }
        return false;
    }

    private static Next<Phase> to(Phase want, Phase phase, String why) {
        return want == phase ? Next.stay() : Next.go(want, why);
    }

    // 泡在岩浆里没喝成、没垫成的原因，事件里照实说。
    private List<String> lacks(BurningNeed.Reading reading) {
        List<String> lacks = new ArrayList<>();
        if (!drinkTried) lacks.add(reading.potion() == null ? "身上没有防火药水" : "防火药水没来得及喝");
        lacks.addAll(failed);
        return lacks;
    }

    private String triedSoFar() {
        return failed.isEmpty() ? "" : "；试过：" + String.join("，", failed);
    }

    // 收工时说做成了什么；身上还着火、但烧不死或已有防火效果的，说一句火自己会灭。
    private String summary(BurningNeed.Reading reading) {
        String done = achieved.isEmpty() ? "现场没有要处理的火了" : String.join("，", achieved);
        if (reading.onFire()) {
            done += reading.fireResistant() ? "；防火效果还在，火伤不着" : "；身上的火估着烧不死，自己会灭";
        }
        return "着火自救：" + done;
    }

    // 从 from 看 to 在哪个方向：东西、南北差得多时只说一个，差不多时合起来说"东南"这类。
    static String bearing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        String eastWest = dx > 0 ? "东" : dx < 0 ? "西" : "";
        String northSouth = dz > 0 ? "南" : dz < 0 ? "北" : "";
        if (Math.abs(dx) > 2 * Math.abs(dz)) return eastWest;
        if (Math.abs(dz) > 2 * Math.abs(dx)) return northSouth;
        return eastWest + northSouth;
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case LEAVE_LAVA -> "从岩浆里出去";
            case DRINK -> "喝防火药水";
            case PLACE_FOOTHOLD -> "在岩浆里垫一块落脚";
            case SWIM_TO_SHORE -> "朝远处的岸游";
            case LEAVE_FIRE -> "从火里迈出来";
            case INTO_WATER -> "走进水里灭火";
        };
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 正在往岸上蹿、往水里跳的半路停下只会更糟；只有更急的（同时被埋、要淹死）打断得了。
        return Interruptibility.UNSAFE_TO_STOP;
    }
}
