// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 保护判断：全仓唯一回答"这一格方块、这只生物能不能碰"的地方。
 *
 * <p>受保护的范围是几路来源的并集：服务端的方块归属记录、记住的区域、这次任务额外保护的地标、
 * 推断为玩家放置的方块，以及有名字、被驯服、拴着绳、围栏里的生物。任何一路说受保护就是受保护；
 * 哪一路都说不清时，按受保护处理——少动一格，好过拆了别人盖的。
 *
 * <p>这里只判断不动手：读的是只读接缝，给的是结论，纯函数可测。
 * 角色自己放的方块不算受保护（临时方块用完要收回），判据是归属记录里的编号就是角色自己。
 *
 * <p>存储另有一问（{@link #mayUseStorage}）：箱子、AE 网络这类拿来取用、存放的东西，
 * 自家人（所有者在实例配置里列的信任玩家）放的也能用；拆、改仍按 {@link #blockProtected} 当别人的。
 *
 * <p>角色自己的家里（记住的"家"、最近睡过的自己那张床周围 {@link #HOME_RADIUS_BLOCKS} 格，以及她按图盖过的每一栋
 * 外扩 {@link #OWN_BUILDING_MARGIN} 格）放开一层：别人放的方块和存储，她亲手去用、去拿、按图去改都行
 * （{@link #protectedByUnlessHome}、{@link #storageUse}），回报里写明是谁放的。记住的区域与这次任务额外保护的地标照旧挡；
 * 寻路顺手挖、自动挑取材点这类不是亲手点的，仍按 {@link #blockProtected} 当别人的。主播在游戏里说过"自己家可以随便改"。
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
    private final GuessesPlayerMade guesses;
    private final String selfPlayerId;
    private final TrustedPlayers trusted;
    private final SeesVillagers villagers;
    private final ReadsOwnHome home;
    private final Function<String, String> names;

    /**
     * @param ownership     方块归属的只读接缝（服务端记录）
     * @param regions       记住区域的只读接缝（世界记忆）
     * @param places        按名字读记过地点的只读接缝，用来定位额外保护的地标
     * @param guesses       玩家放置推断；还没接上时传 {@link GuessesPlayerMade#NOTHING}
     * @param selfPlayerId  角色自己的玩家编号；自己放的临时方块要能收回
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions,
            ReadsRememberedPlaces places, GuessesPlayerMade guesses, String selfPlayerId) {
        this(ownership, regions, places, guesses, selfPlayerId, TrustedPlayers.NOBODY);
    }

    /**
     * @param trusted 自家人：他们放的存储可以取用、存放；没配置时传 {@link TrustedPlayers#NOBODY}
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions,
            ReadsRememberedPlaces places, GuessesPlayerMade guesses, String selfPlayerId, TrustedPlayers trusted) {
        this(ownership, regions, places, guesses, selfPlayerId, trusted, SeesVillagers.NOBODY);
    }

    /**
     * @param villagers 村民读取：认村庄里的床（收回睡过的床时让开）；没接上时传 {@link SeesVillagers#NOBODY}
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions, ReadsRememberedPlaces places,
            GuessesPlayerMade guesses, String selfPlayerId, TrustedPlayers trusted, SeesVillagers villagers) {
        this(ownership, regions, places, guesses, selfPlayerId, trusted, villagers, ReadsOwnHome.NOWHERE, id -> id);
    }

    /**
     * @param home  圈出角色自己的家要读的床与盖过的房子；没接上时传 {@link ReadsOwnHome#NOWHERE}
     * @param names 玩家编号换成名字（在线写名字，不在线写编号）：拒绝理由与回报里说是谁放的
     */
    public Protection(ReadsBlockOwnership ownership, RemembersRegions regions, ReadsRememberedPlaces places,
            GuessesPlayerMade guesses, String selfPlayerId, TrustedPlayers trusted, SeesVillagers villagers,
            ReadsOwnHome home, Function<String, String> names) {
        this.ownership = ownership;
        this.regions = regions;
        this.places = places;
        this.guesses = guesses;
        this.selfPlayerId = selfPlayerId;
        this.trusted = Objects.requireNonNull(trusted, "trusted");
        this.villagers = Objects.requireNonNull(villagers, "villagers");
        this.home = Objects.requireNonNull(home, "home");
        this.names = Objects.requireNonNull(names, "names");
    }

    /**
     * 一格方块受不受保护。
     *
     * <p>判断顺序：归属还拿不准（这一格所在的区块还没问过服务端）就按受保护处理；
     * 再看服务端归属记录（以记录为准，角色自己放的不算），再看记住的区域，
     * 再看额外保护的地标，最后靠玩家放置推断兜底。哪一路命中就不再往下问。
     *
     * @param position           方块位置
     * @param blockType          方块类型，例如 minecraft:chest；不知道传 null，推断这路就跳过
     * @param protectedLandmarks 这次任务额外保护的地标名
     */
    public boolean blockProtected(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        return protectedBy(position, blockType, protectedLandmarks).protects();
    }

    /** 同 {@link #blockProtected}，多给出是哪一路挡的：拒绝时照它说清是记录、区域、地标、推断还是归属没问到。 */
    public ProtectedBy protectedBy(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        return protectedBy(position, protectedLandmarks, () -> guesses.likelyPlayerMade(position, blockType));
    }

    /**
     * 角色亲手去用、去改这一格时挡不挡：家外和 {@link #protectedBy} 一样；在自己家里，别人放的、归属没问到的、
     * 看着像玩家放的都放开，只有记住的区域和这次任务额外保护的地标照旧挡。
     * 只给"点了这一格"的动作用（用东西、按图施工）；寻路顺手挖、自动挑取材点不走这里。
     */
    public ProtectedBy protectedByUnlessHome(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        ProtectedBy reason = protectedBy(position, blockType, protectedLandmarks);
        return liftedAtHome(reason.route()) && homeOf(position).isPresent() ? ProtectedBy.NONE : reason;
    }

    // 家里放开的是"不知道是不是别人的、或记录说是别人的"这几路；区域与地标是调用方或角色自己点名要护着的，不放开。
    private static boolean liftedAtHome(ProtectedBy.Route route) {
        return route == ProtectedBy.Route.PLACED_BY_OTHER || route == ProtectedBy.Route.OWNER_UNKNOWN
                || route == ProtectedBy.Route.LOOKS_PLAYER_MADE;
    }

    /**
     * 这一格在不在角色自己的家里，在的话说成一小句"在你家（记住的家旁）"这类：记住的"家"、
     * 最近睡过的那张床周围 {@link #HOME_RADIUS_BLOCKS} 格（床的归属记录写着是别人放的不算，那是借住），
     * 或她按图盖过的一栋外扩 {@link #OWN_BUILDING_MARGIN} 格以内。都不沾给空。
     */
    public Optional<String> homeOf(WorldPosition position) {
        if (insideHome(position)) return Optional.of("在你家（记住的家旁）");
        Optional<WorldPosition> bed = home.sleptBed();
        if (bed.isPresent() && sameDimension(bed.get(), position) && distance(bed.get(), position) <= HOME_RADIUS_BLOCKS
                && placedByOther(bed.get()).isEmpty()) {
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

    /** 玩家编号换成名字：在线写名字，不在线写编号。 */
    public String nameOf(String playerId) {
        return names.apply(playerId);
    }

    /** 是哪一路挡的，说成一小句（玩家写名字）；不受保护给空串。 */
    public String describe(ProtectedBy reason) {
        return reason.describe(names);
    }

    /**
     * 一格方块受不受保护，推断那一路由调用方自己给：寻路在后台线程上算路，读不了当刻现场，
     * 只能拿它自己读到的那份方块来猜。归属记录、记住的区域、地标几路与 {@link #blockProtected} 一样，
     * 记录说是角色自己放的照样不保护。
     *
     * @param seenPlayerMade 调用方看到的这一格像不像玩家放的
     */
    public boolean blockProtectedAsSeen(WorldPosition position, Set<String> protectedLandmarks,
            boolean seenPlayerMade) {
        return protectedBy(position, protectedLandmarks, () -> seenPlayerMade).protects();
    }

    private ProtectedBy protectedBy(WorldPosition position, Set<String> protectedLandmarks,
            BooleanSupplier likelyPlayerMade) {
        // 拿不准就按受保护处理：归属还没问到，宁可少动一格。
        if (!ownership.known(position.dimension(), position.x(), position.y(), position.z())) {
            return ProtectedBy.only(ProtectedBy.Route.OWNER_UNKNOWN);
        }
        Optional<ReadsBlockOwnership.PlacedBy> placedBy =
                ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z());
        // 归属记录查得到就以它为准：别人放的受保护，自己放的临时方块不算。
        if (placedBy.isPresent()) {
            String owner = placedBy.get().playerId();
            return owner.equals(selfPlayerId) ? ProtectedBy.NONE : ProtectedBy.placedBy(owner);
        }
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) {
                return ProtectedBy.inside(ProtectedBy.Route.IN_REMEMBERED_REGION, region.name());
            }
        }
        Optional<String> landmark = protectedLandmarkHit(position, protectedLandmarks);
        if (landmark.isPresent()) return ProtectedBy.inside(ProtectedBy.Route.NEAR_PROTECTED_LANDMARK, landmark.get());
        // 记录与区域都说不清，才靠猜；猜像就当是。前几路都没命中才去猜，现场读数只在这时才读。
        return likelyPlayerMade.getAsBoolean() ? ProtectedBy.only(ProtectedBy.Route.LOOKS_PLAYER_MADE) : ProtectedBy.NONE;
    }

    /**
     * 一只箱子、一台终端这类存储，能不能拿来取用、存放（不是拆）。
     *
     * <p>这次任务额外保护的地标旁边的不用；归属还没问清的不用（拿不准就不用）。
     * 有归属记录的看是谁放的：角色自己或自家人放的能用，别人放的不用。
     * 没有记录的：在记住的区域里、或推断像是玩家放的，不知道是谁的，按别人的算；
     * 野外、村庄、遗迹里不属于任何玩家的能用。
     *
     * @param position           存储方块的位置
     * @param blockType          方块类型，例如 minecraft:chest；不知道传 null，推断这路就跳过
     * @param protectedLandmarks 这次任务额外保护的地标名
     */
    public boolean mayUseStorage(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        return storageUse(position, blockType, protectedLandmarks).usable();
    }

    /**
     * 同 {@link #mayUseStorage}，多给出不能用时是哪一路挡的、记在谁名下：跳过别人的箱子时说明里照它说清是谁的。
     * 判断顺序：额外保护的地标、归属没问到、归属记录（自己或自家人放的能用）、记住的区域、玩家放置推断。
     * 在角色自己的家里，记录写着别人放的、归属没问到的、看着像玩家放的都能用（{@link StorageUse.Kind#AT_HOME}），
     * 记录上有人的带上是谁放的，好在回报里写明；记住的区域与额外保护的地标照旧不用。
     */
    public StorageUse storageUse(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        StorageUse use = storageUseOutsideHome(position, blockType, protectedLandmarks);
        boolean liftable = use.kind() == StorageUse.Kind.PLACED_BY_OTHER || use.kind() == StorageUse.Kind.OWNER_UNKNOWN
                || use.kind() == StorageUse.Kind.LOOKS_PLAYER_MADE;
        if (!liftable) return use;
        // 家里的箱子是她过日子用的：主播在游戏里说过自己家可以随便改，别人放进来的也拿来用，回报写明是谁放的。
        return homeOf(position).map(where -> StorageUse.atHome(use.placedBy(), where)).orElse(use);
    }

    private StorageUse storageUseOutsideHome(WorldPosition position, String blockType, Set<String> protectedLandmarks) {
        Optional<String> landmark = protectedLandmarkHit(position, protectedLandmarks);
        if (landmark.isPresent()) return StorageUse.inside(StorageUse.Kind.NEAR_PROTECTED_LANDMARK, landmark.get());
        // 归属还没问到服务端：不知道是谁的，按别人的算，宁可少翻一只箱子。
        if (!ownership.known(position.dimension(), position.x(), position.y(), position.z())) {
            return new StorageUse(StorageUse.Kind.OWNER_UNKNOWN, null, null);
        }
        Optional<ReadsBlockOwnership.PlacedBy> placedBy =
                ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z());
        if (placedBy.isPresent()) {
            String owner = placedBy.get().playerId();
            return owner.equals(selfPlayerId) || trusted.includes(owner) ? StorageUse.USABLE : StorageUse.placedBy(owner);
        }
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return StorageUse.inside(StorageUse.Kind.IN_REMEMBERED_REGION, region.name());
        }
        return guesses.likelyPlayerMade(position, blockType)
                ? new StorageUse(StorageUse.Kind.LOOKS_PLAYER_MADE, null, null) : StorageUse.USABLE;
    }

    /**
     * 角色被封死在小坑里、按任务许可已经挖不出去时，这一格能不能挖开出去：是别人放的普通方块，就给放它的人的编号；
     * 不能挖给空。
     *
     * <p>只认服务端记录写着是别人放的（自家人也算别人，他们只放行取用存储）；功能方块不挖；
     * 记住的区域里、记住的地点（地标，含"家"）周围 {@link #LANDMARK_RADIUS} 格内的不挖。
     * 归属还没问到、没有记录的都不放开：拿不准就不动，看着天然的没记录方块本来在"挖天然方块"那一级就能挖。
     *
     * @param functional 这一格是不是功能方块（有方块实体、门、会发红石信号、铁轨），调用方按读到的方块认
     */
    public Optional<String> breakableWhenTrapped(WorldPosition position, boolean functional) {
        if (functional) return Optional.empty();
        if (!ownership.known(position.dimension(), position.x(), position.y(), position.z())) return Optional.empty();
        Optional<ReadsBlockOwnership.PlacedBy> placedBy =
                ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z());
        if (placedBy.isEmpty() || placedBy.get().playerId().equals(selfPlayerId)) return Optional.empty();
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return Optional.empty();
        }
        // 记住的地点周围不碰：家门口被围也不挖，所有者 2026-10-27 定的。
        for (WorldPosition spot : places.places().values()) {
            if (sameDimension(spot, position) && distance(spot, position) <= LANDMARK_RADIUS) return Optional.empty();
        }
        return Optional.of(placedBy.get().playerId());
    }

    /** 一只生物伤不伤得：有名字、被驯服（角色自己驯服的也算）、拴着绳、围栏里，占上一条就是有主的，不伤。 */
    public boolean creatureProtected(ReadsCreatureSituation.CreatureSituation situation) {
        return situation.bondedToSomeone();
    }

    /**
     * 对一只生物用东西、喂、骑、抱、开它的界面、存取它身上的东西时，它算不算别人的：
     * 角色自己驯服的不算；自家人驯服的照旧算别人的（自家人只放行取用存储）。
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
     * 归属记录写着这一格是别人放的：给放它的人的编号（自家人也算别人，他们只放行取用存储）。
     * 记录没问到、没写着谁放的、是角色自己放的都给空。卡坑没出去时说"那是谁放的"用它。
     */
    public Optional<String> placedByOther(WorldPosition position) {
        return ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z())
                .map(ReadsBlockOwnership.PlacedBy::playerId)
                .filter(playerId -> !playerId.equals(selfPlayerId));
    }

    /**
     * 这一格的归属问到了没有：没问到时记下要问，过一两刻再来看。收回自己放下的东西前先等它问到，
     * 拿不准是不是自己放的就不动。
     */
    public boolean ownershipKnown(WorldPosition position) {
        return ownership.known(position.dimension(), position.x(), position.y(), position.z());
    }

    /**
     * 角色自己睡过的床受不受保护（收回它的时候问）：亲手睡过是"这是自己用的"的直接证据，
     * 归属记录没问到、没写着谁放的，都不按受保护算——野外那张自己放下再睡的床，放下记录本就可能不在了。
     * 归属记录写着是别人放的、记住的区域、额外保护的地标，照旧算受保护；玩家放置推断也照常问一遍，
     * 和别的方块同一套规矩（2026-10-25 拍板，推断此刻只认门类结构件，对床还说不上话）。
     * 村庄里的床不在这里挡，收回来源直接让开（{@link #inVillage}）：那是村民的，不该走"点名许可"这条路。
     */
    public ProtectedBy sleptBedProtectedBy(WorldPosition position, Set<String> protectedLandmarks) {
        Optional<ReadsBlockOwnership.PlacedBy> placedBy =
                ownership.whoPlaced(position.dimension(), position.x(), position.y(), position.z());
        if (placedBy.isPresent()) {
            String owner = placedBy.get().playerId();
            return owner.equals(selfPlayerId) ? ProtectedBy.NONE : ProtectedBy.placedBy(owner);
        }
        for (RememberedRegion region : regions.regions()) {
            if (region.contains(position)) return ProtectedBy.inside(ProtectedBy.Route.IN_REMEMBERED_REGION, region.name());
        }
        Optional<String> landmark = protectedLandmarkHit(position, protectedLandmarks);
        if (landmark.isPresent()) return ProtectedBy.inside(ProtectedBy.Route.NEAR_PROTECTED_LANDMARK, landmark.get());
        // 记录、区域、地标都说不清：照别的方块一样靠推断兜底，像玩家放的就当是别人的。
        return guesses.likelyPlayerMade(position, null) ? ProtectedBy.only(ProtectedBy.Route.LOOKS_PLAYER_MADE)
                : ProtectedBy.NONE;
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
     * <p>末影水晶这类一炸一大片的事用它：只看这片范围和记住的地盘有没有交叠，不逐格问方块归属——
     * 归属没问到服务端的格一律按受保护，拿逐格的判断查一片没人碰过的末地石，会把整片都判成碰不得。
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
