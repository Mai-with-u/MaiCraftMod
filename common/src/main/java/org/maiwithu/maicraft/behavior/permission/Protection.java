// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 保护判断：全仓唯一回答"这一格方块、这只生物能不能碰"的地方。
 *
 * <p>受保护的范围是几路来源的并集：记住的区域（圈起来的地盘）、这次任务额外保护的地标，
 * 以及有名字、被驯服、拴着绳、围栏里的生物。任何一路说受保护就是受保护。
 *
 * <p>归属（谁放的方块）不在保护判断里：服务端的方块归属记录只做事实——回报里写明是谁放的、
 * 最后由谁放下，动与不动由 LLM 拿着事实自己掂量。记录另有两处事实判断还在用它：
 * 自己放的临时方块要能收回（{@link #selfPlaced}），睡过的床要确认不是别人的才算家（{@link #homeOf}）。
 *
 * <p>这里只判断不动手：读的是只读接缝，给的是结论，纯函数可测。
 */
public final class Protection {

    /** 地标保护的半径：地标是一个点，点周围的这片都算它的地盘。 */
    static final double LANDMARK_RADIUS = 8.0;
    /** 记住的"家"叫这个名字。 */
    static final String HOME = "家";
    /** "家"的范围：离记住的家这么近就算家里。玩家常识。 */
    public static final double HOME_RADIUS_BLOCKS = 8.0;
    /** 村庄的范围：一格周围这么近看得见村民，就当它在村里（村民夜里睡在床上、白天在村里转）。玩家常识。 */
    public static final double VILLAGE_RADIUS_BLOCKS = 32.0;
    /** 自己盖过的一栋往外扩几格也算家里：贴墙的台阶、屋檐下的箱子、门口那一格。 */
    public static final int OWN_BUILDING_MARGIN = 2;

    private final ReadsBlockOwnership ownership;
    private final RemembersRegions regions;
    private final ReadsRememberedPlaces places;
    private final String selfPlayerId;
    private final SeesVillagers villagers;
    private final ReadsOwnHome home;
    private final Function<String, String> names;

    /**
     * @param ownership    方块归属的只读接缝（服务端记录），做事实判断用
     * @param regions      记住区域的只读接缝（世界记忆）
     * @param places       按名字读记过地点的只读接缝，用来定位额外保护的地标
     * @param selfPlayerId 角色自己的玩家编号；自己放的临时方块要能收回，睡过的床要认是不是别人的
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions,
            ReadsRememberedPlaces places, String selfPlayerId) {
        this(ownership, regions, places, selfPlayerId, SeesVillagers.NOBODY, ReadsOwnHome.NOWHERE, id -> id);
    }

    /**
     * @param villagers 村民读取：认村庄里的床（收回睡过的床时让开）；没接上时传 {@link SeesVillagers#NOBODY}
     * @param home      圈出角色自己的家要读的床与盖过的房子；没接上时传 {@link ReadsOwnHome#NOWHERE}
     * @param names     玩家编号换成名字（在线写名字，不在线写"一位不在线的玩家"）：回报里说是谁放的
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions, ReadsRememberedPlaces places,
            String selfPlayerId, SeesVillagers villagers, ReadsOwnHome home, Function<String, String> names) {
        this.ownership = ownership;
        this.regions = regions;
        this.places = places;
        this.selfPlayerId = selfPlayerId;
        this.villagers = Objects.requireNonNull(villagers, "villagers");
        this.home = Objects.requireNonNull(home, "home");
        this.names = Objects.requireNonNull(names, "names");
    }

    /**
     * 一格方块受不受保护。
     *
     * <p>判断顺序：先看记住的区域，再看额外保护的地标。归属（谁放的）不参与。
     *
     * @param position           方块位置
     * @param blockType          方块类型，例如 minecraft:chest；只为兼容调用方，判断不用它
     * @param protectedLandmarks 这次任务额外保护的地标名
     */
    public boolean blockProtected(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        return protectedBy(position, blockType, protectedLandmarks).protects();
    }

    /** 同 {@link #blockProtected}，多给出是哪一路挡的：拒绝时照它说清是区域还是地标。 */
    public ProtectedBy protectedBy(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) {
                return ProtectedBy.inside(ProtectedBy.Route.IN_REMEMBERED_REGION, region.name());
            }
        }
        Optional<String> landmark = protectedLandmarkHit(position, protectedLandmarks);
        if (landmark.isPresent()) return ProtectedBy.inside(ProtectedBy.Route.NEAR_PROTECTED_LANDMARK, landmark.get());
        return ProtectedBy.NONE;
    }

    /**
     * 一格方块受不受保护，寻路在后台线程上算路时问这份：读不了当刻现场，保护判断本身也不读现场，
     * 与 {@link #blockProtected} 是同一份结论。
     *
     * @param position 方块位置
     */
    public boolean blockProtectedAsSeen(WorldPosition position, Set<String> protectedLandmarks) {
        return protectedBy(position, null, protectedLandmarks).protects();
    }

    /**
     * 这一格在不在角色自己的家里，在的话说成一小句"在你家（记住的家旁）"这类：记住的"家"、
     * 真睡过的那张床周围 {@link #HOME_RADIUS_BLOCKS} 格（床的归属要已经问到、不是别人放的，借住的不算），
     * 或她亲手放下过的格子外扩 {@link #OWN_BUILDING_MARGIN} 格以内。维度说不清或对不上的一律不算；都不沾给空。
     */
    public Optional<String> homeOf(WorldPosition position) {
        // 维度说不清的不认家：下界里和主世界的家坐标重叠的格子不能算家。
        if (position.dimension() == null) return Optional.empty();
        Optional<WorldPosition> remembered = places.place(HOME);
        if (remembered.isPresent() && sameKnownDimension(remembered.get(), position)
                && distance(remembered.get(), position) <= HOME_RADIUS_BLOCKS) {
            return Optional.of("在你家（记住的家旁）");
        }
        Optional<WorldPosition> bed = home.sleptBed();
        if (bed.isPresent() && sameKnownDimension(bed.get(), position) && distance(bed.get(), position) <= HOME_RADIUS_BLOCKS
                && bedIsHers(bed.get())) {
            return Optional.of("在你家（你睡的床旁）");
        }
        for (ReadsOwnHome.Building building : home.ownBuildings()) {
            if (building.around(position, OWN_BUILDING_MARGIN)) return Optional.of("在你盖的「" + building.name() + "」里");
        }
        return Optional.empty();
    }

    /**
     * 动别人放在角色家里的一格时，回报里补的半句"是 玩家甲 放的，在你家（你睡的床旁）"；
     * 不是别人放的（自己放的、没有记录的）、或不在家里给空串。
     */
    public String othersAtHomeNote(WorldPosition position) {
        Optional<String> owner = placedByOther(position);
        if (owner.isEmpty()) return "";
        return homeOf(position).map(where -> "是 " + nameOf(owner.get()) + " 放的，" + where).orElse("");
    }

    // 床那一格的归属要已经问到、而且不是别人放的：所在区块还没问过服务端时拿不准，不当家。
    private boolean bedIsHers(WorldPosition bed) {
        return ownership.known(bed.dimension(), bed.x(), bed.y(), bed.z()) && placedByOther(bed).isEmpty();
    }

    /** 玩家编号换成名字：在线写名字，不在线写"一位不在线的玩家"（回报会被直播念出来，不吐编号）。 */
    public String nameOf(String playerId) {
        return names.apply(playerId);
    }

    /** 是哪一路挡的，说成一小句；不受保护给空串。 */
    public String describe(ProtectedBy reason) {
        return reason.describe();
    }

    /**
     * 一只箱子、一台终端这类存储，能不能拿来取用、存放（不是拆）。
     *
     * <p>归属（谁放的）不参与：谁放的都能取用、存放。只有这次任务额外保护的地标旁、
     * 记住的区域里的不能用。
     *
     * @param position           存储方块的位置
     * @param blockType          方块类型，例如 minecraft:chest；只为兼容调用方，判断不用它
     * @param protectedLandmarks 这次任务额外保护的地标名
     */
    public boolean mayUseStorage(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        return storageUse(position, blockType, protectedLandmarks).usable();
    }

    /**
     * 同 {@link #mayUseStorage}，多给出不能用时是哪一路挡的：跳过存储时说明里照它说清是地标还是区域。
     * 归属不在其中：谁放的存储都能用，是谁放的这个事实由回报另说。
     */
    public StorageUse storageUse(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        Optional<String> landmark = protectedLandmarkHit(position, protectedLandmarks);
        if (landmark.isPresent()) return StorageUse.inside(StorageUse.Kind.NEAR_PROTECTED_LANDMARK, landmark.get());
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return StorageUse.inside(StorageUse.Kind.IN_REMEMBERED_REGION, region.name());
        }
        return StorageUse.USABLE;
    }

    /**
     * 角色被封死在小坑里、按任务许可已经挖不出去时，这一格能不能挖开出去：不是功能方块、
     * 不在记住的区域里、不在记住的地点（地标，含"家"）周围 {@link #LANDMARK_RADIUS} 格内，就能挖。
     * 谁放的不再参与：别人放的普通方块照挖，挖开后在结果里写明是谁放的（{@link #placedByOther}）。
     *
     * @param functional 这一格是不是功能方块（有方块实体、门、会发红石信号、铁轨），调用方按读到的方块认
     */
    public boolean breakableWhenTrapped(WorldPosition position, boolean functional) {
        if (functional) return false;
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return false;
        }
        // 记住的地点周围不碰：家门口被围也不挖，所有者 2026-10-27 定的。
        for (WorldPosition spot : places.places().values()) {
            if (sameDimension(spot, position) && distance(spot, position) <= LANDMARK_RADIUS) return false;
        }
        return true;
    }

    /**
     * 这一格是不是建筑档案里某一栋图上的格：是就给那一栋的名字。缺材料自动挑取材点时跳过，不拆房子当材料；
     * 任务点名那一格（gather 给观察编号或坐标）不问这里。维度说不清时哪个维度的房子上有这一格都算：宁可不挖。
     */
    public Optional<String> archivedBuildingAt(WorldPosition position) {
        return home.archivedBuildingAt(position);
    }

    /**
     * 被困在自己家里、附近没有能开的门时，这一格墙能不能水平拆开出去：在家里、不是功能方块、不在记住的区域里。
     * 谁放的墙都能拆（拆完会补回去，回报写明是谁放的）；这次任务另外保护的格由寻路自己的保护格挡，
     * 床、门、家具与它们底下的格由寻路那一侧另外挡。
     */
    public boolean breakableAtHome(WorldPosition position, boolean functional) {
        if (functional || homeOf(position).isEmpty()) return false;
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return false;
        }
        return true;
    }

    /** 一只生物伤不伤得：有名字、被驯服（角色自己驯服的也算）、拴着绳、围栏里，占上一条就是有主的，不伤。 */
    public boolean creatureProtected(ReadsCreatureSituation.CreatureSituation situation) {
        return situation.bondedToSomeone();
    }

    /**
     * 对一只生物用东西、喂、骑、抱、开它的界面、存取它身上的东西时，它算不算别人的：
     * 角色自己驯服的不算；自家人驯服的照旧算别人的。
     */
    public boolean creatureSomeoneElses(ReadsCreatureSituation.CreatureSituation situation) {
        return situation.someoneElses();
    }

    /** 这一格是不是角色自己放的（临时方块）：只有这个判断，TEMPORARY 档也放行收回。 */
    public boolean selfPlaced(WorldPosition position) {
        return ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z())
                .map(placedBy -> placedBy.playerId().equals(selfPlayerId))
                .orElse(false);
    }

    /**
     * 归属记录里写着这一格是别人放的：睡过的床收回前用它挡住确实是别人的床（角色睡过一张村民的床，
     * 不等于床就是角色的）。记录没问到、或问到了又没写着谁放的，都不算——那两种由收回来源按"睡过的床"的规矩办。
     */
    public boolean placedByAnotherPlayer(WorldPosition position) {
        return placedByOther(position).isPresent();
    }

    /**
     * 归属记录写着这一格是别人放的：给放它的人的编号。
     * 记录没问到、没写着谁放的、是角色自己放的都给空。回报里说"这是谁放的"用它。
     */
    public Optional<String> placedByOther(WorldPosition position) {
        return ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z())
                .map(ReadsBlockOwnership.PlacedBy::playerId)
                .filter(playerId -> !playerId.equals(selfPlayerId));
    }

    /**
     * 这一格的归属问到了没有：没问到时记下要问，过一两刻再来看。写"是谁放的"的事实前先等它问到，
     * 问不到就不写。
     */
    public boolean ownershipKnown(WorldPosition position) {
        return ownership.known(position.dimension(), position.x(), position.y(), position.z());
    }

    /**
     * 角色自己睡过的床受不受保护（收回它的时候问）：亲手睡过是"这是自己用的"的直接证据。
     * 记住的区域、额外保护的地标照旧算受保护；归属（谁放的）不参与。
     * 村庄里的床不在这里挡，收回来源直接让开（{@link #inVillage}）：那是村民的，不该走"点名许可"这条路。
     */
    public ProtectedBy sleptBedProtectedBy(WorldPosition position, Set<String> protectedLandmarks) {
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return ProtectedBy.inside(ProtectedBy.Route.IN_REMEMBERED_REGION, region.name());
        }
        Optional<String> landmark = protectedLandmarkHit(position, protectedLandmarks);
        if (landmark.isPresent()) return ProtectedBy.inside(ProtectedBy.Route.NEAR_PROTECTED_LANDMARK, landmark.get());
        return ProtectedBy.NONE;
    }

    /**
     * 这一格是不是在村庄里：周围 {@link #VILLAGE_RADIUS_BLOCKS} 格内看得见村民就算。
     * 村庄的床是村民睡的，角色借住过一晚也不是自己的，收回睡过的床时让开（2026-10-25 拍板）。
     */
    public boolean inVillage(WorldPosition position) {
        return villagers.villagersNear(position, VILLAGE_RADIUS_BLOCKS);
    }

    /**
     * 这一格是不是在记住的"家"附近：家记过、离它不到 {@link #HOME_RADIUS_BLOCKS} 格就算。
     * 家里的床是重生点，家里的台子、炉子是摆好用的，收回自己放下的东西时不拆这些。
     */
    public boolean insideHome(WorldPosition position) {
        Optional<WorldPosition> home = places.place(HOME);
        if (home.isEmpty()) return false;
        double dx = home.get().x() - position.x();
        double dy = home.get().y() - position.y();
        double dz = home.get().z() - position.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= HOME_RADIUS_BLOCKS;
    }

    /** 按名字找地标的位置；没记过就给空，调用方先去找到它再回来检查。 */
    public Optional<WorldPosition> landmarkPosition(String name) {
        return places.place(name);
    }

    /** 目标位置落不落在任何一个额外保护地标的地盘里。 */
    public Optional<String> protectedLandmarkHit(WorldPosition position, Set<String> protectedLandmarks) {
        for (String name : protectedLandmarks) {
            Optional<WorldPosition> spot = places.place(name);
            if (spot.isEmpty()) continue;
            double dx = spot.get().x() - position.x();
            double dy = spot.get().y() - position.y();
            double dz = spot.get().z() - position.z();
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= LANDMARK_RADIUS) return Optional.of(name);
        }
        return Optional.empty();
    }

    /**
     * 一片范围（以 center 为心、radius 格为半径的球）碰不碰得到记住的区域或这次任务额外保护的地标的地盘：
     * 碰得到就说是哪一块，例如"记住的区域「河东的农场」"。
     *
     * <p>末影水晶这类一炸一大片的事用它：只看这片范围和记住的地盘有没有交叠，不逐格问。
     * 维度不同的不算交叠（一方没写维度就当相配，宁可多算进保护）。
     */
    public Optional<String> protectedAreaNear(WorldPosition center, double radius, Set<String> protectedLandmarks) {
        for (RememberedRegion region : regions.regions()) {
            if (sameDimension(region.center(), center)
                    && distance(region.center(), center) <= region.radiusBlocks() + radius) {
                return Optional.of("记住的区域「" + region.name() + "」");
            }
        }
        for (String name : protectedLandmarks) {
            Optional<WorldPosition> spot = places.place(name);
            if (spot.isPresent() && sameDimension(spot.get(), center)
                    && distance(spot.get(), center) <= LANDMARK_RADIUS + radius) {
                return Optional.of("额外保护的地标「" + name + "」");
            }
        }
        return Optional.empty();
    }

    // 两个位置在不在同一个维度：一方没写维度（角色当前所在维度）就当相配。
    // 认家用的维度比对：两边都写了、而且是同一个才算；一边没写就不算，宁可少认一处家。
    private static boolean sameKnownDimension(WorldPosition a, WorldPosition b) {
        return a.dimension() != null && a.dimension().equals(b.dimension());
    }

    private static boolean sameDimension(WorldPosition a, WorldPosition b) {
        return a.dimension() == null || b.dimension() == null || a.dimension().equals(b.dimension());
    }

    private static double distance(WorldPosition a, WorldPosition b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
