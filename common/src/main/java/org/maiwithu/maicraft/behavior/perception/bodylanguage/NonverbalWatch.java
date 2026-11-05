// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.combat.SparPartner;
import org.maiwithu.maicraft.behavior.combat.ThreatAssessment;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.game.player.DamageObserver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.DiggingSeen;
import org.maiwithu.maicraft.game.world.EntityHurtSeen;
import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.game.world.LooksAt;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.game.world.SwingsSeen;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 看别的玩家冲角色做的肢体动作，按人归成一段发事件 player_body_language。
 *
 * <p>每刻给 16 格内看得见的玩家各采一份样子（{@link PlayerPose}），蹲起、点头、摇头、跳、拿出东西从采样里认；
 * 挥手从挥手包认，过 2 刻再判（这 2 刻前后他在挖方块、打中了别的东西、准星那里冒出或少了一格方块，就不算挥手）；
 * 扔东西从新掉落物认，看它落在哪。被别的玩家打中（伤害包）、或他对着够得着的角色挥手却没打出伤害，按
 * {@link PlayerHitTally} 分拍一下还是连打：拍一下等 2 刻看清掉了多少血就当刻发出去，连打交给挨打事件与自卫，
 * 正在和他切磋的不记。同一个人 1.5 秒没有新动作就把这一段发出去，一直在做的每 6 秒发一条；
 * 他做完走开、5 秒内又回头看角色的，等他回头一起说。一段里至少有一下冲着角色才发；
 * 一个人一分钟超过 12 条，这一分钟剩下的合成一条写总数。最近被谁拍了一下、他最近一下冲角色做动作在哪刻，
 * 交给被拍了回头看。只在客户端线程读写。
 */
public final class NonverbalWatch implements SwingsSeen, DiggingSeen, EntityHurtSeen, DamageObserver, PokesFelt {

    /** 同一个人这么久（刻）没有新动作，这一段就发出去：1.5 秒。 */
    static final int QUIET_TICKS = 30;
    /** 一直在做时每这么久（刻）发一条：6 秒。 */
    static final int WINDOW_TICKS = 120;
    /** 每个人的采样留多久（刻）。 */
    static final int KEEP_TICKS = 200;
    /** 挥手包到了以后等几刻再判是不是挥手：挖方块的进度包、伤害包、方块变化前后 2 刻内都可能到。 */
    static final int SWING_SETTLE_TICKS = 2;
    /** 同一个人一分钟最多发几条，再多的合成一条。 */
    static final int FLOOD_PER_MINUTE = 12;
    static final int MINUTE_TICKS = 1200;
    /** 扔过来的东西落在角色多近（格）内算扔到身边；飞多久（刻）还没落地就按当时的位置算。 */
    static final double TOSS_NEAR_BLOCKS = 3;
    static final int TOSS_FLIGHT_TICKS = 40;
    /** 这么久（刻）没再看见的人不再记。 */
    static final int FORGET_TICKS = 200;
    /** 被打中以后等几刻看清掉了多少血。 */
    static final int HIT_SETTLE_TICKS = 2;

    private final TaskEventSink events;
    private final Function<Integer, Optional<String>> seenIdOf;
    private final Consumer<UUID> noticed;
    private final PlayerHitTally tally = new PlayerHitTally();
    private final SparPartner sparring;
    private final Consumer<Beating> beatings;
    private final List<SelfHit> selfHits = new ArrayList<>();
    private final Map<UUID, Long> lastSelfHit = new HashMap<>();
    private float health = -1;
    private final Map<UUID, Watched> watched = new LinkedHashMap<>();
    private final List<PendingSwing> swings = new ArrayList<>();
    private final Map<Integer, Long> lastDigging = new HashMap<>();
    private final Map<Integer, Long> lastHitOther = new HashMap<>();
    private final Map<Integer, Toss> flying = new HashMap<>();
    private final Set<Integer> knownItems = new HashSet<>();
    private LocalPlayer me;
    private Poke latestPoke;
    private UUID lastToward;
    private long lastTowardTick = Long.MIN_VALUE;

    /**
     * 被一名玩家连打的那一下：谁、哪刻、什么伤、掉了多少血。挨打事件与战斗证据从这里接。
     *
     * @param called "玩家 Alex（e4）"这样的称呼
     */
    public record Beating(UUID who, int entityId, String called, long tick, String damageType, float lost) {}

    /**
     * @param seenIdOf 游戏实体编号 → 这会儿的观察编号（看得见才有）
     * @param noticed  发出一段冲着角色的肢体动作时告诉谁（闲时看把他和刚说过话的人排在一起）
     * @param sparring 正在和谁切磋：他打中角色的不进拍一下的账
     * @param beatings 被玩家连打的每一下交给谁（挨打事件、战斗证据）
     */
    public NonverbalWatch(TaskEventSink events, Function<Integer, Optional<String>> seenIdOf, Consumer<UUID> noticed,
                          SparPartner sparring, Consumer<Beating> beatings) {
        this.events = Objects.requireNonNull(events, "events");
        this.seenIdOf = Objects.requireNonNull(seenIdOf, "seenIdOf");
        this.noticed = Objects.requireNonNull(noticed, "noticed");
        this.sparring = Objects.requireNonNull(sparring, "sparring");
        this.beatings = Objects.requireNonNull(beatings, "beatings");
    }

    // 角色被打中：出手的是别的玩家（直接打的、射箭扔雪球的都算他）就记下来，等 2 刻看清掉了多少血再分拍一下还是连打；
    // 正在和他切磋的不记，切磋自己记。背后打的也记：身体被打中感觉得到。
    @Override public void hurt(LocalPlayer player, DamageSource source, long gameTick) {
        if (!(source.getEntity() instanceof Player other) || other == player || sparring.with(other.getUUID())) return;
        Watched who = watched.computeIfAbsent(other.getUUID(), uuid -> new Watched(uuid, gameTick - 1));
        who.name = other.getGameProfile().getName();
        who.entityId = other.getId();
        boolean missile = source.getDirectEntity() instanceof Projectile;
        String hand = other.getMainHandItem().isEmpty() ? "" : other.getMainHandItem().getHoverName().getString();
        selfHits.add(new SelfHit(who, gameTick, health, missile, source.type().msgId(), hand));
        lastSelfHit.put(other.getUUID(), gameTick);
    }

    /** 最近一个冲角色做过肢体动作的玩家，限 withinTicks 刻内；没有为空。 */
    public Optional<UUID> lastTowardMe(long now, long withinTicks) {
        return lastToward != null && now - lastTowardTick <= withinTicks ? Optional.of(lastToward) : Optional.empty();
    }

    // 挥手包：只记看得见的别的玩家；挥向哪当场按他的视线算，过 2 刻再判是不是挖矿、打东西、放方块带的。
    @Override public void swung(Entity entity, boolean mainHand, long gameTick) {
        if (!(entity instanceof Player other) || other == me || me == null) return;
        Watched who = watched.get(other.getUUID());
        if (who == null || who.lastSeen < gameTick - 1) return;
        swings.add(new PendingSwing(who, gameTick, aim(other)));
    }

    @Override public void digging(int breakerId, BlockPos pos, int progress, long gameTick) {
        lastDigging.put(breakerId, gameTick);
    }

    @Override public void hurt(Entity entity, DamageSource source, long gameTick) {
        if (source.getEntity() != null) lastHitOther.put(source.getEntity().getId(), gameTick);
        if (source.getDirectEntity() != null) lastHitOther.put(source.getDirectEntity().getId(), gameTick);
    }

    @Override public Optional<Poke> latestPoke() {
        return Optional.ofNullable(latestPoke);
    }

    @Override public long lastActTowardMe(UUID who) {
        Watched watching = watched.get(who);
        return watching == null ? Long.MIN_VALUE : watching.lastActTowardMe;
    }

    /** 换世界、断线：记的人、没发出的段、在飞的东西、拍一下的账都丢掉。 */
    public void forget() {
        watched.clear();
        latestPoke = null;
        selfHits.clear();
        lastSelfHit.clear();
        tally.clear();
        health = -1;
        swings.clear();
        lastDigging.clear();
        lastHitOther.clear();
        flying.clear();
        knownItems.clear();
        me = null;
        lastToward = null;
    }

    /** 每刻：采样看得见的人、认扔过来的东西、判挥手，到点的段发出去。 */
    public void tick(PlayerContext context) {
        if (context == null || context.localPlayer() == null || context.level() == null) return;
        me = context.localPlayer();
        Level level = context.level();
        long now = level.getGameTime();
        sample(level, now);
        watchTosses(level, now);
        settleSwings(level, now);
        settleHits(now);
        health = me.getHealth();
        for (Watched who : watched.values()) {
            decide(who, now);
            flushOverflow(who, now);
        }
        watched.values().removeIf(who -> now - who.lastSeen > FORGET_TICKS && who.pending(now).isEmpty());
        lastDigging.values().removeIf(at -> now - at > 40);
        lastHitOther.values().removeIf(at -> now - at > 40);
    }

    // 16 格内看得见的别的玩家各采一份：在哪、头朝哪、蹲没蹲、拿着什么、看没看着角色，和准星落在哪一格。
    private void sample(Level level, long now) {
        for (Player other : level.players()) {
            if (other == me || other.isSpectator() || other.distanceTo(me) > LooksAt.RANGE
                    || !ObservationVisibility.entity(me, other)) continue;
            Watched who = watched.computeIfAbsent(other.getUUID(), uuid -> new Watched(uuid, now));
            who.name = other.getGameProfile().getName();
            who.entityId = other.getId();
            who.lastSeen = now;
            who.poses.addLast(new PlayerPose(now, other.getX(), other.getY(), other.getZ(), other.onGround(),
                    other.getYHeadRot(), other.getXRot(), other.isCrouching(),
                    other.getMainHandItem().isEmpty() ? "" : other.getMainHandItem().getHoverName().getString(),
                    LooksAt.looksAt(other, me)));
            who.aims.addLast(aimedBlock(other, level, now));
            while (!who.poses.isEmpty() && now - who.poses.peekFirst().tick() > KEEP_TICKS) who.poses.removeFirst();
            while (who.aims.size() > 5) who.aims.removeFirst();
        }
    }

    // 新掉落物：生成没几刻、生成点与初速对得上谁扔的就跟着看落在哪；落地或飞满 2 秒就记成他扔了东西。
    private void watchTosses(Level level, long now) {
        Set<Integer> present = new HashSet<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, me.getBoundingBox().inflate(LooksAt.RANGE))) {
            present.add(item.getId());
            if (!knownItems.add(item.getId()) || item.tickCount > 3) continue;
            Watched thrower = throwerOf(level, item);
            if (thrower != null) {
                flying.put(item.getId(), new Toss(thrower, now, item.getItem().getHoverName().getString(),
                        item.getItem().getCount()));
                thrower.tossedAt = now;
            }
        }
        knownItems.retainAll(present);
        Iterator<Map.Entry<Integer, Toss>> it = flying.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Toss> entry = it.next();
            Toss toss = entry.getValue();
            Entity item = level.getEntity(entry.getKey());
            if (item != null && !item.onGround() && now - toss.since < TOSS_FLIGHT_TICKS) continue;
            it.remove();
            toss.who.events.add(tossAct(toss, item, entry.getKey(), now));
        }
    }

    private Watched throwerOf(Level level, ItemEntity item) {
        Watched best = null;
        double bestMiss = Double.MAX_VALUE;
        for (Watched who : watched.values()) {
            if (who.lastSeen != level.getGameTime()) continue;
            Entity entity = level.getEntity(who.entityId);
            if (!(entity instanceof Player other)) continue;
            Optional<Double> miss = NonverbalReading.tossMiss(other.getEyePosition(), other.getViewVector(1.0f),
                    item.position(), item.getDeltaMovement(), item.tickCount);
            if (miss.isPresent() && miss.get() < bestMiss) {
                best = who;
                bestMiss = miss.get();
            }
        }
        return best;
    }

    // 落在角色 3 格内算扔给角色：写扔了几个什么、落在角色哪边几格；被捡走、飞没了的按最后看到的说。
    private NonverbalAct tossAct(Toss toss, Entity item, int itemId, long now) {
        String what = toss.count + " 个" + toss.name + seenIdOf.apply(itemId).map(id -> "（" + id + "）").orElse("");
        if (item == null) {
            return new NonverbalAct(NonverbalAct.Kind.TOSS, now, 1, false, "扔了 " + what + "，还没落地就不见了");
        }
        double dx = item.getX() - me.getX();
        double dz = item.getZ() - me.getZ();
        double distance = Math.hypot(dx, dz);
        boolean near = distance <= TOSS_NEAR_BLOCKS;
        String landed = distance < 1 ? "落在你脚边"
                : "落在你" + DirectionWords.relative(dx, dz, me.getYRot()) + " " + Math.round(distance) + " 格";
        return new NonverbalAct(NonverbalAct.Kind.TOSS, now, 1, near, (near ? "朝你扔了 " : "扔了 ") + what + "，" + landed);
    }

    // 挥手过 2 刻再判：前后 2 刻内他在挖方块、打中了别的东西、准星那格或旁边冒出（少了）方块、刚扔了东西，都不算挥手。
    private void settleSwings(Level level, long now) {
        Iterator<PendingSwing> it = swings.iterator();
        while (it.hasNext()) {
            PendingSwing swing = it.next();
            if (now - swing.tick < SWING_SETTLE_TICKS) continue;
            it.remove();
            Watched who = swing.who;
            if (near(lastDigging.get(who.entityId), swing.tick) || near(lastHitOther.get(who.entityId), swing.tick)
                    || near(who.tossedAt, swing.tick) || near(lastSelfHit.get(who.uuid), swing.tick)
                    || blockChanged(who, level, swing.tick)) continue;
            // 对着够得着的角色挥手却没打出伤害（服务器关了 PvP、无敌帧里）：也是拍了一下，没伤到。
            if (swing.target.reachesMe) {
                poked(who, poke(swing.tick, "", 0));
                continue;
            }
            who.events.add(new NonverbalAct(NonverbalAct.Kind.SWING, swing.tick, 1, swing.target.towardMe,
                    swing.target.words));
        }
    }

    // 打中角色的那一下过 2 刻：掉了多少血看清了，按账分拍一下还是连打。拍一下进这一段当刻发，连打交出去。
    private void settleHits(long now) {
        Iterator<SelfHit> it = selfHits.iterator();
        float lostSoFar = 0;
        while (it.hasNext()) {
            SelfHit hit = it.next();
            if (now - hit.tick < HIT_SETTLE_TICKS) continue;
            it.remove();
            float lost = hit.healthBefore < 0 ? 0 : Math.max(0, hit.healthBefore - me.getHealth() - lostSoFar);
            lostSoFar += lost;
            double effective = ThreatAssessment.effectiveHealth(me.getHealth(), me.getArmorValue());
            PlayerHitTally.Verdict verdict = tally.hit(hit.who.uuid, hit.tick, lost, effective, hit.missile && lost > 0);
            if (verdict == PlayerHitTally.Verdict.POKE) {
                poked(hit.who, poke(hit.tick, hit.hand, lost));
            } else {
                beatings.accept(new Beating(hit.who.uuid, hit.who.entityId, hit.who.called(seenIdOf), hit.tick,
                        hit.damageType, lost));
            }
        }
    }

    // 被拍了一下：进这一段当刻发出去，并记成最近一次被拍（回头看他）。
    private void poked(Watched who, NonverbalAct act) {
        who.events.add(act);
        who.pokedAt = act.tick();
        latestPoke = new Poke(who.uuid, who.entityId, who.name, act.tick());
    }

    // 拍一下的那句细节：拿着什么（空手）、掉没掉血。
    private static NonverbalAct poke(long tick, String hand, float lost) {
        String with = hand.isEmpty() ? "空手" : "拿着" + hand;
        String hurt = lost <= 0 ? "没伤到" : "掉了 " + (lost == Math.rint(lost) ? String.valueOf((int) lost)
                : String.format(Locale.ROOT, "%.1f", lost)) + " 点血";
        return new NonverbalAct(NonverbalAct.Kind.POKE, tick, 1, true, with + "，" + hurt);
    }

    private static boolean near(Long at, long tick) {
        return at != null && Math.abs(at - tick) <= SWING_SETTLE_TICKS;
    }

    private static boolean blockChanged(Watched who, Level level, long tick) {
        for (AimedBlock aim : who.aims) {
            if (aim.pos == null || aim.tick < tick - SWING_SETTLE_TICKS || aim.tick > tick) continue;
            if (level.getBlockState(aim.pos) != aim.state || level.getBlockState(aim.front) != aim.frontState) return true;
        }
        return false;
    }

    // 一个人的段到点没有：6 秒满了、走开又回头了，或者 1.5 秒没新动作（东西还在飞、他还在走开的路上时再等等）。
    private void decide(Watched who, long now) {
        List<NonverbalAct> acts = who.pending(now);
        if (acts.isEmpty()) return;
        // 他接着冲角色做动作：回头看他的就多看一会儿。
        acts.stream().filter(NonverbalAct::towardMe).mapToLong(NonverbalAct::tick).max()
                .ifPresent(tick -> who.lastActTowardMe = Math.max(who.lastActTowardMe, tick));
        long first = acts.get(0).tick();
        long last = acts.get(acts.size() - 1).tick();
        boolean walkedBack = acts.get(acts.size() - 1).kind() == NonverbalAct.Kind.WALK;
        boolean inFlight = flying.values().stream().anyMatch(toss -> toss.who == who);
        boolean waiting = inFlight || NonverbalReading.walkingAway(List.copyOf(who.poses), last, now);
        // 拍一下当刻发：不等 1.5 秒，这一段之前攒下的一起带上。
        boolean poked = who.pokedAt != null && who.pokedAt > who.cut;
        if (poked || due(first, last, now, walkedBack, waiting)) publish(who, acts, now);
    }

    /**
     * 一段该不该发了：从第一下起满 6 秒就发；做完走开又回头了就发；1.5 秒没有新动作也发，
     * 除非扔出的东西还在飞、他还在走开的路上（等他回头一起说）。纯函数。
     */
    static boolean due(long first, long last, long now, boolean walkedBack, boolean waiting) {
        return now - first >= WINDOW_TICKS || walkedBack || now - last >= QUIET_TICKS && !waiting;
    }

    private void publish(Watched who, List<NonverbalAct> acts, long now) {
        who.cut = now;
        who.events.clear();
        if (acts.stream().noneMatch(NonverbalAct::towardMe)) return;
        lastToward = who.uuid;
        lastTowardTick = now;
        noticed.accept(who.uuid);
        if (now - who.minuteStart >= MINUTE_TICKS) {
            who.minuteStart = now;
            who.publishedThisMinute = 0;
        }
        if (who.publishedThisMinute >= FLOOD_PER_MINUTE) {
            for (NonverbalAct act : acts) who.overflow.merge(act.kind(), act.count(), Integer::sum);
            return;
        }
        who.publishedThisMinute++;
        PlayerPose last = who.poses.peekLast();
        events.publish(TaskEvent.Kind.PLAYER_BODY_LANGUAGE,
                NonverbalWords.sentence(who.called(seenIdOf), acts, where(last), last != null && last.lookingAtMe()),
                who.player(seenIdOf));
    }

    // 一分钟满了还压着的合成一条：这一分钟又蹲起几下、挥手几下。
    private void flushOverflow(Watched who, long now) {
        if (who.overflow.isEmpty() || now - who.minuteStart < MINUTE_TICKS) return;
        events.publish(TaskEvent.Kind.PLAYER_BODY_LANGUAGE, NonverbalWords.overflow(who.called(seenIdOf), who.overflow),
                who.player(seenIdOf));
        who.overflow.clear();
        who.minuteStart = now;
        who.publishedThisMinute = 0;
    }

    private String where(PlayerPose pose) {
        if (pose == null) return "他已经看不见了";
        DirectionWords.Bearing bearing = DirectionWords.Bearing.byBlock(me.getX(), me.getY(), me.getZ(), me.getYRot(),
                (int) Math.floor(pose.x()), (int) Math.floor(pose.y()), (int) Math.floor(pose.z()));
        return "他在你" + bearing.direction() + " " + bearing.distance() + " 格";
    }

    // 挥手当刻他挥向哪：顺着视线最先碰到的是角色、别的东西、够得着的方块，还是空中（看着角色、挥向空中也算冲着角色）。
    private SwingTarget aim(Player other) {
        Vec3 eye = other.getEyePosition();
        Vec3 look = other.getViewVector(1.0f);
        Level level = other.level();
        double entityReach = InteractionRange.entityReach(other);
        Entity hit = null;
        double hitDistance = entityReach;
        for (Entity entity : level.getEntities(other, other.getBoundingBox().expandTowards(look.scale(entityReach))
                .inflate(1.0), entity -> !entity.isSpectator() && entity.isPickable())) {
            Optional<Vec3> at = entity.getBoundingBox().inflate(entity.getPickRadius())
                    .clip(eye, eye.add(look.scale(entityReach)));
            if (at.isPresent() && eye.distanceTo(at.get()) < hitDistance) {
                hit = entity;
                hitDistance = eye.distanceTo(at.get());
            }
        }
        BlockHitResult block = level.clip(new ClipContext(eye, eye.add(look.scale(InteractionRange.blockReach(other))),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, other));
        boolean blockFirst = block.getType() == HitResult.Type.BLOCK
                && eye.distanceTo(block.getLocation()) < hitDistance;
        if (hit == me && !blockFirst) return new SwingTarget(true, true, "对着你");
        if (hit != null && !blockFirst) {
            String name = hit.hasCustomName() ? hit.getCustomName().getString() : hit.getType().getDescription().getString();
            return new SwingTarget(false, false, "对着" + name + seenIdOf.apply(hit.getId()).map(id -> "（" + id + "）")
                    .orElse(""));
        }
        if (blockFirst) {
            BlockPos pos = block.getBlockPos();
            DirectionWords.Bearing bearing = DirectionWords.Bearing.byBlock(me.getX(), me.getY(), me.getZ(),
                    me.getYRot(), pos.getX(), pos.getY(), pos.getZ());
            return new SwingTarget(false, false, "对着" + level.getBlockState(pos).getBlock().getName().getString()
                    + "（你" + bearing.direction() + " " + bearing.distance() + " 格）");
        }
        boolean looking = LooksAt.looksAt(other, me);
        return new SwingTarget(looking, false, looking ? "对着你" : "朝空中");
    }

    // 准星落在够得着的哪一格和它前面那格，连同此刻的方块：挥手前后这两格变了，就是在放方块或一下挖掉了方块。
    private static AimedBlock aimedBlock(Player other, Level level, long now) {
        Vec3 eye = other.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye,
                eye.add(other.getViewVector(1.0f).scale(InteractionRange.blockReach(other))),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, other));
        if (hit.getType() != HitResult.Type.BLOCK) return new AimedBlock(now, null, null, null, null);
        BlockPos front = hit.getBlockPos().relative(hit.getDirection());
        return new AimedBlock(now, hit.getBlockPos(), level.getBlockState(hit.getBlockPos()), front,
                level.getBlockState(front));
    }

    /** 挥向哪：冲没冲着角色、够不够得着角色（打中角色就是拍一下）、怎么说。 */
    private record SwingTarget(boolean towardMe, boolean reachesMe, String words) {}

    private record PendingSwing(Watched who, long tick, SwingTarget target) {}

    /** 被别的玩家打中的一下：谁、哪刻、挨打前的血、是不是投射物、什么伤、他手上拿着什么。 */
    private record SelfHit(Watched who, long tick, float healthBefore, boolean missile, String damageType, String hand) {}

    private record AimedBlock(long tick, BlockPos pos, BlockState state, BlockPos front, BlockState frontState) {}

    /** 在飞的一件东西：谁扔的、哪刻扔的、是什么、几个。 */
    private record Toss(Watched who, long since, String name, int count) {}

    /** 看着的一个人：采样、事件型的动作（挥手、扔东西）、上一段发到哪刻、最近一下冲角色做动作在哪刻、这一分钟发了几条。 */
    private static final class Watched {
        final UUID uuid;
        final Deque<PlayerPose> poses = new ArrayDeque<>();
        final Deque<AimedBlock> aims = new ArrayDeque<>();
        final List<NonverbalAct> events = new ArrayList<>();
        final Map<NonverbalAct.Kind, Integer> overflow = new EnumMap<>(NonverbalAct.Kind.class);
        String name = "";
        int entityId;
        long lastSeen;
        long cut;
        Long tossedAt;
        Long pokedAt;
        long lastActTowardMe = Long.MIN_VALUE;
        long minuteStart;
        int publishedThisMinute;

        Watched(UUID uuid, long now) {
            this.uuid = uuid;
            this.cut = now;
            this.minuteStart = now;
        }

        // 上一段发出之后的动作，按先后：采样认出来的、挥手和扔东西；只跳一下的不算（走路常跳），
        // 只换了手、别的什么都没做的不成一段；做完走开又回头的接在最后。
        List<NonverbalAct> pending(long now) {
            List<PlayerPose> list = List.copyOf(poses);
            List<NonverbalAct> acts = new ArrayList<>(NonverbalReading.read(list, cut));
            for (NonverbalAct act : events) {
                if (act.tick() > cut) acts.add(act);
            }
            if (acts.stream().filter(act -> act.kind() == NonverbalAct.Kind.JUMP).count() == 1) {
                acts.removeIf(act -> act.kind() == NonverbalAct.Kind.JUMP);
            }
            acts.sort((a, b) -> Long.compare(a.tick(), b.tick()));
            if (acts.stream().allMatch(act -> act.kind() == NonverbalAct.Kind.HOLD)) return List.of();
            // 早在这一段之前换的手不算进来：只留第一下肢体动作前 1.5 秒内拿出的。
            long firstReal = acts.stream().filter(act -> act.kind() != NonverbalAct.Kind.HOLD)
                    .mapToLong(NonverbalAct::tick).min().orElseThrow();
            acts.removeIf(act -> act.kind() == NonverbalAct.Kind.HOLD && act.tick() < firstReal - QUIET_TICKS);
            long lastTick = acts.get(acts.size() - 1).tick();
            NonverbalReading.walkedBack(list, lastTick).ifPresent(acts::add);
            return acts;
        }

        String called(Function<Integer, Optional<String>> seenIdOf) {
            return "玩家 " + name + seenIdOf.apply(entityId).map(id -> "（" + id + "）").orElse("");
        }

        NonverbalPlayer player(Function<Integer, Optional<String>> seenIdOf) {
            return new NonverbalPlayer(name, uuid.toString(), seenIdOf.apply(entityId).orElse(null));
        }
    }
}
