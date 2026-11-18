// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 看见罕见目标的判断与去重（事件 {@code rare_sighting}）：角色在眼前视野里亲眼看见 Boss 级强敌或露在外面的天然稀有矿脉时，
 * 发一条事件并把稀有矿脉顺路记进世界记忆。
 *
 * <p>只认眼前亲眼看见的：背对着的不发，隔着墙的不发，黑洞远处看不清的不发，玩家放下的装饰矿石不发。
 * 同一只 Boss 还在周围期间只报第一次，彻底离开很久后再撞见才重报；相连的同种矿石合为一条矿脉只报一次，
 * 同一片地方已报过或世界记忆里早记过同种矿脉的也不再大惊小怪。纯判断，现场读数由读端喂入。
 */
public final class RareSightings {

    /** 视野内认出 Boss 的最远距离（格）：末影龙、凋灵体型大且悬空，抬头六十余格认得出。C 玩家常识，来源：Boss 遭遇视距。 */
    public static final double BOSS_MAX_RANGE = 64.0;
    /** 视野内认清矿石斑点的最远距离（格）：再远矿点与岩壁混在一起看不真切。C 玩家常识，来源：下矿肉眼辨识距离。 */
    public static final double ORE_MAX_RANGE = 16.0;
    /** 同一片稀有矿脉的去重半径（格）：相隔几格露头的同种矿脉转头再看不再重报。C 玩家常识，来源：矿脉分布尺度。 */
    public static final double ORE_CLUSTER_RADIUS = 8.0;
    /** 同一只 Boss 不在周围超过这么久（5 分钟）才忘掉已目击记录，避免拉扯缠斗时反复惊呼。C 玩家常识，来源：Boss 战脱战时长。 */
    static final long BOSS_FORGET_TICKS = 20L * 300L;

    /** 原版未统一挂进通用标签、但遭遇压迫感与直播节目效果同属 Boss 级的强敌类型。 */
    private static final Set<String> VANILLA_BOSS_TYPES = Set.of(
            "minecraft:ender_dragon",
            "minecraft:wither",
            "minecraft:warden",
            "minecraft:elder_guardian"
    );

    private final TaskEventSink events;
    private final RemembersSightings memory;
    private final BiPredicate<WorldPosition, String> siteAlreadyRemembered;
    /** 已目击过的 Boss 实体 UUID → 最近一次仍在周围渲染列表里的游戏刻。 */
    private final Map<String, Long> reportedBosses = new HashMap<>();
    /** 本世界已目击过的稀有矿脉锚点。 */
    private final List<SpottedOre> reportedOres = new ArrayList<>();

    public RareSightings(TaskEventSink events, RemembersSightings memory,
                         BiPredicate<WorldPosition, String> siteAlreadyRemembered) {
        this.events = Objects.requireNonNull(events, "events");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.siteAlreadyRemembered = Objects.requireNonNull(siteAlreadyRemembered, "siteAlreadyRemembered");
    }

    /** 角色此刻的位置与朝向基准。 */
    public record Viewer(double x, double y, double z, float facingYaw, String dimension) {
        public Viewer {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    /** 周围在场的一只活着的 Boss 候选：带着此刻是不是在角色眼前无遮挡可见。 */
    public record BossCandidate(String uuid, String typeId, String name, String seenId, WorldPosition at,
                                boolean visibleInFront) {
        public BossCandidate {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(typeId, "typeId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(at, "at");
        }
    }

    /** 眼前视野里看得清、且确认不是玩家放置的一格稀有矿石。 */
    public record VisibleOre(BlockPos pos, String blockId, String name) {
        public VisibleOre {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(name, "name");
        }
    }

    private record SpottedOre(WorldPosition at, String blockId) {}

    /** 判断一种实体类型是否按 Boss 级强敌对待（带 {@code #c:bosses} 标签，或是原版四大强敌）。 */
    public static boolean isBossType(String typeId, boolean taggedAsBoss) {
        return taggedAsBoss || (typeId != null && VANILLA_BOSS_TYPES.contains(typeId));
    }

    /** 每刻核对周围活着的 Boss：已在记忆里的刷新在场时刻；新进入眼前视野的发一条事件。 */
    public void observeBosses(Viewer viewer, long gameTick, List<BossCandidate> candidates) {
        if (viewer == null || candidates == null) {
            return;
        }
        pruneAbsentBosses(gameTick, candidates);
        for (BossCandidate candidate : candidates) {
            if (reportedBosses.containsKey(candidate.uuid())) {
                reportedBosses.put(candidate.uuid(), gameTick);
                continue;
            }
            if (!candidate.visibleInFront()) {
                continue;
            }
            reportedBosses.put(candidate.uuid(), gameTick);
            String line = describeBoss(viewer, candidate);
            events.publish(TaskEvent.Kind.RARE_SIGHTING, line, new RareSighting(
                    RareSighting.CATEGORY_BOSS,
                    candidate.typeId(),
                    candidate.name(),
                    candidate.seenId(),
                    1,
                    candidate.at()));
        }
    }

    /** 按间隔核对眼前看见的天然稀有矿石：相连同种矿石聚成一条矿脉，未报过且世界记忆未记过的发事件并记产地。 */
    public void observeOres(Viewer viewer, Instant when, List<VisibleOre> visibleOres) {
        if (viewer == null || visibleOres == null || visibleOres.isEmpty()) {
            return;
        }
        Map<BlockPos, String> byPos = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (VisibleOre ore : visibleOres) {
            byPos.put(ore.pos(), ore.blockId());
            names.putIfAbsent(ore.blockId(), ore.name());
        }
        BlockPos feet = BlockPos.containing(viewer.x(), viewer.y(), viewer.z());
        for (OreVeins.Vein vein : OreVeins.group(byPos, feet)) {
            WorldPosition spot = new WorldPosition(
                    vein.nearest().getX(), vein.nearest().getY(), vein.nearest().getZ(), viewer.dimension());
            if (alreadyKnownOre(spot, vein.blockId())) {
                rememberReportedOre(spot, vein.blockId());
                continue;
            }
            rememberReportedOre(spot, vein.blockId());
            memory.siteSeen(spot, List.of(vein.blockId()), when);
            String name = names.getOrDefault(vein.blockId(), vein.blockId());
            String line = describeOre(viewer, name, vein.blocks(), spot);
            events.publish(TaskEvent.Kind.RARE_SIGHTING, line, new RareSighting(
                    RareSighting.CATEGORY_RARE_ORE,
                    vein.blockId(),
                    name,
                    null,
                    vein.blocks(),
                    spot));
        }
    }

    private void pruneAbsentBosses(long gameTick, List<BossCandidate> present) {
        for (BossCandidate candidate : present) {
            if (reportedBosses.containsKey(candidate.uuid())) {
                reportedBosses.put(candidate.uuid(), gameTick);
            }
        }
        reportedBosses.entrySet().removeIf(entry -> gameTick - entry.getValue() > BOSS_FORGET_TICKS);
    }

    private boolean alreadyKnownOre(WorldPosition spot, String blockId) {
        if (nearReportedOre(spot, blockId)) {
            return true;
        }
        return siteAlreadyRemembered.test(spot, blockId);
    }

    private boolean nearReportedOre(WorldPosition spot, String blockId) {
        double maxDistSqr = ORE_CLUSTER_RADIUS * ORE_CLUSTER_RADIUS;
        for (SpottedOre prev : reportedOres) {
            if (!prev.blockId().equals(blockId) || !Objects.equals(prev.at().dimension(), spot.dimension())) {
                continue;
            }
            double dx = prev.at().x() - spot.x();
            double dy = prev.at().y() - spot.y();
            double dz = prev.at().z() - spot.z();
            if (dx * dx + dy * dy + dz * dz <= maxDistSqr) {
                return true;
            }
        }
        return false;
    }

    private void rememberReportedOre(WorldPosition spot, String blockId) {
        if (!nearReportedOre(spot, blockId)) {
            reportedOres.add(new SpottedOre(spot, blockId));
        }
    }

    private static String describeBoss(Viewer viewer, BossCandidate boss) {
        DirectionWords.Bearing bearing = DirectionWords.Bearing.byBlock(
                viewer.x(), viewer.y(), viewer.z(), viewer.facingYaw(),
                boss.at().x(), boss.at().y(), boss.at().z());
        String idTag = boss.seenId() != null && !boss.seenId().isBlank() ? "（" + boss.seenId() + "）" : "";
        return "眼前看见了" + boss.name() + idTag + "（" + whereText(bearing) + "）";
    }

    private static String describeOre(Viewer viewer, String name, int blocks, WorldPosition spot) {
        DirectionWords.Bearing bearing = DirectionWords.Bearing.byBlock(
                viewer.x(), viewer.y(), viewer.z(), viewer.facingYaw(),
                spot.x(), spot.y(), spot.z());
        String countText = blocks > 1 ? " " + blocks + " 块" : "";
        return "眼前看见了" + name + countText + "（" + whereText(bearing) + "）";
    }

    private static String whereText(DirectionWords.Bearing bearing) {
        int dist = bearing.distance();
        return dist <= 2 ? "脚边" : bearing.direction() + " " + dist + " 格";
    }
}
