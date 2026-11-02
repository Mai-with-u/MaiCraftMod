// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 刚读了什么 → 这一回怎么看：看哪几处、各停多久、开不开界面、开什么。纯函数，随机数从外面给。
 *
 * <p>只开站在原地够得着的容器，远的只看过去：读后看从不走路，刚读到的方位、距离不因此过时，
 * 也不和 LLM 下一步的安排抢角色。只能转头时（目标在等回答、被暂停）要开界面的几条降成不开。
 */
final class LookPlans {

    /** 读自己时背包开多久：两到三秒。 */
    static final int SELF_MIN_TICKS = 40;
    static final int SELF_MAX_TICKS = 60;
    /** 读周围时每样看多久：零点八到一点五秒；挑两三样。 */
    static final int SPOT_MIN_TICKS = 16;
    static final int SPOT_MAX_TICKS = 30;
    /** 两样之间至少差这么多度才算方向不同。 */
    static final float SPOTS_APART_DEGREES = 30f;
    /** 看一样东西停多久：一点五到二点五秒；告示牌两到三秒。 */
    static final int THING_MIN_TICKS = 30;
    static final int THING_MAX_TICKS = 50;
    static final int SIGN_MIN_TICKS = 40;
    static final int SIGN_MAX_TICKS = 60;
    /** 扫过一片时每处停多久：零点六到一秒；最多扫五处。 */
    static final int SWEEP_MIN_TICKS = 12;
    static final int SWEEP_MAX_TICKS = 20;
    static final int SWEEP_LIMIT = 5;
    /** 配方页翻开多久：三到四秒；没装查看器时对着背包盘算两秒。 */
    static final int RECIPE_MIN_TICKS = 60;
    static final int RECIPE_MAX_TICKS = 80;
    static final int NO_VIEWER_TICKS = 40;
    /** 多远算远（格）：远的只朝那个方位看，视线压在平视附近。 */
    static final double FAR_BLOCKS = 64;

    private LookPlans() {}

    /** 这一回看要开的界面。 */
    sealed interface Screen {
        /** 不开界面，只转头。 */
        record None() implements Screen {}

        /** 打开自己的背包。 */
        record Inventory() implements Screen {}

        /** 打开手边的这只容器看一眼，内容记进世界记忆。 */
        record Container(WorldPosition at, String blockType) implements Screen {}

        /** 打开配方查看器翻到这样东西的配方页（uses 为真翻用途页）。 */
        record Recipe(String itemId, boolean uses) implements Screen {}
    }

    /** 一回看里的一下：看哪里、停多久、开什么界面。 */
    record Step(LookTarget target, int holdTicks, Screen screen) {
        boolean opensScreen() {
            return !(screen instanceof Screen.None);
        }
    }

    /**
     * 一回看：几下依次看过去，转头速度一样，看完转回站定朝向。
     *
     * @param lookAround 真：没什么可挑的，改成原地环顾一圈
     */
    record Plan(List<Step> steps, float degreesPerSecond, boolean lookAround) {
        static final Plan NOTHING = new Plan(List.of(), LookPace.READ_DEGREES_PER_SECOND, false);

        Plan {
            steps = List.copyOf(steps);
        }

        boolean empty() {
            return steps.isEmpty() && !lookAround;
        }
    }

    /** 读了自己：按 E 打开背包看两三秒再关；只能转头时不做。 */
    static Plan self(boolean screensAllowed, RandomGenerator random) {
        if (!screensAllowed) return Plan.NOTHING;
        return read(List.of(new Step(new LookTarget.Standing(),
                LookPace.between(SELF_MIN_TICKS, SELF_MAX_TICKS, random), new Screen.Inventory())));
    }

    /**
     * 读了周围：挑两三样方向不同的东西，从左到右依次看过去；什么都没有就原地环顾一圈。
     *
     * @param standingYaw 站定朝向：左右按它排
     */
    static Plan surroundings(List<LookingWorld.Sighted> spots, float standingYaw, RandomGenerator random) {
        List<LookingWorld.Sighted> shuffled = new ArrayList<>(spots);
        shuffle(shuffled, random);
        int wanted = 2 + random.nextInt(2);
        List<LookingWorld.Sighted> chosen = new ArrayList<>();
        for (LookingWorld.Sighted spot : shuffled) {
            if (chosen.size() >= wanted) break;
            boolean apart = chosen.stream()
                    .allMatch(other -> Math.abs(Aim.wrap(other.yaw() - spot.yaw())) >= SPOTS_APART_DEGREES);
            if (apart) chosen.add(spot);
        }
        if (chosen.isEmpty()) return new Plan(List.of(), LookPace.READ_DEGREES_PER_SECOND, true);
        chosen.sort(leftToRight(standingYaw));
        List<Step> steps = new ArrayList<>();
        for (LookingWorld.Sighted spot : chosen) {
            steps.add(new Step(spot.target(), LookPace.between(SPOT_MIN_TICKS, SPOT_MAX_TICKS, random),
                    new Screen.None()));
        }
        return read(steps);
    }

    /**
     * 读了一个观察编号或地标：看着它；手边的容器五条都满足、又能开界面时打开看一眼，否则只看过去。
     */
    static Plan seen(LookingWorld.Sighted thing, boolean screensAllowed, RandomGenerator random) {
        int hold = thing.kind() == LookingWorld.Kind.SIGN
                ? LookPace.between(SIGN_MIN_TICKS, SIGN_MAX_TICKS, random)
                : LookPace.between(THING_MIN_TICKS, THING_MAX_TICKS, random);
        Screen screen = new Screen.None();
        if (screensAllowed && thing.kind() == LookingWorld.Kind.CONTAINER && mayPeek(thing.container())) {
            screen = new Screen.Container(thing.container().at(), thing.container().blockType());
        }
        return read(List.of(new Step(thing.target(), hold, screen)));
    }

    /**
     * 结果指着的几处：扫的从左到右每处看一眼（最多五处），不扫的盯着第一处看一会儿。
     */
    static Plan places(List<LookingWorld.Sighted> places, boolean sweep, float standingYaw, RandomGenerator random) {
        if (places.isEmpty()) return Plan.NOTHING;
        if (!sweep) {
            return read(List.of(new Step(places.get(0).target(),
                    LookPace.between(THING_MIN_TICKS, THING_MAX_TICKS, random), new Screen.None())));
        }
        List<LookingWorld.Sighted> ordered = new ArrayList<>(places.subList(0, Math.min(SWEEP_LIMIT, places.size())));
        ordered.sort(leftToRight(standingYaw));
        List<Step> steps = new ArrayList<>();
        for (LookingWorld.Sighted place : ordered) {
            steps.add(new Step(place.target(), LookPace.between(SWEEP_MIN_TICKS, SWEEP_MAX_TICKS, random),
                    new Screen.None()));
        }
        return read(steps);
    }

    /**
     * 查了配方：装了配方查看器就翻到这一样的配方页（或用途页）三四秒；没装就打开背包看两秒，
     * 像对着背包盘算材料，不碰原版配方书。只能转头时不做。
     */
    static Plan recipe(String itemId, boolean uses, boolean viewerOpensPages, boolean screensAllowed,
            RandomGenerator random) {
        if (!screensAllowed) return Plan.NOTHING;
        if (!viewerOpensPages) {
            return read(List.of(new Step(new LookTarget.Standing(), NO_VIEWER_TICKS, new Screen.Inventory())));
        }
        return read(List.of(new Step(new LookTarget.Standing(),
                LookPace.between(RECIPE_MIN_TICKS, RECIPE_MAX_TICKS, random), new Screen.Recipe(itemId, uses))));
    }

    /**
     * 手边的容器能不能打开看一眼：站在原地够得着、看得见；存储许可允许；不是信号源（陷阱箱）；
     * 附近看不见敌对生物（猪灵看见开箱会翻脸）。不满足就只看过去。
     */
    static boolean mayPeek(LookingWorld.ContainerFacts facts) {
        return facts != null && facts.reachableAndVisible() && facts.mayUse() && !facts.signalSource()
                && !facts.hostileNear();
    }

    /** 远（六十四格外）的东西只朝方位看：目标点换成平视附近。 */
    static boolean far(double distance) {
        return distance > FAR_BLOCKS;
    }

    private static Plan read(List<Step> steps) {
        return new Plan(steps, LookPace.READ_DEGREES_PER_SECOND, false);
    }

    // 从左到右：相对站定朝向偏左（偏航更小）的先看。偏航顺时针增大，往右转偏航变大。
    private static Comparator<LookingWorld.Sighted> leftToRight(float standingYaw) {
        return Comparator.comparingDouble(spot -> Aim.wrap(spot.yaw() - standingYaw));
    }

    private static <T> void shuffle(List<T> list, RandomGenerator random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T swap = list.get(i);
            list.set(i, list.get(j));
            list.set(j, swap);
        }
    }
}
