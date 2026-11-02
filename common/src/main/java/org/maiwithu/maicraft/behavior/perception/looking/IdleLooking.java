// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import net.minecraft.util.Mth;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.recipe.OpensRecipePage;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 闲时看：角色闲着时像人一样看看。LLM 刚读了什么就顺着看过去，能开界面的打开看几秒（读后看）；
 * 没读什么时隔一会儿看看身边的人、生物和声音来处（闲看）。只转头和开界面，不走路；有事要做当刻收手。
 *
 * <p>每刻在控制循环推进之前调一次 {@link #tick}，由启动告诉它此刻角色能不能用：栈空着能全做；
 * 主目标在等回答、被暂停时只能转头（不开界面、不碰手上的东西）；有任务要用角色、人按了 F8 就收手。
 * 它自己再看角色（背包到了、脚踏实地、没骑乘没睡没飞）和屏幕（没有别人开的界面）。
 * 收手时自己开着的界面关上，镜头停在哪就是哪，不往回甩；站定朝向留到别的事把镜头转走为止。
 * 它不进控制循环的栈、不发任务事件，observe(self) 的 doing 照旧。
 */
public final class IdleLooking implements LooksAfterReading {

    /** 此刻角色能不能拿来看东西。 */
    public enum Availability {
        /** 闲着：没有主任务也没有临时任务，转头、开界面都行。 */
        FREE,
        /** 主目标在等回答或被暂停：只转头，不开界面、不碰手上的东西。 */
        HEAD_ONLY,
        /** 有任务要用角色，或者人按了 F8：收手。 */
        TAKEN
    }

    /** 刚闲下来时镜头偏离平视超过这么多度（刚挖完脚下、刚放完头顶），第一下先抬回平视。 */
    static final float LEVEL_FIRST_DEGREES = 35f;
    /** 抬回平视那一下停多久（刻）。 */
    static final int LEVEL_HOLD_TICKS = 10;
    /** 闲着时俯仰平常停在哪：平视附近，抬回来后略低一点。 */
    static final float REST_PITCH_MIN = -10f;
    static final float REST_PITCH_MAX = 15f;
    static final float LEVELED_PITCH = 5f;

    private final Function<TickContext, TurnsHead> heads;
    private final LookingWorld world;
    private final LookScreens screens;
    private final RandomGenerator random;
    private final boolean enabled;

    private WhatWasRead incoming;
    private WhatWasRead pending;
    private long pendingArrived;
    private long pendingReady;
    private Action current;
    private long idleSince = -1;
    private long nextGlanceAt;
    private boolean levelFirst;
    private float restPitch;
    private boolean headOnly;
    private String lastGlanceKey;
    private Float lastRandomOffset;
    private long lastTick;
    private TurnsHead lastHead;
    private final Map<String, Long> lastRead = new HashMap<>();
    private final Map<UUID, Long> spokeAt = new HashMap<>();
    private final Map<String, Long> heardSince = new HashMap<>();

    /**
     * @param enabled 所有者实例配置里的「闲时看」；关着时什么都不做，读信息不转头、不开界面
     */
    IdleLooking(Function<TickContext, TurnsHead> heads, LookingWorld world, LookScreens screens,
            RandomGenerator random, boolean enabled) {
        this.heads = Objects.requireNonNull(heads, "heads");
        this.world = Objects.requireNonNull(world, "world");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.random = Objects.requireNonNull(random, "random");
        this.enabled = enabled;
    }

    /**
     * 接在真实客户端上的闲时看：转头走角色的视角输入，可看的东西从这个世界的感知场景来，
     * 开箱经原生交互与菜单入口、内容记进这个世界的记忆，配方页交给装了的查看器翻。
     *
     * @param pages   装了的查看器里能翻开配方页的那几个；每次问都读此刻的登记表
     * @param enabled 所有者实例配置里的「闲时看」
     */
    public static IdleLooking live(Supplier<PlayerContext> contexts, Scene scene, WorldMemory memory,
            FacilityKinds kinds, Protection protection, MenuLayouts layouts, Interactions interactions,
            Supplier<List<OpensRecipePage>> pages, boolean enabled) {
        return new IdleLooking(TurnsHead::of, new ClientLookingWorld(scene, memory, kinds, protection),
                new ClientLookScreens(contexts, layouts, interactions, memory, pages), RandomGenerator.getDefault(),
                enabled);
    }

    // 读信息的路径读完交过来：只留最新一条，下一刻开始算它到了多久。
    @Override public void justRead(WhatWasRead what) {
        if (enabled && what != null) incoming = what;
    }

    /** 有人在聊天里说了话：十秒内他看得见、在二十四格内，闲看先看他。 */
    public void someoneSpoke(UUID who) {
        if (enabled && who != null) spokeAt.put(who, lastTick);
    }

    /** 推进一刻：在控制循环推进之前调，新目标或临时任务来的那一刻先收手，任务这一刻照常开始。 */
    public void tick(TickContext context, Availability availability) {
        if (!enabled) return;
        long now = context.gameTick();
        lastTick = now;
        acceptIncoming(now);
        TurnsHead head = heads.apply(context);
        if (availability == Availability.TAKEN || head == null || !world.playerCanLook(context) || screens.othersOpen()) {
            stop();
            return;
        }
        lastHead = head;
        boolean onlyHead = availability == Availability.HEAD_ONLY;
        if (onlyHead && current instanceof LookOnce once && once.screenOpen()) {
            // 主目标挂起来了（等回答、暂停）而背包还开着：只能转头了，界面先关上。
            current.close();
            current = null;
        }
        headOnly = onlyHead;
        if (idleSince < 0) begin(head, now);
        // 转头看东西期间角色朝哪边按站定朝向算：每刻都按着，环顾做完放开了也接着按回来。
        head.hold();
        if (pending != null && LookPace.expired(pendingArrived, now)) pending = null;
        boolean readReady = pending != null && now >= pendingReady;
        if (readReady && (current == null || heldEnough(current))) {
            startRead(context, head, now);
        } else if (current == null && now >= nextGlanceAt) {
            startGlance(context, head, now);
        }
        if (current != null) advance(context, now);
    }

    /** 此刻在看东西（测试与调试面板读）。 */
    boolean looking() {
        return current != null;
    }

    private void acceptIncoming(long now) {
        if (incoming == null) return;
        // 新请求换掉排着的旧请求：只留最新一条，读完隔零点二到零点五秒再动。
        pending = incoming;
        incoming = null;
        pendingArrived = now;
        pendingReady = now + LookPace.readDelay(random);
    }

    // 刚闲下来：先站定一秒；镜头低头或仰头偏离平视太多的，第一下先抬回平视。
    private void begin(TurnsHead head, long now) {
        idleSince = now;
        nextGlanceAt = now + LookPace.SETTLE_TICKS;
        float pitch = head.pitch();
        levelFirst = Math.abs(pitch) > LEVEL_FIRST_DEGREES;
        restPitch = levelFirst ? LEVELED_PITCH : Mth.clamp(pitch, REST_PITCH_MIN, REST_PITCH_MAX);
    }

    // 读后看：同样的读还在冷却里就不重看；能看的换掉正在看的那一下（连着查配方时配方页不关，直接翻过去）。
    private void startRead(TickContext context, TurnsHead head, long now) {
        WhatWasRead what = pending;
        pending = null;
        LookPace.Cooldown cooldown = LookPace.cooldownOf(what);
        if (LookPace.coolingDown(lastRead.get(cooldown.key()), now, cooldown.ticks())) return;
        LookPlans.Plan plan = planFor(context, head, what);
        if (plan.empty()) return;
        lastRead.put(cooldown.key(), now);
        if (current != null) {
            if (current instanceof LookOnce once && once.showingRecipePage() && opensRecipe(plan)) once.handOverScreen();
            current.close();
        }
        current = plan.lookAround() ? new LookAround(heads, random, false)
                : new LookOnce(plan, heads, world, screens, random, restPitch);
    }

    private LookPlans.Plan planFor(TickContext context, TurnsHead head, WhatWasRead what) {
        boolean screensAllowed = !headOnly;
        return switch (what) {
            case WhatWasRead.Self self -> LookPlans.self(screensAllowed, random);
            case WhatWasRead.Surroundings around ->
                    LookPlans.surroundings(world.sceneSpots(context), head.standingYaw(), random);
            case WhatWasRead.Seen seen -> world.resolve(context, seen.id())
                    .map(thing -> LookPlans.seen(thing, screensAllowed, random)).orElse(LookPlans.Plan.NOTHING);
            case WhatWasRead.Places places -> LookPlans.places(world.places(context, places.places()), places.sweep(),
                    head.standingYaw(), random);
            case WhatWasRead.RecipePage page -> LookPlans.recipe(page.itemId(), page.uses(),
                    screens.opensRecipePages(), screensAllowed, random);
        };
    }

    // 闲看：到点了挑一样看；闲久了只对人和声音有反应，什么都不值得看就再等一个间隔。
    private void startGlance(TickContext context, TurnsHead head, long now) {
        if (levelFirst) {
            levelFirst = false;
            current = new LookOnce(new LookPlans.Plan(List.of(new LookPlans.Step(new LookTarget.Standing(),
                    LEVEL_HOLD_TICKS, new LookPlans.Screen.None())), LookPace.GLANCE_DEGREES_PER_SECOND, false),
                    heads, world, screens, random, restPitch);
            return;
        }
        List<LookingWorld.Candidate> around = world.around(context);
        Optional<IdleGlances.Glance> glance = IdleGlances.pick(around, spokeAgo(now), heardAgo(around, now),
                head.standingYaw(), lastGlanceKey, lastRandomOffset, LookPace.reactiveOnly(now - idleSince), random);
        if (glance.isEmpty()) {
            nextGlanceAt = now + LookPace.glanceGap(now - idleSince, random);
            return;
        }
        lastGlanceKey = glance.get().key();
        if (glance.get().randomOffset() != null) lastRandomOffset = glance.get().randomOffset();
        current = new LookOnce(new LookPlans.Plan(List.of(glance.get().step()), LookPace.GLANCE_DEGREES_PER_SECOND,
                false), heads, world, screens, random, restPitch);
    }

    // 看完一回：从头计下一回闲看的间隔。
    private void advance(TickContext context, long now) {
        if (current.tick(context) instanceof ActionStatus.Running) return;
        current.close();
        current = null;
        nextGlanceAt = now + LookPace.glanceGap(now - idleSince, random);
    }

    // 收手：开着的界面关上，松开转头（镜头停在哪就是哪），站定朝向留到别的事把镜头转走为止。
    private void stop() {
        if (current != null) {
            current.close();
            current = null;
        }
        if (idleSince >= 0 && lastHead != null) lastHead.letGo();
        idleSince = -1;
        levelFirst = false;
    }

    private static boolean heldEnough(Action current) {
        return !(current instanceof LookOnce once) || once.heldEnough();
    }

    private static boolean opensRecipe(LookPlans.Plan plan) {
        return !plan.steps().isEmpty() && plan.steps().get(0).screen() instanceof LookPlans.Screen.Recipe;
    }

    // 说过话的人各是多少刻前说的；十秒以前的忘掉。
    private Map<UUID, Long> spokeAgo(long now) {
        spokeAt.values().removeIf(at -> now - at > IdleGlances.SPOKE_WITHIN_TICKS);
        Map<UUID, Long> ago = new HashMap<>();
        spokeAt.forEach((who, at) -> ago.put(who, now - at));
        return ago;
    }

    // 声音从第一次在挑闲看时听见起算多久：一直响着的（流水、岩浆）过三秒就不再算刚听见，不会每回都被它拉过去。
    private Map<String, Long> heardAgo(List<LookingWorld.Candidate> around, long now) {
        Set<String> present = new HashSet<>();
        for (LookingWorld.Candidate candidate : around) {
            if (candidate.kind() == LookingWorld.CandidateKind.SOUND) present.add(candidate.key());
        }
        heardSince.keySet().retainAll(present);
        for (String key : present) heardSince.putIfAbsent(key, now);
        Map<String, Long> ago = new HashMap<>();
        heardSince.forEach((key, since) -> ago.put(key, now - since));
        return ago;
    }
}
