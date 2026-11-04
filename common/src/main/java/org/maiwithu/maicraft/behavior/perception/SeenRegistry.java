// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 观察编号登记：给本会话里看到的东西发 e#（实体）、f#（地形特征）、b#（设施）编号，
 * 同一样东西在还在场时编号保持不变。
 *
 * <p>编号短期有效：实体最后一次看见后只保留一小会儿，地形特征与设施多留一阵；
 * 超期就算它不在了，由 {@link #expire} 报出最后一次在哪个方位看到、最后位置在哪，
 * 让能力能以"目标不在了"结束并如实交代。之后再见到同一样东西会拿到新编号——
 * 编号是对这次观察会话说的，隔久了还沿用旧编号会让人以为中间一直在看着它。
 *
 * <p>目标在推进时（含 sequence 一步接一步地做）编号不按时间过期：LLM 一口气排好几步、后面几步点的
 * 还是开头看到的设施，做到第几步编号都还认得。只留东西还在的——实体还在这个世界里，
 * 设施那一格还是那种方块；离得远没加载的设施和地形特征照留，到了现场由能力自己看；东西不在了照常失效。
 * 留也有边：离最后一次看见超过 20 分钟的照常失效，到期还留着的最多 256 条，多出来的从最早看见的开始失效。
 * 陪伴、跟随这类一跑一整场直播的目标也在推进，不设边的话一小时前见过的东西还认得、登记册只增不减。
 */
public final class SeenRegistry {

    /** 编号对应的观察种类。 */
    public enum Kind {
        /** 实体：生物、掉落物、矿车这类会动的东西。 */
        ENTITY,
        /** 地形特征：悬崖、水体、树林这类成片地物。 */
        FEATURE,
        /** 设施：箱子、工作台、熔炉、床这类功能方块。 */
        FACILITY,
        /** 物理结构：飞艇、船、车这类会动的方块结构（Sable 的子世界），不是实体，编号 s#。 */
        STRUCTURE,
    }

    /** 一个刚失效的编号：最后看到的方位与位置，用来交代"最后一次在哪儿看见它"。 */
    public record Gone(String id, Kind kind, String lastDirection, WorldPosition lastPosition) {}

    // 实体是会动的，最后一次看见后只信一小会儿；地形特征与设施不动，多留一阵。
    private static final int ENTITY_RETENTION_TICKS = 200;
    private static final int PLACE_RETENTION_TICKS = 2400;
    /**
     * 目标推进中到期的编号最多再留多久，按最后一次看见算：一条 sequence 前几步走路、取物、做东西加起来
     * 十来分钟，留 20 分钟够它做完；再久就照常失效，不让一场陪伴里早就没再看的东西一直认得。
     */
    static final long MAX_KEEP_WHILE_WORKING_TICKS = 20L * 60 * 20;
    /** 目标推进中到期却留下的编号最多几条：多出来的从最后一次看见最早的开始照常失效。 */
    static final int MAX_KEPT_WHILE_WORKING = 256;

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<Integer, String> entityIds = new HashMap<>();
    private final Map<Long, String> placeIds = new HashMap<>();
    private final Map<String, String> structureIds = new HashMap<>();
    private int nextEntity = 1;
    private int nextFeature = 1;
    private int nextFacility = 1;
    private int nextStructure = 1;

    /**
     * 登记一只实体，返回它的观察编号：同一只实体（按游戏实体编号）还在册就沿用旧编号
     * 并刷新方位与时刻；不在册或已失效就发新编号。
     */
    public String entity(int entityId, WorldPosition position, String direction, long nowTick) {
        String existing = entityIds.get(entityId);
        if (existing != null && entries.containsKey(existing)) {
            refresh(existing, position, direction, nowTick, null);
            return existing;
        }
        String id = "e" + nextEntity++;
        entityIds.put(entityId, id);
        put(new Entry(id, Kind.ENTITY, position, direction, nowTick, ENTITY_RETENTION_TICKS, null));
        return id;
    }

    /** 登记一个地形特征，返回观察编号 f#；同一个位置的特征还在册就沿用旧编号。 */
    public String feature(WorldPosition position, String direction, long nowTick) {
        return place(position, null, direction, nowTick, Kind.FEATURE, () -> "f" + nextFeature++);
    }

    /** 登记一个设施，返回观察编号 b#；同一个位置的设施还在册就沿用旧编号。 */
    public String facility(WorldPosition position, String direction, long nowTick) {
        return facility(position, null, direction, nowTick);
    }

    /**
     * 登记一个设施并记下它是哪种方块：目标推进中编号到期时，按这一格还是不是这种方块判断东西还在不在。
     * 方块种类说不出时给 null，只按这一格空没空判断。
     */
    public String facility(WorldPosition position, String blockType, String direction, long nowTick) {
        return place(position, blockType, direction, nowTick, Kind.FACILITY, () -> "b" + nextFacility++);
    }

    /**
     * 登记一个物理结构，返回观察编号 s#：同一个结构（按它自己的身份，例如 Sable 子世界的编号）还在册就沿用旧编号；
     * 结构会动，位置按这次看到的刷新，保留期和实体一样短。
     */
    public String structure(String identity, WorldPosition position, String direction, long nowTick) {
        String existing = structureIds.get(identity);
        if (existing != null && entries.containsKey(existing)) {
            refresh(existing, position, direction, nowTick, null);
            return existing;
        }
        String id = "s" + nextStructure++;
        structureIds.put(identity, id);
        put(new Entry(id, Kind.STRUCTURE, position, direction, nowTick, ENTITY_RETENTION_TICKS, null));
        return id;
    }

    /** 观察编号对应的结构身份；编号不是在册结构时给空。登船这类按编号找回结构用。 */
    public Optional<String> structureOf(String id) {
        return structureIds.entrySet().stream()
                .filter(entry -> entry.getValue().equals(id) && entries.containsKey(id))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** 查一个编号还认不认得：认得就给它的登记项，不认得（从未见过或已失效）给空。 */
    public Optional<Entry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /** 一个结构（按它自己的身份）这会儿的观察编号：在册才有。组装出新结构后告诉 LLM 档案跟到了哪个 s#。 */
    public Optional<String> idOfStructure(String identity) {
        String id = structureIds.get(identity);
        return id != null && entries.containsKey(id) ? Optional.of(id) : Optional.empty();
    }

    /** 游戏实体这会儿的观察编号：在册才有，没登记过或已失效给空。挨打的事件用它告诉 LLM 是哪一只。 */
    public Optional<String> idOfEntity(int entityId) {
        String id = entityIds.get(entityId);
        return id != null && entries.containsKey(id) ? Optional.of(id) : Optional.empty();
    }

    /** 观察编号对应的游戏实体编号；编号不是在册实体时给空。跟随这类能力用它找回真实的实体。 */
    public Optional<Integer> gameEntityOf(String id) {
        return entityIds.entrySet().stream()
                .filter(entry -> entry.getValue().equals(id))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * 观察编号此刻对应的那只实体：按游戏实体编号从这个世界里读回来；编号不是在册实体、
     * 它已经不在这个世界里（走远卸载、死了被移除）时给空。点名一只生物说话、存东西、改设置都从这里找回它。
     */
    public Optional<Entity> entityIn(Level level, String id) {
        if (level == null) return Optional.empty();
        return gameEntityOf(id).map(level::getEntity).filter(entity -> !entity.isRemoved());
    }

    /**
     * 把超期没再见到的编号移出登记，并逐条报告它们最后出现在哪个方位、什么位置。
     * 每次刷新场景后调用一次；返回的失效记录交给关心它们的能力。
     */
    public List<Gone> expire(long nowTick) {
        return expire(nowTick, null);
    }

    /**
     * 同 {@link #expire(long)}，另外给一个"东西还在不在"的判断：目标在推进时传进来，到期但东西还在的编号照留；
     * 没有目标在推进时给 null，到期就失效。
     */
    public List<Gone> expire(long nowTick, Predicate<Entry> stillThereWhileWorking) {
        List<Gone> gone = new ArrayList<>();
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : new ArrayList<>(entries.values())) {
            long unseen = nowTick - entry.lastSeenTick();
            if (unseen <= entry.retentionTicks()) continue;
            // 目标推进中：到期但东西还在、离最后一次看见又没超过 20 分钟的先留着，条数超了下面再淘汰。
            if (stillThereWhileWorking != null && unseen <= MAX_KEEP_WHILE_WORKING_TICKS
                    && stillThereWhileWorking.test(entry)) {
                kept.add(entry);
                continue;
            }
            gone.add(drop(entry));
        }
        // 留下的超过条数上限：从最后一次看见最早的开始照常失效，同样交代最后一次看到的方位。
        if (kept.size() > MAX_KEPT_WHILE_WORKING) {
            kept.sort(Comparator.comparingLong(Entry::lastSeenTick));
            for (Entry entry : kept.subList(0, kept.size() - MAX_KEPT_WHILE_WORKING)) gone.add(drop(entry));
        }
        return gone;
    }

    // 从登记册里去掉一个编号，连同按实体、结构、位置找它的索引；返回它最后一次看到的方位与位置。
    private Gone drop(Entry entry) {
        entries.remove(entry.id());
        if (entry.kind() == Kind.ENTITY) {
            entityIds.values().removeIf(id -> id.equals(entry.id()));
        } else if (entry.kind() == Kind.STRUCTURE) {
            structureIds.values().removeIf(id -> id.equals(entry.id()));
        } else {
            placeIds.values().removeIf(id -> id.equals(entry.id()));
        }
        return new Gone(entry.id(), entry.kind(), entry.direction(), entry.position());
    }

    /**
     * 目标推进中一个到期的编号对应的东西还在不在这个世界里：实体还在、没被移除；设施那一格加载着时
     * 还是登记时那种方块（说不出种类的只看空没空），没加载就照留；地形特征照留；物理结构会开走、
     * 又读不出还在不在，照常失效。
     */
    public boolean stillThere(Level level, Entry entry) {
        if (level == null) return false;
        return switch (entry.kind()) {
            case ENTITY -> entityIn(level, entry.id()).isPresent();
            case FEATURE -> true;
            case STRUCTURE -> false;
            case FACILITY -> {
                BlockPos at = new BlockPos(entry.position().x(), entry.position().y(), entry.position().z());
                if (!level.isLoaded(at)) yield true;
                var state = level.getBlockState(at);
                yield entry.blockType() == null ? !state.isAir()
                        : entry.blockType().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }
        };
    }

    private String place(WorldPosition position, String blockType, String direction, long nowTick, Kind kind,
            Supplier<String> newId) {
        // 同一个位置的同种东西是同一样：沿用编号，方位按这次看到的刷新。
        String existing = placeIds.get(placeKey(position));
        if (existing != null && entries.containsKey(existing) && entries.get(existing).kind() == kind) {
            refresh(existing, position, direction, nowTick, blockType);
            return existing;
        }
        String id = newId.get();
        placeIds.put(placeKey(position), id);
        put(new Entry(id, kind, position, direction, nowTick, PLACE_RETENTION_TICKS, blockType));
        return id;
    }

    private void put(Entry entry) {
        entries.put(entry.id(), entry);
    }

    // 再看见时刷新方位与时刻；这次说得出方块种类就按这次的记，说不出沿用登记时的。
    private void refresh(String id, WorldPosition position, String direction, long nowTick, String blockType) {
        Entry old = entries.get(id);
        entries.put(id, new Entry(id, old.kind(), position, direction, nowTick, old.retentionTicks(),
                blockType != null ? blockType : old.blockType()));
    }

    private static long placeKey(WorldPosition position) {
        // 编号只在本会话内有效，跨维度的同坐标不会同时在场，键里不需要维度。
        return position.x() & 0x3FFFFFFL | (position.z() & 0x3FFFFFFL) << 26 | (position.y() & 0xFFL) << 52;
    }

    /** 登记项：编号、种类、最后位置、最后方位、最后看见的时刻与保留时长；设施另记是哪种方块（说不出为 null）。 */
    public record Entry(String id, Kind kind, WorldPosition position, String direction,
            long lastSeenTick, int retentionTicks, String blockType) {}
}
