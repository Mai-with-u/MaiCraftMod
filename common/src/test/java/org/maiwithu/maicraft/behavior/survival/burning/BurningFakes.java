// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.burning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.survival.burning.FireResistancePotions.CarriedPotion;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 着火需求与自救任务的替身：一份份排好的处境、记下按键与动作的动手部件、记下事件的事件出口。 */
final class BurningFakes {

    static final BlockPos FEET = new BlockPos(0, 64, 0);
    static final CarriedPotion POTION = new CarriedPotion(3, "minecraft:potion", true,
            List.of(FireResistancePotions.FIRE_RESISTANCE));

    private BurningFakes() {}

    /** 拼一份处境：默认站在平地上，什么事都没有。 */
    static final class Situation {
        private BlockPos feet = FEET;
        private boolean inLava;
        private int resistance;
        private boolean inFire;
        private BlockPos fireStep;
        private boolean onFire;
        private BurnEstimate burn = BurnEstimate.NONE;
        private BurningSteps.Water water;
        private boolean boils;
        private CarriedPotion potion;

        static Situation calm() {
            return new Situation();
        }

        Situation inLava() {
            inLava = true;
            onFire = true;
            burn = new BurnEstimate(300, 15, true);
            return this;
        }

        Situation inFire(BlockPos step) {
            inFire = true;
            onFire = true;
            fireStep = step;
            return this;
        }

        /** 在陆地上着着火，估着烧得死或烧不死。 */
        Situation burning(boolean lethal) {
            onFire = true;
            burn = lethal ? new BurnEstimate(300, 15, true) : new BurnEstimate(100, 5, false);
            return this;
        }

        Situation water(int x, int steps) {
            water = new BurningSteps.Water(new BlockPos(x, 64, 0), steps);
            return this;
        }

        Situation resistant(int ticks) {
            resistance = ticks;
            return this;
        }

        Situation potion() {
            potion = POTION;
            return this;
        }

        Situation nether() {
            boils = true;
            return this;
        }

        BurningNeed.Reading build() {
            return new BurningNeed.Reading(feet, inLava, resistance, inFire, fireStep, onFire, burn, water, boils, potion);
        }
    }

    /** 按顺序一刻给一份处境，给完了一直给最后那份。 */
    static final class ScriptedView implements BurningNeed.View {
        private final List<BurningNeed.Reading> readings = new ArrayList<>();
        private int next;

        ScriptedView(Situation... situations) {
            for (Situation situation : situations) readings.add(situation.build());
        }

        // 同一刻读几次都给同一份：任务每刻只读一次，换刻由测试推进。
        void advance() {
            if (next < readings.size() - 1) next++;
        }

        @Override
        public BurningNeed.Reading read(TickContext context) {
            return readings.get(next);
        }
    }

    /** 记下按键、喝药、垫块的动手部件：每朝同一格按一次，离那格近 0.5 格。 */
    static final class FakeMoves implements BurningTask.Moves {
        List<BlockPos> exits = List.of();
        BlockPos shore;
        BurningTask.Foothold foothold = new BurningTask.Foothold.Unavailable("身上没有能拿来垫的方块");
        Action drinkAction = new FinishesAfter(2);
        final List<BlockPos> steered = new ArrayList<>();
        final List<Boolean> jumps = new ArrayList<>();
        final List<CarriedPotion> drunk = new ArrayList<>();
        int footholdAsked;
        /** 为真时怎么按都挪不近：测换岸。 */
        boolean blocked;
        /**
         * 贴着岸往上蹿：每按一刻脚升高这么多格，水平始终离出口中心 0.8 格不动，距离按真的走一刻那样报（上下也算）；
         * 为 0 时按默认的每刻近 0.5 格。
         */
        double climbPerTick;
        private int climbed;
        private final Map<BlockPos, Double> distance = new HashMap<>();

        @Override public List<BlockPos> lavaExits(TickContext context) {
            return exits;
        }

        @Override public Optional<BlockPos> farShore(TickContext context) {
            return Optional.ofNullable(shore);
        }

        @Override public double steer(TickContext context, BlockPos cell, boolean jump) {
            steered.add(cell);
            jumps.add(jump);
            if (climbPerTick > 0) {
                double feetY = Math.min(cell.getY(), cell.getY() - 1.0 + climbPerTick * climbed++);
                return BurningSteps.toStand(cell.getX() + 0.5 - 0.8, feetY, cell.getZ() + 0.5, cell);
            }
            double now = distance.getOrDefault(cell, 5.0);
            if (!blocked) distance.put(cell, Math.max(0, now - 0.5));
            return distance.getOrDefault(cell, now);
        }

        @Override public Optional<Action> drink(TickContext context, CarriedPotion potion) {
            drunk.add(potion);
            return Optional.ofNullable(drinkAction);
        }

        @Override public BurningTask.Foothold foothold(TickContext context) {
            footholdAsked++;
            return foothold;
        }
    }

    /** 推进几刻后做完；fail 为真时以失败收场。 */
    static final class FinishesAfter implements Action {
        private int left;
        private final boolean fail;

        FinishesAfter(int ticks) {
            this(ticks, false);
        }

        FinishesAfter(int ticks, boolean fail) {
            this.left = ticks;
            this.fail = fail;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (--left > 0) return ActionStatus.running();
            return fail ? ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "游戏没让放")) : ActionStatus.done();
        }

        @Override public String describe() {
            return "替身动作";
        }
    }

    /** 记下发出的事件。 */
    static final class Events implements TaskEventSink {
        final List<String> unhandled = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            if (kind == TaskEvent.Kind.NEED_UNHANDLED) unhandled.add(message);
        }
    }
}
