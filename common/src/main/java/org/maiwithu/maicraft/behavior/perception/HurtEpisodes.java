// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntPredicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import org.maiwithu.maicraft.game.player.DamageObserver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.FieldOfView;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;

/**
 * 挨打的片段（事件 hurt）：同一个来源（同一个攻击者，没有攻击者时同一种伤害）连着挨的算一段，
 * 5 秒没再挨这个来源的打就把这一段发出去；一直挨着打不停时每 10 秒发一条，只算这 10 秒里的。
 * 每条都是自成一体的"刚才这阵"，不分开始与结束；「还剩多少血」写最后一下扣完当刻的血，不写发出去时回了血的数。
 *
 * <p>攻击者身份以角色亲眼看见为准：挨打当刻或这一段发出前在眼前视野里无遮挡看见过凶手，才说出是谁打的并铺上攻击者字段；
 * 背后挨冷箭、隔着遮挡没看见凶手时只按伤害性质说"不知从哪射来箭打中"这类玩家真实能感知到的事实，不借伤害包透视凶手身份。
 * 没收到伤害包、只看到血掉了的，单独一段写明"只看到血掉了"。只看血量，黄心（伤害吸收）到期掉的不算挨打。
 * 死了的时候没发出的段丢掉（角色死了已有自己的事件），换世界、断线时也丢掉。
 * 别的玩家打中的分开算：拍一下不进片段（它在别的玩家的肢体动作事件里说一次），连打从变成连打的那一下起
 * 经 {@link #beaten} 补进来，切磋对手的出手不进；同一下伤害只出现在一条事件里。
 */
public final class HurtEpisodes implements DamageObserver {

    /** 这么久没再挨这个来源的打，这一段就发出去。C 玩家常识，来源：一阵交手间隔。 */
    static final int QUIET_TICKS = 100;
    /** 一直挨着打时，每过这么久发一条。C 玩家常识，来源：长战斗播报节奏。 */
    static final int WINDOW_TICKS = 200;
    /** 伤害包到了以后，这么多刻里血掉的算这一下的。F 游戏事实，来源：伤害包与生命值同步刻差。 */
    private static final int ATTRIBUTE_TICKS = 2;
    private static final String UNSEEN = "unseen";
    /** 别的玩家刚打中的那一下：拍一下还是连打要过 2 刻才分得清，这一下掉的血不记到任何片段上。 */
    private static final String BY_PLAYER = "by-player";

    private final TaskEventSink events;
    private final Function<Integer, Optional<String>> seenIdOf;
    /** 角色此刻在眼前视野里看不看得见这个实体编号的攻击者。 */
    private final IntPredicate attackerVisible;
    /** 没有攻击者的伤（原版 msgId）是碰着哪种方块扎的、记没记下：一句话里带上。 */
    private final Function<String, Optional<String>> culpritOf;
    private final Map<String, Episode> open = new LinkedHashMap<>();
    private Float lastHealth;
    private String lastHitKey;
    private long lastHitTick = Long.MIN_VALUE;

    /**
     * @param seenIdOf 游戏实体编号 → 这会儿的观察编号（在场景里才有），消息里带上让 LLM 能指它
     */
    public HurtEpisodes(TaskEventSink events, Function<Integer, Optional<String>> seenIdOf) {
        this(events, seenIdOf, entityId -> true, type -> Optional.empty());
    }

    /** @param culpritOf 没有攻击者的伤是碰着哪种方块扎的（吃过亏的方块认出来的），认不出为空 */
    public HurtEpisodes(TaskEventSink events, Function<Integer, Optional<String>> seenIdOf,
                        Function<String, Optional<String>> culpritOf) {
        this(events, seenIdOf, entityId -> true, culpritOf);
    }

    /**
     * @param attackerVisible 角色此刻在眼前视野里看不看得见该游戏实体编号的凶手；全程没看见时不报凶手身份
     * @param culpritOf       没有攻击者的伤是碰着哪种方块扎的，认不出为空
     */
    public HurtEpisodes(TaskEventSink events, Function<Integer, Optional<String>> seenIdOf,
                        IntPredicate attackerVisible, Function<String, Optional<String>> culpritOf) {
        this.events = Objects.requireNonNull(events, "events");
        this.seenIdOf = Objects.requireNonNull(seenIdOf, "seenIdOf");
        this.attackerVisible = Objects.requireNonNull(attackerVisible, "attackerVisible");
        this.culpritOf = Objects.requireNonNull(culpritOf, "culpritOf");
    }

    /**
     * 一下伤害的来源：归并用的键、攻击者怎么称呼（没有为 null）、原版伤害类型、
     * 服务端伤害包带来的攻击者信息（仅在角色亲眼看见凶手时才对外写出）。
     */
    record Hit(String key, String attacker, String damageType,
               String attackerType, String attackerName, Integer attackerEntityId) {
    }

    // 伤害包：认出伤害类型并记下来源；是不是真看见了凶手按当刻视野记，随后几秒回头看见了也会补认。
    @Override public void hurt(LocalPlayer player, DamageSource source, long gameTick) {
        Entity attacker = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        String type = source.type().msgId();
        // 别的玩家打中的：先只记下"这一下是玩家打的"，掉的血不算别人头上，也不算"只看到血掉了"。
        if (attacker instanceof Player other && other != player) {
            byPlayer(gameTick);
            return;
        }
        if (attacker == null) {
            hit(new Hit("type:" + type, null, type, null, null, null), false, gameTick);
            return;
        }
        int entityId = attacker.getId();
        // 伤害包在客户端刻开头先于控制边界到达，这里直接拿当刻本地玩家与凶手实体核对前方视锥与无遮挡视线。
        boolean visibleNow = FieldOfView.canSeeEntity(player, attacker, 64.0);
        String name = attacker instanceof Player other ? "玩家 " + other.getGameProfile().getName()
                : attacker.hasCustomName() ? attacker.getCustomName().getString()
                : attacker.getType().getDescription().getString();
        String seen = visibleNow ? seenIdOf.apply(entityId).map(id -> "（" + id + "）").orElse("") : "";
        hit(new Hit("entity:" + entityId, name + seen, type,
                BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString(), name, entityId),
                visibleNow, gameTick);
    }

    /** 每刻看一眼血量与还没认出凶手的在途片段：回头看见凶手就补认，到点的段发出去。 */
    public void tick(PlayerContext context) {
        if (context == null || context.localPlayer() == null || context.level() == null) return;
        LocalPlayer self = context.localPlayer();
        health(self.getHealth(), self.isDeadOrDying(), context.level().getGameTime());
    }

    /**
     * 被别的玩家连打的一下（拍一下的账判出来的，掉了多少血已经看清）：进这名玩家的片段，照常 5 秒没再挨就发。
     */
    public void beaten(String attacker, int entityId, String damageType, float lost, long tick) {
        String clean = attacker.replaceAll("（e\\d+）$", "");
        boolean visibleNow = attackerVisible.test(entityId);
        String label = visibleNow ? attacker : clean;
        Hit hit = new Hit("entity:" + entityId, label, damageType, "minecraft:player", clean, entityId);
        Episode episode = open.computeIfAbsent(hit.key(), key -> new Episode(hit, tick));
        episode.hits++;
        episode.lost += lost;
        episode.lastTick = tick;
        if (visibleNow) episode.markSeen(attacker);
        if (lastHealth != null) episode.healthAfter = lastHealth;
    }

    /** 换世界、断线：没发出的段丢掉，血量基准重来。 */
    public void forget() {
        open.clear();
        lastHealth = null;
        lastHitKey = null;
    }

    // 别的玩家打中的这一下：接下来 2 刻里掉的血留给拍一下的账去分，不记到片段上。
    void byPlayer(long tick) {
        lastHitKey = BY_PLAYER;
        lastHitTick = tick;
    }

    void hit(Hit hit, long tick) {
        hit(hit, hit.attackerEntityId() != null && attackerVisible.test(hit.attackerEntityId()), tick);
    }

    void hit(Hit hit, boolean visibleNow, long tick) {
        Episode episode = open.computeIfAbsent(hit.key(), key -> new Episode(hit, tick));
        episode.hits++;
        episode.lastTick = tick;
        if (visibleNow) {
            episode.markSeen(labelWithSeenId(hit));
        }
        // 这一下还没看到血掉：先按挨打前的血记，等下一刻真掉了再改成掉完的；
        // 要是始终没掉（0 伤的轻摔），发出去时写的就是这一下当刻没变的血。
        if (lastHealth != null) episode.healthAfter = lastHealth;
        lastHitKey = hit.key();
        lastHitTick = tick;
    }

    void health(float health, boolean dead, long tick) {
        if (dead || health <= 0f) {
            // 死了：角色死了的事件已经说了，没发出的段直接丢掉。
            forget();
            return;
        }
        refreshSeenAttackers();
        boolean byPlayer = BY_PLAYER.equals(lastHitKey) && tick - lastHitTick <= ATTRIBUTE_TICKS;
        if (lastHealth != null && health < lastHealth && !byPlayer) {
            float lost = lastHealth - health;
            Episode episode = lastHitKey != null && tick - lastHitTick <= ATTRIBUTE_TICKS ? open.get(lastHitKey) : null;
            if (episode == null) {
                episode = open.computeIfAbsent(UNSEEN, key -> new Episode(new Hit(UNSEEN, null, "", null, null, null), tick));
                episode.lastTick = tick;
            }
            episode.lost += lost;
            // 「还剩」记这一下扣完当刻的血：这段要等 5 秒没再挨打才发，吃饱了的角色这 5 秒能回好几点，
            // 发出去时再读血量就会写成回血以后的数，和当时掉完的对不上。
            episode.healthAfter = health;
        }
        lastHealth = health;
        flush(health, tick);
    }

    // 背后挨打后几秒内角色转头看见了那只凶手：把片段标为已亲眼看见，并补上当刻观察编号。
    private void refreshSeenAttackers() {
        for (Episode episode : open.values()) {
            Integer entityId = episode.hit.attackerEntityId();
            if (entityId != null && !episode.attackerSeen && attackerVisible.test(entityId)) {
                episode.markSeen(labelWithSeenId(episode.hit));
            }
        }
    }

    private String labelWithSeenId(Hit hit) {
        if (hit.attacker() != null && hit.attacker().matches(".*（e\\d+）$")) {
            return hit.attacker();
        }
        String base = hit.attackerName() != null ? hit.attackerName() : hit.attacker();
        if (base == null || hit.attackerEntityId() == null) {
            return base;
        }
        return base + seenIdOf.apply(hit.attackerEntityId()).map(id -> "（" + id + "）").orElse("");
    }

    // 到点的段发出去：5 秒没再挨这个来源的打整段发；一直在挨的每 10 秒发一条再接着记。
    private void flush(float health, long tick) {
        var it = open.values().iterator();
        while (it.hasNext()) {
            Episode episode = it.next();
            if (tick - episode.lastTick >= QUIET_TICKS) {
                publish(episode, health);
                it.remove();
            } else if (tick - episode.windowStart >= WINDOW_TICKS) {
                publish(episode, health);
                episode.hits = 0;
                episode.lost = 0f;
                episode.windowStart = tick;
            }
        }
    }

    private void publish(Episode episode, float health) {
        if (episode.hits == 0 && episode.lost <= 0f) return;
        float remaining = episode.healthAfter != null ? episode.healthAfter : health;
        // 有实体来源但全程没在视野里看见凶手：只报伤害媒介，不透视报出凶手身份与攻击者字段。
        if (episode.hit.attackerEntityId() != null && !episode.attackerSeen) {
            events.publish(TaskEvent.Kind.HURT,
                    describeUnseenAttacker(episode.hit.damageType(), episode.hits, episode.lost, remaining));
            return;
        }
        String attackerLabel = episode.seenLabel != null ? episode.seenLabel : episode.hit.attacker();
        String line = describe(episode.hit, attackerLabel, episode.hits, episode.lost, remaining);
        if (attackerLabel == null && !episode.hit.key().equals(UNSEEN)) {
            line += culpritOf.apply(episode.hit.damageType()).map(culprit -> "（" + culprit + "）").orElse("");
        }
        if (episode.hit.attackerType() != null) {
            events.publish(TaskEvent.Kind.HURT, line, new AttackerFacts(episode.hit.attackerType(),
                    episode.hit.attackerName(), episode.hit.attackerEntityId()));
            return;
        }
        events.publish(TaskEvent.Kind.HURT, line);
    }

    /** 一段挨打的一句话，纯函数：看见凶手时写谁、什么伤、几下、掉了多少、还剩多少；没伤害包的写明只看到血掉了。 */
    static String describe(Hit hit, String attackerLabel, int hits, float lost, float health) {
        String tail = "，掉了 " + number(lost) + " 点血，还剩 " + number(health) + " 点";
        if (hit.key().equals(UNSEEN)) {
            return "只看到血掉了 " + number(lost) + " 点（没收到伤害包，说不清是谁、什么伤），还剩 " + number(health) + " 点";
        }
        String times = hits == 1 ? "一下" : " " + hits + " 下";
        if (attackerLabel != null) return "被" + attackerLabel + attackVerb(hit.damageType()) + times + tail;
        return selfPhrase(hit.damageType(), times) + tail;
    }

    /** 凶手全程不在眼前视野里时的说法：只说玩家能切身感知到的箭、爆炸、魔法或暗处受击，不剧透是谁打的。 */
    static String describeUnseenAttacker(String damageType, int hits, float lost, float health) {
        String times = hits == 1 ? "一下" : " " + hits + " 下";
        String tail = "，掉了 " + number(lost) + " 点血，还剩 " + number(health) + " 点";
        String head = switch (damageType) {
            case "arrow" -> "不知从哪射来箭打中" + times;
            case "trident" -> "不知从哪飞来三叉戟刺中" + times;
            case "fireball", "unattributedFireball", "witherSkull" -> "不知从哪飞来火球炸中" + times;
            case "explosion", "explosion.player" -> "被突如其来的爆炸炸到" + times;
            case "sonic_boom" -> "被暗处的音爆打中" + times;
            case "indirectMagic", "magic" -> "不知被谁用魔法伤到" + times;
            case "thorns" -> "被反伤了" + times;
            default -> "没看清被什么打了" + times;
        };
        return head + tail;
    }

    // 有攻击者时的动词：射中、刺中、炸到……别的一律"打"。
    private static String attackVerb(String type) {
        return switch (type) {
            case "arrow" -> "射中";
            case "trident" -> "刺中";
            case "fireball", "unattributedFireball", "witherSkull" -> "用火球炸中";
            case "explosion", "explosion.player" -> "炸到";
            case "thorns" -> "反伤";
            case "sonic_boom" -> "用音爆打中";
            case "indirectMagic", "magic" -> "用魔法伤到";
            default -> "打了";
        };
    }

    // 没有攻击者时：照原版伤害类型说人话。
    private static String selfPhrase(String type, String times) {
        return switch (type) {
            case "fall" -> "摔了" + times;
            case "drown" -> "溺水呛了" + times;
            case "inFire", "onFire", "campfire" -> "着火烧了" + times;
            case "lava" -> "被岩浆烫了" + times;
            case "hotFloor" -> "被岩浆块烫了" + times;
            case "cactus" -> "被仙人掌扎了" + times;
            case "sweetBerryBush" -> "被浆果丛扎了" + times;
            case "starve" -> "饿得掉血" + times;
            case "inWall" -> "卡在方块里憋了" + times;
            case "cramming" -> "被挤了" + times;
            case "freeze" -> "冻伤了" + times;
            case "wither" -> "凋零掉血" + times;
            case "magic", "indirectMagic" -> "中了魔法伤" + times;
            case "lightningBolt" -> "被雷劈了" + times;
            case "fallingBlock", "fallingStalactite", "anvil" -> "被掉下来的东西砸了" + times;
            case "stalagmite" -> "摔到石笋上" + times;
            case "flyIntoWall" -> "飞着撞墙" + times;
            case "dragonBreath" -> "被龙息伤了" + times;
            case "outOfWorld", "outsideBorder" -> "掉出世界" + times;
            default -> "受了 " + type + " 伤" + times;
        };
    }

    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    /** 一个来源这一段的账：来源、几下、掉了多少、最后一下扣完还剩多少、这一段期间有没有亲眼看见凶手。 */
    private static final class Episode {
        final Hit hit;
        int hits;
        float lost;
        /** 最后一下扣完当刻的血；还没看过血量就挨了打时为 null。 */
        Float healthAfter;
        long lastTick;
        long windowStart;
        boolean attackerSeen;
        String seenLabel;

        Episode(Hit hit, long tick) {
            this.hit = hit;
            this.lastTick = tick;
            this.windowStart = tick;
        }

        void markSeen(String label) {
            this.attackerSeen = true;
            if (label != null && (this.seenLabel == null || label.contains("（"))) {
                this.seenLabel = label;
            }
        }
    }
}
