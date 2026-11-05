// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 闲看挑什么：没读什么时隔一会儿看一样，按"说话的人 > 声音来处 > 玩家 > 生物 > 随便一个方向"挑。纯函数。
 * "说话的人"也包括刚冲角色做过肢体动作的人：他蹲几下、挥挥手，和在聊天里叫了一声一样，是在找角色。
 *
 * <p>只看看得见的东西和听见的声音来处（候选由现场给，墙后、地下的不在里面）：盯着一面墙看、
 * 过一会儿那里挖出矿来，观众一看就知道角色在透视。连着两回不看同一处（说话的人除外）；
 * 随便看不转过一百度，大角度转头只留给说话的人和声音。
 */
final class IdleGlances {

    /** 说过话的人多久内还算"刚说过"（刻）与多远内看他（格）。 */
    static final int SPOKE_WITHIN_TICKS = 200;
    static final double SPEAKER_BLOCKS = 24;
    /** 声音多久内还算刚听见（刻）。 */
    static final int HEARD_WITHIN_TICKS = 60;
    /** 多远的玩家、生物值得看一眼（格）。 */
    static final double PLAYER_BLOCKS = 16;
    static final double MOB_BLOCKS = 12;
    /** 随便看时左右偏开站定朝向多少度。 */
    static final float RANDOM_MIN_DEGREES = 25f;
    static final float RANDOM_MAX_DEGREES = 100f;
    /** 随便看时的俯仰：平视往上十五度到往下十度。 */
    static final float RANDOM_PITCH_UP = -15f;
    static final float RANDOM_PITCH_DOWN = 10f;
    /** 低头看脚前两三格地面时的俯仰范围，和左右最多偏多少。 */
    static final float GROUND_PITCH_MIN = 28f;
    static final float GROUND_PITCH_MAX = 38f;
    static final float GROUND_YAW_DEGREES = 20f;
    /** 两回随便看至少差开多少度，免得看着像在原地发呆。 */
    static final float RANDOM_APART_DEGREES = 20f;

    private IdleGlances() {}

    /**
     * 挑中的一回闲看。
     *
     * @param key          认这一回看的键：下一回不看同一处
     * @param randomOffset 随便看时偏开站定朝向的度数；不是随便看为 null
     */
    record Glance(LookPlans.Step step, String key, Float randomOffset) {}

    /**
     * 挑一样看。
     *
     * @param around        身边看得见的玩家、生物和听见的声音
     * @param spokeAgo      说过话的玩家各是多少刻前说的
     * @param heardAgo      各个声音是多少刻前开始听见的（按候选的键）
     * @param lastKey       上一回看的键；没有为 null
     * @param lastOffset    上一回随便看偏开多少度；没有为 null
     * @param reactiveOnly  闲久了：只对说话的人、走近的玩家和声音有反应，不再随便看
     * @return 什么都不值得看（闲久了又没人没声音）时为空
     */
    static Optional<Glance> pick(List<LookingWorld.Candidate> around, Map<UUID, Long> spokeAgo,
            Map<String, Long> heardAgo, float standingYaw, String lastKey, Float lastOffset, boolean reactiveOnly,
            RandomGenerator random) {
        Optional<Glance> speaker = speaker(around, spokeAgo, random);
        if (speaker.isPresent()) return speaker;
        Optional<Glance> sound = sound(around, heardAgo, lastKey, random);
        if (sound.isPresent()) return sound;
        Optional<Glance> player = nearest(around, LookingWorld.CandidateKind.PLAYER, PLAYER_BLOCKS, lastKey,
                reactiveOnly, 30, 60, random);
        if (player.isPresent()) return player;
        if (reactiveOnly) return Optional.empty();
        Optional<Glance> mob = nearest(around, LookingWorld.CandidateKind.MOB, MOB_BLOCKS, lastKey, false,
                20, 40, random);
        if (mob.isPresent()) return mob;
        return Optional.of(anywhere(standingYaw, lastOffset, random));
    }

    // 最近十秒在聊天里说过话（或冲角色做过肢体动作）、看得见、二十四格内的玩家：最近的那个，看着他二到四秒，他动就跟着看。
    private static Optional<Glance> speaker(List<LookingWorld.Candidate> around, Map<UUID, Long> spokeAgo,
            RandomGenerator random) {
        return around.stream()
                .filter(candidate -> candidate.kind() == LookingWorld.CandidateKind.PLAYER
                        && candidate.playerId() != null && candidate.distance() <= SPEAKER_BLOCKS)
                .filter(candidate -> {
                    Long ago = spokeAgo.get(candidate.playerId());
                    return ago != null && ago <= SPOKE_WITHIN_TICKS;
                })
                .min(Comparator.comparingLong(candidate -> spokeAgo.get(candidate.playerId())))
                .map(candidate -> glance(candidate, 40, 80, random));
    }

    // 最近三秒听见的声音：朝声音的大致方位平视看过去一两秒；声音只给方位，看的也只是大致方向。
    private static Optional<Glance> sound(List<LookingWorld.Candidate> around, Map<String, Long> heardAgo,
            String lastKey, RandomGenerator random) {
        return around.stream()
                .filter(candidate -> candidate.kind() == LookingWorld.CandidateKind.SOUND
                        && !candidate.key().equals(lastKey))
                .filter(candidate -> {
                    Long ago = heardAgo.get(candidate.key());
                    return ago != null && ago <= HEARD_WITHIN_TICKS;
                })
                .min(Comparator.comparingLong(candidate -> heardAgo.get(candidate.key())))
                .map(candidate -> glance(candidate, 20, 40, random));
    }

    // 范围内最近的玩家或生物，上一回看过的跳过；生物动着的先看；闲久了只看朝角色走过来的。
    private static Optional<Glance> nearest(List<LookingWorld.Candidate> around, LookingWorld.CandidateKind kind,
            double blocks, String lastKey, boolean onlyApproaching, int minTicks, int maxTicks, RandomGenerator random) {
        List<LookingWorld.Candidate> fit = new ArrayList<>();
        for (LookingWorld.Candidate candidate : around) {
            if (candidate.kind() != kind || candidate.distance() > blocks || candidate.key().equals(lastKey)) continue;
            if (onlyApproaching && !candidate.approaching()) continue;
            fit.add(candidate);
        }
        return fit.stream()
                .min(Comparator.comparing((LookingWorld.Candidate candidate) -> !candidate.moving())
                        .thenComparingDouble(LookingWorld.Candidate::distance))
                .map(candidate -> glance(candidate, minTicks, maxTicks, random));
    }

    private static Glance glance(LookingWorld.Candidate candidate, int minTicks, int maxTicks, RandomGenerator random) {
        return new Glance(new LookPlans.Step(candidate.target(), LookPace.between(minTicks, maxTicks, random),
                new LookPlans.Screen.None()), candidate.key(), null);
    }

    /**
     * 都没有：随便看一个方向，左右偏开站定朝向二十五到一百度，上下在平视往上十五度、往下十度之间；
     * 大约五回里有一回低头看脚前两三格的地面。和上一回随便看至少差开二十度。一两秒。
     */
    static Glance anywhere(float standingYaw, Float lastOffset, RandomGenerator random) {
        int hold = LookPace.between(20, 40, random);
        if (random.nextInt(5) == 0) {
            float offset = (random.nextFloat() * 2f - 1f) * GROUND_YAW_DEGREES;
            float pitch = GROUND_PITCH_MIN + random.nextFloat() * (GROUND_PITCH_MAX - GROUND_PITCH_MIN);
            return new Glance(new LookPlans.Step(new LookTarget.Direction(Aim.wrap(standingYaw + offset), pitch), hold,
                    new LookPlans.Screen.None()), "ground", offset);
        }
        float offset = randomOffset(random);
        for (int tries = 0; tries < 5 && lastOffset != null && Math.abs(offset - lastOffset) < RANDOM_APART_DEGREES; tries++) {
            offset = randomOffset(random);
        }
        float pitch = RANDOM_PITCH_UP + random.nextFloat() * (RANDOM_PITCH_DOWN - RANDOM_PITCH_UP);
        return new Glance(new LookPlans.Step(new LookTarget.Direction(Aim.wrap(standingYaw + offset), pitch), hold,
                new LookPlans.Screen.None()), "anywhere", offset);
    }

    private static float randomOffset(RandomGenerator random) {
        float size = RANDOM_MIN_DEGREES + random.nextFloat() * (RANDOM_MAX_DEGREES - RANDOM_MIN_DEGREES);
        return random.nextBoolean() ? size : -size;
    }
}
