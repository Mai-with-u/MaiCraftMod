// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.behavior.permission.FunctionalBlocks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.behavior.navigation.PainfulBlocks;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.construction.ShowsPreview;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.LightingWorld;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.navigation.baritone.BaritoneInternals;
import org.maiwithu.maicraft.behavior.permission.ClientCreatureSituations;
import org.maiwithu.maicraft.behavior.permission.ClientVillagers;
import org.maiwithu.maicraft.behavior.permission.LivePlayerMadeGuesses;
import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.behavior.navigation.baritone.NavigationObstacles;
import org.maiwithu.maicraft.behavior.navigation.baritone.NavigationProtection;
import org.maiwithu.maicraft.behavior.permission.OwnershipMap;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.behavior.permission.RegionsSnapshot;
import org.maiwithu.maicraft.behavior.perception.ClientEntitySight;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.perception.ClientEnvironmentSight;
import org.maiwithu.maicraft.behavior.perception.ClientNearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.ClientSelfSight;
import org.maiwithu.maicraft.behavior.perception.ClientSubtitleEar;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.OverheadGrid;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.interaction.NativeBlockBreaking;
import org.maiwithu.maicraft.behavior.travel.planning.ClientTravelWorldView;
import org.maiwithu.maicraft.behavior.travel.settle.TravelProgress;
import org.maiwithu.maicraft.behavior.travel.settle.TravelProgressListener;
import org.maiwithu.maicraft.behavior.acquire.trade.ClientTradeMenuReads;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemsInUse;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.behavior.acquire.engine.LiveCarryReads;
import org.maiwithu.maicraft.behavior.acquire.cook.RegistryToolRequirements;
import org.maiwithu.maicraft.behavior.menu.ClientOpenScreenReads;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.SubtitleFeed;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.InputDriver;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerControlBoundary;
import org.maiwithu.maicraft.game.serverlink.ServerLinkSession;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ReadsLightLevels;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.DocumentGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.GoalRunStore;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.behavior.inventory.StoredReservations;
import org.maiwithu.maicraft.game.world.OnlinePlayers;
import org.maiwithu.maicraft.game.world.SaveIdentity;
import org.maiwithu.maicraft.kernel.storage.StateIdentity;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.game.world.RegistryBlockTags;
import org.maiwithu.maicraft.mcp.view.MenuView;
import org.maiwithu.maicraft.mcp.view.SelfBrief;
import org.maiwithu.maicraft.ability.timer.Timers;

/**
 * 进世界时创建、退世界时丢弃的这一份现场：世界记忆、感知场景，并把能力按清单登记进客户端共用的注册表。
 *
 * <p>世界身份从存档目录或服务器地址识别，记忆按身份分开存；连到另一个世界时这份现场整体丢弃，
 * 不把上一个世界的东西带过来。每刻由启动侧把当刻的角色上下文交给 {@link #observe}，
 * 各路读端从这里读出观察事实，交给场景整理。
 */
public final class WorldScope {

    private final BlockScanService blockScans;
    private final SubtitleFeed subtitles;
    private final WorldMemory memory;
    private final PainfulBlocks pains;
    /** 这个世界的保护判断：能力挑方块、生存需求里要挖开的都问它，归属查询的在途请求只留一份。 */
    private final Protection protection;
    /** 自家人：存储能不能用、被打要不要停工都按这一份。 */
    private final TrustedPlayers trusted;
    /** 方块归属按区块问服务端记下；角色走到哪先把周围问好。 */
    private final OwnershipMap ownership;
    /** 记住的区域每刻抄一份：寻路在后台线程上也要读。 */
    private final RegionsSnapshot regions;
    /** 角色此刻所在的维度：寻路在后台线程上问保护时按它定位置。 */
    private volatile String dimension;
    /** 这个世界的目标运行存盘：退出游戏、换世界之后，没做完的目标从这里读回来。 */
    private final GoalRunStore goalRuns;
    /** 设施分类：容器与工作设施按这个世界同步过来的方块标签认。 */
    private final FacilityKinds kinds;
    private final Scene scene;
    /** 这一刻在看的玩家现场：设施观察的读端跨刻复用一个，每刻从这里读现场。 */
    private PlayerContext observing;
    /**
     * 设施观察的读端：只建一个。每刻新建会每刻重新遍历整张方块注册表找床（大型整合包里几万种方块），
     * 还会每刻重复登记同一批方块，扫描索引的引用计数只增不减。告示牌也由它读，牌子上的字按画面上显示的读：
     * 客户端开着文字过滤时读过滤后的那一份。
     */
    private final ClientNearbyBlocksSight facilitySight;
    /** 联动模组的观察（电梯、车站、列车、物理结构）：场景每次刷新时问。 */
    private final List<ModSight> modSights;
    /** 模组的东西在角色周围多大范围里看（格）。 */
    private static final int THINGS_RADIUS = 64;
    private final TravelProgressListener travelProgress = new LatestTravelProgress();
    /** 看界面要读的东西：开着的界面按这个世界认得出的界面（原版加联动模组的证明）读，交易界面再读报价。 */
    private final MenuView.Reads menus;

    /** 保留最近一刻出行进展的收件口：宿主查任务面板时读它，不攒历史。 */
    private static final class LatestTravelProgress implements TravelProgressListener {
        private TravelProgress latest;

        @Override public void onTravelProgress(TravelProgress progress) {
            this.latest = progress;
        }
    }

    // 全仓唯一的保护判断：归属记录、记住的区域与地标、自家人之外再接两路现场读数，都读当刻的角色上下文。
    // 玩家放置推断按方块本身认：门、活板门、栅栏门这类归属记录查不到的，也按玩家的东西对待；
    // 村庄按看得见村民认：收回睡过的床时村里的那张让开。读不到当刻的现场（还没进世界、人接管中）就不猜、按没看见算。
    private Protection assembleProtection(WorldMemory memory, PlayerControlBoundary playerControl, String selfPlayerId) {
        return new Protection(ownership, regions, memory,
                new LivePlayerMadeGuesses(position -> playerControl.activeContext()
                        .map(context -> context.level() == null ? null
                                : context.level().getBlockState(new BlockPos(position.x(), position.y(), position.z())))
                        .orElse(null)),
                selfPlayerId, trusted,
                new ClientVillagers(() -> playerControl.activeContext().map(context -> context.level()).orElse(null)));
    }

    public WorldScope(Minecraft minecraft, PlayerControlBoundary playerControl, BlockScanService blockScans,
            ServerLinkSession session, SubtitleFeed subtitles, Interactions interactions,
            UseKeyProjection useKeyProjection, WalkTo walksOnFoot, BaritoneInternals walks, CombatSenses senses,
            AbilityRegistry abilities, InteractionSender interactionSender, MenuActions menuActions,
            InstanceConfig instanceConfig, FurnaceFuels furnaceFuels, CompatRegistry compat,
            SurvivalWiring survivalWiring, Path configDirectory, ShowsPreview preview, ItemsInUse itemsInUse,
            ReservedItems reserved, DigToolRule digTools, DropConfirmations dropConfirmations, TaskEventSink events, ReadsLightLevels lightLevels,
            LightingWorld lightingWorld, FollowLighting followLighting, Timers timers) {
        // 游戏接口层认出是哪个存档或服务器，内核的世界身份只拿编号与目录。
        SaveIdentity save = SaveIdentity.current(minecraft)
                .orElseThrow(() -> new IllegalStateException("进了世界却识别不出世界身份，记忆无处安放"));
        StateIdentity identity = new StateIdentity(save.key(), save.directory(),
                save.directory().resolve(DocumentStore.FILE_NAME), "state");
        this.blockScans = Objects.requireNonNull(blockScans, "blockScans");
        this.subtitles = Objects.requireNonNull(subtitles, "subtitles");
        // 世界记忆与目标运行存在同一个文档库里，按世界身份分开；目标读回时按能力清单的参数规格整理参数。
        DocumentStore documents = new DocumentStore(identity.databaseFile());
        this.memory = new WorldMemory(documents, identity.key());
        // 吃过亏的方块：碰着就掉血的那几种记在所有世界共用的一份里，进世界读出来交给寻路绕开。
        this.pains = new PainfulBlocks(documents);
        this.goalRuns = new DocumentGoalRunStore(documents, identity.key(), abilities);
        // 预留跟着世界存：进这个世界读出上次写下的，之后每改一次写回同一个文档库。
        reserved.attach(new StoredReservations(documents, identity.key()));
        this.kinds = new FacilityKinds(new RegistryBlockTags());
        this.scene = new Scene(memory, kinds);
        this.facilitySight = new ClientNearbyBlocksSight(blockScans, () -> observing, kinds, minecraft::isTextFilteringEnabled);
        // 保护判断：归属记录按区块问服务端（还没问到的按受保护处理；自己刚放下的按服务端推来的确认当刻记上），
        // 区域读每刻抄一份的快照，地标问世界记忆；
        this.ownership = new OwnershipMap(session, minecraft.player.getUUID().toString());
        this.regions = new RegionsSnapshot(memory);
        this.regions.refresh();
        // 自家人：所有者在实例配置里列的信任玩家；名单写名字的，按在线玩家对上编号。
        this.trusted = new TrustedPlayers(instanceConfig.trustedPlayers(),
                name -> OnlinePlayers.idByName(minecraft, name));
        this.protection = assembleProtection(memory, playerControl, minecraft.player.getUUID().toString());
        this.dimension = minecraft.level.dimension().location().toString();
        // 寻路挖路、垫路也过这一份保护判断：不挖穿别人的房子，归属拿不准的格子不动。
        // 算路跑在后台线程上，碰不了当刻的角色上下文（玩家控制边界只许客户端线程进），所以门类推断不走
        // 上面那份读现场的推断，改用寻路自己读到的这格方块来认；归属记录、区域几路照旧。
        // 被封死在小坑里、按任务许可挖不出去时：别人放的普通方块能挖开出去，功能方块、记住的区域与地点旁边的照旧不碰。
        NavigationProtection.installWorldRule(new NavigationProtection.CellRule() {
            @Override public boolean protects(int x, int y, int z, BlockState state) {
                return protection.blockProtectedAsSeen(new WorldPosition(x, y, z, dimension), Set.of(),
                        state != null && LivePlayerMadeGuesses.looksPlacedByPlayer(state));
            }

            @Override public Optional<String> placedByOtherWhenTrapped(int x, int y, int z, BlockState state) {
                return protection.breakableWhenTrapped(new WorldPosition(x, y, z, dimension),
                        FunctionalBlocks.functional(state));
            }
        });
        // 会动的结构（联动登记的障碍来源）交给寻路：转着的风车、开着的列车、电梯井绕开走，挡路时先等它过去。
        NavigationObstacles.install(compat.obstacleSources());
        this.modSights = compat.modSights();
        // 当刻角色的供给者：感知、背包、聊天、看界面都从它拿这一刻的角色，不留到下一刻。
        Supplier<PlayerContext> now = () -> playerControl.activeContext().orElse(null);
        this.menus = new MenuView.Reads(new ClientOpenScreenReads(now, compat.menuLayouts()), new ClientTradeMenuReads(now));
        // 原生挖掘走生存需求共用的那套挖掘基础代码，每次要挖一格开一份。
        Supplier<NativeBlockBreaking> diggings = () -> new NativeBlockBreaking(interactionSender, menuActions, digTools);
        // 能力按清单登记进客户端全程共用的那一份注册表：MCP 的 lookup 与 execute 读的就是它。
        // 依赖按领域用途分组传进去：当刻角色读端、物品事实、世界与观察、动手、走到与出行、
        // 许可与保护、对外通道、存放位置，外加几样只属于一件事的接缝与实例配置。
        AbilityCatalog.create(new AbilityCatalog.Deps(
                new CharacterReads(now,
                        PlayerViews.backpack(now),
                        LiveCarryReads.offhand(now),
                        PlayerViews.hunger(now),
                        PlayerViews.foods(now),
                        PlayerViews.equipment(now),
                        PlayerViews.effects(now),
                        PlayerViews.gearFit(now),
                        LiveCarryReads.characterPosition(now)),
                new ItemReads(LiveCarryReads.itemTags(), LiveCarryReads.itemRegistry(),
                        new RegistryToolRequirements(), furnaceFuels),
                new WorldReads(scene, memory, blockScans, kinds, lightLevels, lightingWorld),
                senses,
                new Hands(interactions, useKeyProjection, diggings::get, new InputDriver(playerControl), digTools),
                new Travel(walksOnFoot, walks, new ClientTravelWorldView(now), travelProgress),
                new Guards(protection, new ClientCreatureSituations(now)),
                new Channels(new ChatChannel(now),
                        // 客户端刻号跟着所在世界走；没进世界读不到，按 0 兜底（只在读端内部量时长用）。
                        () -> minecraft.level == null ? 0 : minecraft.level.getGameTime(),
                        events, timers, session),
                new Stores(documents, identity.key(), configDirectory),
                compat, survivalWiring, followLighting, preview, itemsInUse, reserved, dropConfirmations, instanceConfig),
                abilities);
    }

    /**
     * 每刻的世界扫描：各路读端读当刻的观察事实，交给场景整理并发观察编号。
     * 没有角色基准时整理不出方位，先等自身观察，别的什么都不整理。
     */
    public void observe(PlayerContext current, long gameTick) {
        LocalPlayer player = current.localPlayer();
        if (player == null) {
            return;
        }
        // 保护判断要的现场先刷新：维度、记住的区域、角色周围的方块归属。
        dimension = current.level().dimension().location().toString();
        regions.refresh();
        ownership.tick(dimension, player.blockPosition());
        Instant when = Instant.now();
        var self = new ClientSelfSight(() -> current).current();
        if (self == null) {
            return;
        }
        scene.updateSelf(self);
        scene.updateEnvironment(new ClientEnvironmentSight(() -> current).current());
        scene.updateEntities(gameTick, new ClientEntitySight(() -> current).nearby());
        scene.updateSounds(gameTick, new ClientSubtitleEar(() -> subtitles).recent());
        // 俯视网格以角色为中心画一张：地形特征从网格里聚出来，路过成片的树顺路记产地线索。
        scene.updateGrid(OverheadGrid.render(current.level(), player.blockPosition(), OverheadGrid.DEFAULT_RADIUS),
                gameTick, when);
        // 设施观察：容器与工作设施两类都查（按方块标签认，模组的箱子、损坏的铁砧都算），看见的写进世界记忆；
        // 床不在扫描清单里（按颜色散成十六种）。
        Set<String> facilities = new LinkedHashSet<>(kinds.containerBlockTypes());
        facilities.addAll(kinds.workstationBlockTypes());
        observing = current;
        scene.updateFacilities(gameTick, when, facilitySight.nearby(facilities));
        // 看得见的告示牌连同牌子上的字：主播在基地立的路牌、留言，observe 里读得到。
        scene.updateSigns(gameTick, facilitySight.signs());
        // 模组的东西：电梯、车站、列车、物理结构，联动模组各自报角色附近看得到的。
        List<ModThing> things = new ArrayList<>();
        AABB around = new AABB(player.blockPosition()).inflate(THINGS_RADIUS);
        for (ModSight sight : modSights) {
            sight.sight(current.level(), player, around, things::add);
        }
        scene.updateModThings(gameTick, things);
        scene.expire(gameTick);
    }

    /** 离开这个世界：寻路不再按这个世界的保护判断挖路、不再问它的障碍来源，记下的归属作废。 */
    public void leave() {
        NavigationProtection.installWorldRule(NavigationProtection.CellRule.NONE);
        NavigationObstacles.install(List.of());
        ownership.forgetAll();
    }

    /** 世界记忆：宿主查询与能力共用的那份。 */
    public PainfulBlocks pains() {
        return pains;
    }

    public WorldMemory memory() {
        return memory;
    }

    /** 自家人名单（按在线玩家对上编号）。 */
    public TrustedPlayers trustedPlayers() {
        return trusted;
    }

    /** 这个世界的保护判断。 */
    public Protection protection() {
        return protection;
    }

    /** 这个世界的目标运行存盘：进世界时交给目标运行表，读回上次没做完的目标。 */
    public GoalRunStore goalRuns() {
        return goalRuns;
    }

    /** 感知场景：观察视图与速写都从这里整理。 */
    /**
     * 结束事件附的一行自身摘要：这个世界场景里的自身观察（每刻刷新）、所在维度与开着的界面；
     * 刚进世界还没看清自己时为空，事件照发不附。
     */
    public Optional<String> selfBrief() {
        SceneSelf self = scene.self();
        return self == null ? Optional.empty() : Optional.of(SelfBrief.line(self, dimension, menus.screens().current()));
    }

    /** observe(menu) 读的界面：开着的界面与交易报价。 */
    public MenuView.Reads menus() {
        return menus;
    }

    public Scene scene() {
        return scene;
    }

    /** 出行进展的收件口；保留最近一刻，给宿主查询用。 */
    public TravelProgressListener travelProgress() {
        return travelProgress;
    }
}
