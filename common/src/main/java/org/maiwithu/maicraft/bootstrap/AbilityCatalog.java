// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.build.BuildModule;
import org.maiwithu.maicraft.ability.machine.MachineAbilities;
import org.maiwithu.maicraft.ability.machine.MachineVehicles;
import org.maiwithu.maicraft.ability.chat.ChatAbility;
import org.maiwithu.maicraft.ability.design.DesignModule;
import org.maiwithu.maicraft.ability.design.LiveSiteCells;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.ability.design.api.BuildingRecords;
import org.maiwithu.maicraft.ability.design.api.DesignStore;
import org.maiwithu.maicraft.ability.chat.ReadsChatEcho;
import org.maiwithu.maicraft.ability.enchant.EnchantModule;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.ability.deposit.DepositModule;
import org.maiwithu.maicraft.ability.drop.DropModule;
import org.maiwithu.maicraft.ability.eat.EatModule;
import org.maiwithu.maicraft.ability.equip.EquipModule;
import org.maiwithu.maicraft.ability.explore.ExploreAbility;
import org.maiwithu.maicraft.ability.fight.FightModule;
import org.maiwithu.maicraft.ability.fish.FishModule;
import org.maiwithu.maicraft.ability.fight.LiveFightMoves;
import org.maiwithu.maicraft.behavior.combat.CombatSenses;
import org.maiwithu.maicraft.behavior.combat.LiveBattleMoves;
import org.maiwithu.maicraft.behavior.combat.ModRangedWeapons;
import org.maiwithu.maicraft.behavior.explore.endereye.ClientEnderEyes;
import org.maiwithu.maicraft.behavior.perception.ClientEntitySight;
import org.maiwithu.maicraft.behavior.interaction.ClientItemFrameHits;
import org.maiwithu.maicraft.ability.fight.LiveSeenTargets;
import org.maiwithu.maicraft.ability.follow.FollowModule;
import org.maiwithu.maicraft.ability.follow.LiveFollowMoves;
import org.maiwithu.maicraft.ability.follow.LiveFollowView;
import org.maiwithu.maicraft.ability.gather.ApproachesTargets;
import org.maiwithu.maicraft.ability.find.FindModule;
import org.maiwithu.maicraft.ability.gather.GatherAbility;
import org.maiwithu.maicraft.ability.gather.LiveSceneTargets;
import org.maiwithu.maicraft.ability.gather.LiveSpotReads;
import org.maiwithu.maicraft.ability.light.LightModule;
import org.maiwithu.maicraft.ability.vehicle.VehicleAssembleModule;
import org.maiwithu.maicraft.ability.vehicle.VehicleDriveModule;
import org.maiwithu.maicraft.ability.vehicle.VehicleTravelMode;
import org.maiwithu.maicraft.ability.vehicle.api.VehicleControls;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.quest.QuestModule;
import org.maiwithu.maicraft.ability.remember.RememberAbility;
import org.maiwithu.maicraft.ability.sequence.SequenceModule;
import org.maiwithu.maicraft.ability.sleep.SleepModule;
import org.maiwithu.maicraft.ability.suicide.SuicideModule;
import org.maiwithu.maicraft.ability.timer.TimerAbility;
import org.maiwithu.maicraft.ability.travel.TravelAbility;
import org.maiwithu.maicraft.ability.use.UseModule;
import org.maiwithu.maicraft.ability.wait.WaitModule;
import org.maiwithu.maicraft.behavior.acquire.field.ClientCropReplanting;
import org.maiwithu.maicraft.behavior.acquire.mining.ClientCollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.ClientStairsDown;
import org.maiwithu.maicraft.behavior.acquire.mining.ClientStairsUp;
import org.maiwithu.maicraft.behavior.acquire.mining.LiveStairsSite;
import org.maiwithu.maicraft.behavior.acquire.sources.ContainerSource;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemsInUse;
import org.maiwithu.maicraft.game.world.ClientCommandTree;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.sources.MenuContainerTakes;
import org.maiwithu.maicraft.behavior.acquire.mining.LiveBlockDigging;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.LiveApproachWorld;
import org.maiwithu.maicraft.behavior.approach.LiveApproaches;
import org.maiwithu.maicraft.behavior.construction.AnchorResolver;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.LiveClicks;
import org.maiwithu.maicraft.behavior.construction.LiveGroundHeights;
import org.maiwithu.maicraft.behavior.construction.LiveHolds;
import org.maiwithu.maicraft.behavior.construction.LivePlacements;
import org.maiwithu.maicraft.behavior.construction.LiveSite;
import org.maiwithu.maicraft.behavior.construction.LiveTravels;
import org.maiwithu.maicraft.behavior.construction.MemoryLedger;
import org.maiwithu.maicraft.behavior.construction.PermissionGuards;
import org.maiwithu.maicraft.behavior.construction.ShowsPreview;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientGearChanges;
import org.maiwithu.maicraft.behavior.interaction.LiveHoldsSneak;
import org.maiwithu.maicraft.behavior.inventory.drops.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.drops.TossArc;
import org.maiwithu.maicraft.behavior.inventory.drops.TossesOnto;
import org.maiwithu.maicraft.behavior.inventory.drops.ClientStepsAside;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientStackMerger;
import org.maiwithu.maicraft.behavior.inventory.drops.DropAvoidance;
import org.maiwithu.maicraft.behavior.inventory.drops.DropsItems;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.inventory.containers.ClientSpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.RememberedContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.GameRecipes;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.travel.planning.DestinationResolver;
import org.maiwithu.maicraft.behavior.travel.modes.ClientDimensionWorld;
import org.maiwithu.maicraft.behavior.travel.modes.ClientRailScout;
import org.maiwithu.maicraft.behavior.travel.modes.DimensionTravelServices;
import org.maiwithu.maicraft.behavior.travel.modes.LiveBlockUses;
import org.maiwithu.maicraft.behavior.travel.modes.LiveEntityUses;
import org.maiwithu.maicraft.behavior.travel.modes.BoatCrossing;
import org.maiwithu.maicraft.behavior.travel.modes.MinecartMode;
import org.maiwithu.maicraft.behavior.travel.modes.PearlCrossing;
import org.maiwithu.maicraft.behavior.travel.modes.PortalFinder;
import org.maiwithu.maicraft.behavior.travel.planning.SceneSeenTargets;
import org.maiwithu.maicraft.game.ChatChannel;
import org.maiwithu.maicraft.game.interaction.ClientChatDraftScreen;
import org.maiwithu.maicraft.game.interaction.OverlayMessages;
import org.maiwithu.maicraft.game.player.ClientFishingHookFacts;
import org.maiwithu.maicraft.game.serverlink.SessionServerReads;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 能力清单：启动时按这份明确的清单创建并登记能力，新增能力在清单里加一行，不做类路径扫描。
 *
 * <p>各模块的构造依赖都从这份清单的入参里来；还没有实现方的接缝先留空
 * （传 null 或 Optional.empty()），能力自己按接缝缺失如实失败，接上后在清单里补一行。
 */
public final class AbilityCatalog {

    private AbilityCatalog() {}

    /**
     * 一份清单需要的全部依赖：进世界时创建，原样递进各模块。按领域用途分成几组——
     * 当刻角色读端、物品事实读端、世界与观察读端、战斗感观、动手部件、走到与出行、
     * 许可与保护、对外通道、存放位置，外加几样只属于一件事的接缝；
     * 加一个读端就是往所在组加一个字段，再在进世界的那一处传进那个组。
     */
    public record Deps(
            CharacterReads character,
            ItemReads items,
            WorldReads world,
            /** 战斗感观：角色周围的敌对威胁、伤害证据与自己的战斗资质，一次取齐的只读接缝。 */
            CombatSenses senses,
            Hands hands,
            Travel travel,
            Guards guards,
            Channels channels,
            Stores stores,
            CompatRegistry compat,
            /** 夜晚生存需求与睡觉能力之间的晚接桥；进世界登记睡觉能力后接上真实现。 */
            SurvivalWiring survivalWiring,
            /** 随行补光的会话设置：太暗需求与 light 能力共用一份，进世界重置为默认。 */
            FollowLighting followLighting,
            /** 施工预览：design 投影的蓝图交给它画；没有画面的清单传 {@link ShowsPreview#NONE}。 */
            ShowsPreview preview,
            /** 正要用的东西：拿东西的引擎建好后接上，走路垫脚按它留够数。 */
            ItemsInUse itemsInUse,
            /** 掉落物的事实确认：捡掉落物、战斗拾荒按服务器的拾取包认东西进没进包。 */
            DropConfirmations dropConfirmations,
            /** 启动游戏的所有者放在配置文件里的开关与上限。 */
            InstanceConfig config) {

        public Deps {
            Objects.requireNonNull(character, "character");
            Objects.requireNonNull(items, "items");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(senses, "senses");
            Objects.requireNonNull(hands, "hands");
            Objects.requireNonNull(travel, "travel");
            Objects.requireNonNull(guards, "guards");
            Objects.requireNonNull(channels, "channels");
            Objects.requireNonNull(stores, "stores");
            Objects.requireNonNull(compat, "compat");
            Objects.requireNonNull(survivalWiring, "survivalWiring");
            Objects.requireNonNull(followLighting, "followLighting");
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(itemsInUse, "itemsInUse");
            Objects.requireNonNull(dropConfirmations, "dropConfirmations");
            Objects.requireNonNull(config, "config");
        }
    }

    /** 按清单创建并登记全部能力；登记顺序即能力列表的展示顺序。 */
    public static AbilityRegistry create(Deps deps) {
        return create(deps, new AbilityRegistry(new TaskFactories()));
    }

    /**
     * 按清单把能力登记进给定的注册表，返回它。先清掉旧登记：能力模块带着某一个世界的现场，
     * 退世界后现场整体丢弃，进下一个世界时按清单重新登记，不把上一个世界的模块带过来。
     */
    public static AbilityRegistry create(Deps deps, AbilityRegistry into) {
        AbilityRegistry registry = into;
        registry.clearRegistered();
        Shared shared = sharedOf(deps);

        // 用东西：读世界、组装交互、靠近、备手、去拿缺的东西、观察编号、附近搜索、动作栏提示语、
        // 走到没加载的坐标、捡掉出的东西、看点开的界面都接上了。
        registry.register(useModule(deps, shared.space(), shared.bringClose(), shared.toMainhand(), shared.innerNeeds(),
                shared.refusals()));

        // 进食：把食物换到主手走背包界面的原生搬运；手上不是食物时能换手了。
        registry.register(new EatModule(deps.character().backpack(), deps.character().offhand(),
                deps.character().hunger(), deps.character().foods(), deps.character().equipment(),
                deps.character().effects(), deps.items().itemTags(), deps.hands().useKeyProjection(),
                Optional.of(shared.toMainhand())));

        // 装备：穿卸走背包界面的原生操作；卸下时背包满了先按腾地方腾一格，腾不动才如实说装不下。
        registry.register(new EquipModule(deps.character().backpack(), deps.character().offhand(),
                deps.character().equipment(), deps.character().gearFit(), Optional.of(shared.space()),
                Optional.of(new ClientGearChanges(deps.character().context(), shared.toMainhand())),
                deps.items().itemTags()));

        // 丢弃：换手接上了；丢完朝旁边走两步，别让丢出的东西落回自己头上。
        // 丢东西的现场部件与腾地方共用一份：落点登记写进同一本避让账，走开的每一步绕开它。
        // 给了坐标就朝那一格扔（传送带、工作盆上）：接住东西的高度按那一格方块顶面算，空着的格按落到底。
        registry.register(new DropModule(deps.character().backpack(), deps.character().offhand(),
                deps.items().itemTags(), shared.dropping(),
                (itemId, count, cell, permissions) -> new TossesOnto(itemId, count, cell,
                        TossArc.catchHeightOf(deps.character().context().get(), cell), permissions, shared.bringClose(),
                        shared.toMainhand(), FirstPersonScene::of)));

        // 寻死：脱甲收图腾、挑死法走过去、重生后按原件穿回，见 registerSuicide。
        registerSuicide(registry, deps, shared);
        // 拿到物品：身上的不算来源（引擎开场就清点），已实现的途径都登记，见 obtainModule。
        // 看不见石头时往下挖楼梯：挖一格、捡这一级掉的东西与采掘共用一套，走下一级只走不改地形，脚下空着垫一块。
        ClientStairsDown stairs = stairsDown(deps, shared);
        // 从记得的箱子与现场容器拿东西的来源：候选由找容器的读端给出（现场扫描与世界记忆并成一份），
        // 刚摆下、记忆里还没有的箱子也接得住；拿到物品的引擎与附魔的受限补齐共用同一份。
        ContainerSource containerSource = containerSource(deps, shared);
        registry.register(AcquisitionWiring.obtainModule(deps, shared.playerServices(), shared.bringClose(),
                shared.toMainhand(), shared.permission(), shared.innerNeeds(), shared.collects(), stairs,
                shared.space(), containerSource, shared.drops(), shared.dropping().stepsAside()));

        // 存东西：找容器把现场扫描与世界记忆并起来，打开容器、逐笔搬运与挖盖子都接上了。
        registry.register(depositModule(deps, shared.storing(), shared.collects(), shared.networks()));

        // 附魔：缺料走受限补齐（身上与记得的容器，要下矿才够就 NEED_ITEM），界面里装料与报价
        // 走容器界面的既有规矩；观察编号查场景、记过的地点查世界记忆、台子分刻扫描找。
        registry.register(new EnchantModule(deps.character().context(),
                AcquisitionWiring.containerOnlyAcquisition(containerSource, deps, shared.space()), shared.space(),
                (at, permissions) -> new ClientMenuOpening(at, permissions, deps.compat().menuLayouts(),
                        shared.bringClose(), deps.hands().interactions(), deps.character().context()),
                new SceneSeenTargets(deps.world()::scene), deps.world().memory(), deps.world().blockScans(),
                deps.items().itemTags(), deps.items().itemRegistry()));

        // 睡觉：选床、放自带床、现做一张、白天备床与夜间自动休息都从这里接，见 registerSleep。
        registerSleep(registry, deps, shared);

        // 采集：挖、收、捡、打展示框，接线见 gatherModule。
        registry.register(gatherModule(deps, shared));

        // 建筑设计与施工：画图不控制角色，施工经玩家行为层的施工引擎，见 registerConstruction。
        DesignStore designs = new DesignStore(DesignStore.fileIn(deps.stores().configDirectory()));
        // 施工服务只拼一份：建造按图施工与机器施工走同一个施工引擎、同一套现场部件。
        Function<Permissions, ConstructionServices> construction = constructionServices(deps, shared);
        registerConstruction(registry, deps, designs, construction);

        registerMachineAbilities(registry, deps, designs, shared, construction);

        // 照明：区域补光量光选灯位、放灯交给施工引擎；随行补光的开关接会话设置。
        registry.register(new LightModule(deps.followLighting(), anchors(deps), deps.character().position(),
                construction, deps.world().lightLevels(), deps.world().lightingWorld(),
                deps.character().backpack(), deps.character().offhand()));

        registerStandalone(registry, deps, shared, construction);
        return registry;
    }

    /**
     * 清单里好几处共用的现场部件：靠近与换手、挖一格与捡掉落物、拿到物品的内需入口、
     * 游戏拒绝读端、许可检查点、联动能用的那份玩家行为、丢东西与存放的现场部件，以及腾地方。
     */
    private record Shared(LiveApproaches bringClose, ClientMovesToMainhand toMainhand, LiveBlockDigging digging,
            ClientDropPickup drops, ClientCollectsBlocks collects, DeferredInnerNeeds innerNeeds,
            ClientGameRefusals refusals, PermissionCheck permission, PlayerServices playerServices,
            DropsItems.Parts dropping, StoresInContainer.Parts storing, InventorySpace space,
            List<NetworkStorage> networks) {
    }

    /** 把共用的现场部件一次建好：世界记忆之外的每刻现场都从这里出。 */
    private static Shared sharedOf(Deps deps) {
        // 靠近与走向站位：站位判断问当刻的世界，走路交给走到（内嵌 Baritone）。
        // 许可检查点：保护判断是这个世界的那一份（生存需求要挖开的也用它），来源、采集、站位补救都用这一份。
        PermissionCheck permission = new PermissionCheck(deps.guards().protection(), deps.guards().creatures());
        LiveApproaches bringsClose = new LiveApproaches(deps.character().context(),
                new LiveApproachWorld(deps.character().context()), deps.travel().walks(), permission);
        // 换手读端提前建好：用东西的备手、进食的换手、装备的穿卸共用同一套界面搬运。
        ClientMovesToMainhand toMainhand = new ClientMovesToMainhand(deps.character().context());
        // 拿到物品引擎的内需入口先建好：用东西缺了要用的东西去拿一件、来源备料缺原料缺工具，都回到同一个引擎，
        // 引擎在登记拿到物品时建好再接上。
        DeferredInnerNeeds innerNeeds = new DeferredInnerNeeds();
        // 游戏拒绝读端：用东西与睡觉共一份（动作栏提示语同出一个来源）。
        ClientGameRefusals refusals = new ClientGameRefusals(new OverlayMessages(),
                () -> deps.channels().session().confirmations().recent(), deps.channels().clientTicks());
        // 联动能用的玩家行为：联动来源、机器类型、网络读取器进世界时都带着这一份建，
        // 走过去、点开、换手、缺东西去拿与自带能力是同一套。
        PlayerServices playerServices = new PlayerServices(deps.character().context(), bringsClose,
                deps.hands().interactions(), toMainhand,
                new ClientGearChanges(deps.character().context(), toMainhand),
                new LiveHoldsSneak(deps.hands().inputs(), deps.character().context()), innerNeeds,
                deps.guards().protection(), deps.items().itemTags(), deps.compat().menuLayouts(),
                deps.world().blockScans(), new SessionServerReads(deps.channels().session()),
                deps.character().backpack());
        // 挖一格与捡掉落物共用一套：采集、拿东西时挖矿收庄稼、存东西挖开盖子，都是"走过去、挖掉、捡起这一下掉出来的"。
        // 联动的连锁挖进世界时建一次：采掘整脉、清障一片、开路一行都经这一份按住连锁键挖，没装就是空表、逐格挖。
        LiveBlockDigging digging = new LiveBlockDigging(deps.hands().digging(),
                deps.compat().breakAccelerators(playerServices));
        ClientDropPickup drops = new ClientDropPickup(deps.character().context(), deps.travel().walks(),
                deps.dropConfirmations());
        ClientCollectsBlocks collects = new ClientCollectsBlocks(bringsClose, digging, drops);
        // 丢弃落点避让与走开读端：丢弃能力用这一份，谁丢的都登记进同一本账，
        // 走路时一并绕开，不把刚丢出去的又吸回来。
        DropAvoidance dropAvoidance = new DropAvoidance();
        ClientStepsAside stepsAside = new ClientStepsAside(deps.hands().inputs(), dropAvoidance);
        DropsItems.Parts dropping = new DropsItems.Parts(FirstPersonScene::of, deps.character().position(),
                Optional.of(toMainhand), Optional.of(stepsAside), dropAvoidance);
        // 存放的现场部件：存东西能力与腾地方共用，走过去点开用共用的打开容器动作，存完记进世界记忆。
        StoresInContainer.Parts storing = new StoresInContainer.Parts(
                (at, permissions) -> new ClientMenuOpening(at, permissions, deps.compat().menuLayouts(),
                        bringsClose, deps.hands().interactions(), deps.character().context()),
                deps.world().memory());
        // 腾地方：合并散堆、存进容器、存网络三条路都接上了，存进容器用的是存东西能力同一份现场部件；
        // 腾背包不替玩家往地上扔东西，丢弃只有丢弃能力（drop）走。装备与拿东西在背包要满时先按它腾。
        // 随身背包的接缝留给随身背包的联动模组。身上带着连着网络的无线终端（AE2）时先存进网络：联动模组登记的第一个网络存储。
        List<NetworkStorage> networks = deps.compat().networkStorages(playerServices);
        InventorySpace space = new InventorySpace(deps.character().backpack(),
                Optional.of(new ClientStackMerger(deps.character().context())),
                Optional.empty(),
                Optional.of(new RememberedContainers(deps.world().memory(), deps.character().position(),
                        deps.guards().protection())),
                Optional.of(storing),
                () -> networks.stream().findFirst());
        return new Shared(bringsClose, toMainhand, digging, drops, collects, innerNeeds, refusals,
                permission, playerServices, dropping, storing, space, networks);
    }

    /** 往下挖楼梯的现场部件：拿到物品在看不见石头时走下一级，挖一格与捡这一级掉的东西共用采掘那一套。 */
    private static ClientStairsDown stairsDown(Deps deps, Shared shared) {
        return new ClientStairsDown(
                new LiveStairsSite(deps.travel().walks(), deps.hands().interactions(), shared.toMainhand()),
                shared.digging(), shared.drops(), shared.permission());
    }

    /** 从记得的箱子与现场容器拿东西的来源：候选由找容器的读端给出（现场扫描与世界记忆并成一份）。 */
    private static ContainerSource containerSource(Deps deps, Shared shared) {
        return new ContainerSource(
                new ClientSpotsContainers(deps.world().blockScans(), deps.character().context(), deps.world().memory(),
                        deps.guards().protection(), deps.world().facilities()),
                deps.items().itemTags(), deps.guards().protection(),
                new MenuContainerTakes(shared.bringClose(), deps.hands().interactions(), deps.items().itemTags(),
                        deps.world().memory(), deps.character().context(), deps.compat().menuLayouts()));
    }

    /**
     * 跨维度出行的现场部件一次拼好：找门、进门、建门与末地门房间，出行与跟着过门共用；
     * 右键实体（坐上载具）与按住潜行（下车）也是出行共用的两样，一并交给它。
     */
    private static DimensionTravelServices dimensionPortals(Deps deps, Shared shared,
            Function<Permissions, ConstructionServices> construction) {
        return new DimensionTravelServices(
                new ClientDimensionWorld(deps.character().context(),
                        new PortalFinder(deps.character().context(), deps.world().blockScans()),
                        deps.world().blockScans()),
                deps.character().context(),
                deps.travel().walks(), shared.innerNeeds(), shared.toMainhand(),
                new LiveBlockUses(deps.hands().interactions()), construction,
                deps.world().memory(), deps.travel().walkInternals(), deps.travel().travelProgress(),
                new LiveEntityUses(deps.hands().interactions()),
                new LiveHoldsSneak(deps.hands().inputs(), deps.character().context()));
    }

    /**
     * 机器能力：看机器、审蓝图、改设置、用机器做东西。机器类型与网络读取器由登记表按装了的模组建，
     * 没装联动也照常登记，认不出机器时如实说；配方查询与查资料是同一份顺序（EMI、JEI、游戏配方表）。
     */
    private static void registerMachineAbilities(AbilityRegistry registry, Deps deps, DesignStore designs,
            Shared shared, Function<Permissions, ConstructionServices> construction) {
        RecipeLookup recipes = new RecipeLookup(deps.compat().recipeViewers(),
                new GameRecipeTable(GameRecipes.fromPlayer(deps.character().context())));
        for (AbilityModule module : MachineAbilities.all(deps.character().context(),
                deps.compat().machineTypes(shared.playerServices()),
                deps.compat().networkReaders(shared.playerServices()),
                recipes, shared.innerNeeds(), shared.bringClose(), deps.hands().interactions(),
                new SceneSeenTargets(deps.world()::scene), deps.world().memory(), deps.world().memory(),
                deps.stores().worldDocuments(), deps.stores().worldKey(),
                anchors(deps), Optional.of(designs), construction, deps.config().machineBlockLimits(),
                deps.compat().placementRules(), deps.compat().itemSettings(shared.playerServices()),
                new MachineVehicles(deps.compat().vehicleReaders(shared.playerServices()), vehicleControls(deps),
                        id -> deps.world().scene().seen().structureOf(id),
                        deps.compat().trainSchedulers(shared.playerServices()),
                        id -> deps.world().scene().seen().gameEntityOf(id)))) {
            registry.register(module);
        }
    }

    /**
     * 寻死：脱甲收图腾、挑死法走过去、重生后按原件穿回。穿卸与换手和装备共用一套，
     * 保护判断与许可检查点是这个世界的那一份。
     */
    private static void registerSuicide(AbilityRegistry registry, Deps deps, Shared shared) {
        registry.register(SuicideModule.live(deps.character().context(), deps.travel().walks(),
                deps.hands().interactions(), shared.toMainhand(),
                new ClientGearChanges(deps.character().context(), shared.toMainhand()), deps.character().effects(),
                deps.guards().protection(), shared.permission()));
    }

    /**
     * 睡觉：选床、放自带床、现做一张、白天备床与夜间自动休息都从这里接；
     * 夜晚这项生存需求的"今晚有没有床"与"找空当去睡"接上同一份判断与任务。
     */
    private static void registerSleep(AbilityRegistry registry, Deps deps, Shared shared) {
        SleepModule sleepModule = new SleepModule(deps.character().context(), deps.world().blockScans(),
                deps.guards().protection(), deps.character().backpack(), deps.character().offhand(),
                deps.hands().interactions(), shared.bringClose(), shared.collects(),
                deps.world().memory(),
                new DestinationResolver(deps.travel().travelWorld(), deps.world().memory(),
                        new SceneSeenTargets(deps.world()::scene)),
                shared.innerNeeds(), shared.refusals()::shownMessage, deps.survivalWiring().autoSleep());
        registry.register(sleepModule);
        deps.survivalWiring().attach(sleepModule.bedAvailability(), sleepModule, sleepModule);
    }

    /**
     * 建筑设计与施工：图纸按实例存在 config/maicraft/designs.sqlite，锚点从角色、坐标、记得的地点与观察编号落实；
     * 施工的许可按这次任务的来，缺料回到拿到物品的内需入口，挖与捡共用采集那一套。
     */
    private static void registerConstruction(AbilityRegistry registry, Deps deps, DesignStore designs,
            Function<Permissions, ConstructionServices> construction) {
        AnchorResolver anchors = anchors(deps);
        // design 导出与 build 导入用同一个 schematics 目录：导出的文件名直接当 file 参数喂回去。
        Path schematics = deps.stores().configDirectory().resolveSibling("schematics");
        // 读现场按这一刻的客户端世界读：看不看得见与观察同一套遮挡，自己放的问这个世界的保护判断。
        BuildingRecords buildings = buildingRecords(deps);
        registry.register(new DesignModule(designs, anchors, deps.preview(), schematics, buildings,
                new LiveSiteCells(deps.character().context(), deps.guards().protection())));
        registry.register(new BuildModule(designs, anchors, schematics, construction, buildings));
    }

    /**
     * 建筑档案这一摊：档案按世界存在能力自己的文档里，存好就把"这一栋在哪、叫什么"记进世界记忆；
     * 同名的记过地点问世界记忆；布局里的设施照场景里的认法（容器、工作站）。
     */
    private static BuildingRecords buildingRecords(Deps deps) {
        return new BuildingRecords(
                new BuildingArchives(deps.stores().worldDocuments(), deps.stores().worldKey()),
                deps.world().memory(),
                archive -> deps.world().memory().rememberBuilding(
                        new WorldPosition(archive.anchor().getX(), archive.anchor().getY(),
                                archive.anchor().getZ(), archive.dimension()), archive.name(), Instant.now()),
                id -> deps.world().facilities().isContainer(id) || deps.world().facilities().isWorkstation(id));
    }

    /** 锚点解析：观察编号查场景、地标查世界记忆、脚下与坐标直接用，省 y 取地表；建造与机器施工共用。 */
    private static AnchorResolver anchors(Deps deps) {
        return new AnchorResolver(new SceneSeenTargets(deps.world()::scene), deps.world().memory(),
                deps.character().position(), new LiveGroundHeights(deps.character().context()));
    }

    /**
     * 施工服务按每次任务的许可拼一份：现场、靠近、放置、点击、挖掘、内需、备手、许可、记账、走到，
     * 扳手拆机器件与放置规则也在内；建造与机器施工都用这一个。连锁挖在共用的挖掘里（清障按命中面 3×3 连锁），
     * 扳手进世界时建一次，每次施工共用，没装就是空表、照原来的挖。
     */
    private static Function<Permissions, ConstructionServices> constructionServices(Deps deps, Shared shared) {
        List<DismantleTool> dismantlers = deps.compat().dismantleTools(shared.playerServices());
        // 放置规则不用玩家行为：登记表里的整张表原样交给施工（换物品、对着哪格放、等哪几格）与放置预测（放下后自改）。
        List<PlacementRules> placementRules = deps.compat().placementRules();
        return permissions -> {
            LiveSite site = new LiveSite(deps.character().context());
            return new ConstructionServices(site, shared.bringClose(),
                    new LivePlacements(deps.character().context(), placementRules),
                    new LiveClicks(deps.hands().interactions(), deps.hands().inputs(), deps.character().context()),
                    shared.digging(), shared.innerNeeds(),
                    new LiveHolds(shared.toMainhand(), deps.character().context()),
                    new PermissionGuards(() -> shared.permission(), permissions, site::dimension),
                    new MemoryLedger(deps.world().memory(), site::dimension), new LiveTravels(deps.travel().walks()),
                    dismantlers, placementRules);
        };
    }

    /** 寻找：方块与实体扫已加载区，结构查记过的产地线索；要塞附近没有线索时扔末影之眼指路。
     *  实体的类型、可见与特征读感知的同一份实体观察，命中直接在场景的观察编号表上领编号。 */
    private static void registerFind(AbilityRegistry registry, Deps deps, Shared shared) {
        registry.register(FindModule.live(deps.character().context(), deps.world().blockScans(),
                new ClientEntitySight(deps.character().context()), deps.guards().creatures(), deps.world().memory(),
                deps.world().scene(),
                new ClientEnderEyes(deps.character().context(), deps.travel().walks(), shared.toMainhand(),
                        shared.drops())));
    }

    /** 不和别的能力共用现场部件的那些能力：战斗、跟随、等待、计时、钓鱼、出行、聊天、记地点、按顺序做事、任务书。 */
    private static void registerStandalone(AbilityRegistry registry, Deps deps, Shared shared,
            Function<Permissions, ConstructionServices> construction) {
        PermissionCheck permission = shared.permission();
        PlayerServices services = shared.playerServices();
        // 战斗：感观与生存需求共用一份，观察编号与动手都接在真实客户端上。
        registry.register(new FightModule(deps.senses(),
                new LiveSeenTargets(deps.world().scene().seen()),
                new LiveFightMoves(deps.travel().walks(), deps.dropConfirmations(), deps.hands().useKeyProjection(),
                        new ModRangedWeapons(deps.compat()::rangedWeapons, deps.character().foods()),
                        // 拾荒和采集、打猎共用一套捡掉落物。
                        new ClientDropPickup(deps.character().context(), deps.travel().walks(),
                                deps.dropConfirmations())),
                permission));

        // 跨维度出行的现场部件一次拼好：找门、进门、建门与末地门房间，出行与跟着过门共用。
        DimensionTravelServices portals = dimensionPortals(deps, shared, construction);

        // 跟随：常驻任务，目标从场景的观察编号找；坐车先认原版的船与骆驼、再问联动登记的（列车座位），过门走出行的跨维度段，
        // 帮打用和自卫同一套战斗动手，跟不过去、跟丢了经事件出口告诉宿主。
        registry.register(new FollowModule(deps.travel().walks(), new LiveFollowView(deps.world().scene().seen()),
                new LiveFollowMoves(deps.travel().walks(), deps.hands().interactions(),
                        deps.compat().rideAlongs(services), portals,
                        deps.senses(),
                        new LiveBattleMoves(deps.travel().walks(), deps.hands().useKeyProjection(),
                                new ModRangedWeapons(deps.compat()::rangedWeapons, deps.character().foods())),
                        new LiveHoldsSneak(deps.hands().inputs(), deps.character().context())),
                deps.channels().events()));

        // 等待占着身体站在原地等；不占身体的"过 N 秒叫我"归计时，往启动侧每刻看钟的那份计时表里挂。
        registry.register(new WaitModule());
        registry.register(new TimerAbility(deps.channels().timers()));

        // 钓鱼：钓位几何与咬钩收竿都接上了；缺竿回到拿到物品的内需入口，捡钓获用顺手捡起同一套。
        registry.register(new FishModule(deps.character().context(), shared.innerNeeds(), shared.toMainhand(),
                deps.travel().walks(), shared.drops(), shared.space(),
                new ClientFishingHookFacts(deps.character().context())));

        // 出行：目的地解析用当刻的世界、记住的地点与场景里的观察编号；走到由内嵌 Baritone 执行，
        // 路上垫的方块从走到实现方读回。跨维度的现场部件一次拼好：找门、进门、建门与末地门房间共用。
        // 联动登记的出行方式（电梯、喷气背包、火车）进世界时建好交给出行：远处或走不通时比它们的报价。
        // 回露天往上挖楼梯和往下挖楼梯读同一份现场、挖一格用同一套原生挖掘。
        registry.register(new TravelAbility(deps.travel().walks(),
                new DestinationResolver(deps.travel().travelWorld(), deps.world().memory(),
                        new SceneSeenTargets(deps.world()::scene)),
                deps.travel().walkInternals(), deps.travel().travelProgress(), portals,
                deps.travel().travelWorld(), travelModes(deps, services, portals),
                new ClientStairsUp(new LiveStairsSite(deps.travel().walks()), shared.digging(), permission)));

        registerFind(registry, deps, shared);

        // 探索：观察推进与走行段在探索走行里，发现落进世界记忆并经事件出口告诉宿主。
        registry.register(ExploreAbility.live(deps.character().context(), deps.world().blockScans(),
                deps.travel().walks(), deps.world().memory(), deps.channels().events()));

        // 聊天：发送与回显确认都走游戏聊天通道；是否放行游戏命令由所有者的实例配置决定。
        registry.register(chatModule(deps));

        // 记地点：只改世界记忆，当场完成；位置从角色、场景里的观察编号与已记的地点解析。
        registry.register(new RememberAbility(deps.world().memory(), deps.character().position(),
                new SceneSeenTargets(deps.world()::scene)));

        // 按顺序做事：逐步推进在目标推进里，这里只登记"接受步骤"的能力。
        registry.register(new SequenceModule());

        // 任务书：提交、勾选、领奖经登记表交来的任务书操作做；FTB 任务没装时能力不登记。
        registry.register(new QuestModule(deps.compat().questBooks(services)));
        // 组装载具：物理组装器拉一次杆组装或拆回；观察编号 s# 从场景的编号表换成结构身份。
        registry.register(new VehicleAssembleModule(deps.compat().vehicleAssemblers(services),
                new SceneSeenTargets(deps.world()::scene), id -> deps.world().scene().seen().structureOf(id),
                deps.world().memory(),
                () -> deps.character().context().get() == null || deps.character().context().get().localPlayer() == null
                        ? BlockPos.ZERO : deps.character().context().get().localPlayer().blockPosition()));
        // 驾驶载具：坐上驾驶座接管控制件试按或开到某处；不在车上先经出行上车，观察编号 s# 同样从场景的编号表换成结构身份，
        // 去记过的地点按世界记忆找，坐标没给 y、按方向开多远从角色脚下算。
        registry.register(new VehicleDriveModule(deps.compat().vehicleDrivers(services),
                deps.compat().vehicleReaders(services), vehicleControls(deps),
                new SceneSeenTargets(deps.world()::scene), id -> deps.world().scene().seen().structureOf(id),
                deps.world().memory(),
                () -> deps.character().context().get() == null || deps.character().context().get().localPlayer() == null
                        ? BlockPos.ZERO : deps.character().context().get().localPlayer().blockPosition()));
    }

    /**
     * 出行的方式：联动登记的（电梯、喷气背包、火车、上下船），装了能开载具的联动时再加开自己的载具（via=vehicle）；
     * 原版自带的末地折跃门（via=end_gateway）总在，只在末地用得上；原版船（via=boat）只在主世界用得上，
     * 都用跨维度那一份现场部件读格、走到、换主手；原版矿车（via=minecart）总在，沿铺好的铁轨坐一段，
     * 铁轨扫描、读格、走到、放车也靠这份部件。
     */
    private static List<TravelMode> travelModes(Deps deps, PlayerServices services, DimensionTravelServices portals) {
        List<TravelMode> modes = new ArrayList<>(deps.compat().travelModes(services));
        List<VehicleDriver> drivers = deps.compat().vehicleDrivers(services);
        if (!drivers.isEmpty()) {
            modes.add(new VehicleTravelMode(drivers, deps.compat().vehicleReaders(services), vehicleControls(deps),
                    id -> deps.world().scene().seen().structureOf(id)));
        }
        modes.add(new PearlCrossing(portals));
        // 原版船（via=boat）也是原版自带：隔水时放船划过去，用跨维度那一份现场部件读格、走到、换主手。
        modes.add(new BoatCrossing(portals));
        // 原版矿车（via=minecart）也是原版自带：沿铺好的铁轨坐一段，四种铁轨的扫描挂在方块扫描服务上。
        modes.add(new MinecartMode(new ClientRailScout(deps.character().context(), deps.world().blockScans()),
                portals));
        return modes;
    }

    /**
     * 操纵表：按结构身份存在这个世界的文档库里。它不留状态，机器能力（看、改）与驾驶能力各拿一份读写同一处。
     */
    private static VehicleControls vehicleControls(Deps deps) {
        return new VehicleControls(deps.stores().worldDocuments(), deps.stores().worldKey());
    }

    /** 聊天能力的一份：命令的完成依据（提交后聊天栏冒出的反馈行）与聊天回显同出一个聊天栏日志。
     *  包私有是给总装测试的接缝：测试从这里取组装好的能力，钉住实例配置接进了逐字打字开关。 */
    static ChatAbility chatModule(Deps deps) {
        ChatChannel chat = deps.channels().chat();
        ReadsChatEcho echo = new ReadsChatEcho() {
            @Override public boolean appearsInChat(String message) { return chat.appearsInChat(message); }
            // 游戏命令不回显成聊天：发前记下聊天栏的记号，发后读这之后服务器回了什么。
            @Override public long mark() { return chat.mark(); }
            @Override public List<String> shownSince(long mark) { return chat.shownSince(mark); }
        };
        // 逐字打字开的是真实聊天框，框里放的是草稿；提交仍走聊天通道。
        // 管理员命令认不认得出，单人时问内置服务器的命令树；连远程服务器时只按名单认。
        ClientCommandTree commandTree = new ClientCommandTree();
        // 逐字打字开关必须从实例配置接进来：它决定发话任务一开始是逐字打草稿，还是整句直接提交。
        return new ChatAbility(chat::send, echo, new ClientChatDraftScreen(), deps.config().allowGameCommands(),
                deps.config().chatTypewriter(), commandTree::adminOnly);
    }

    /** 用东西能力的一份：游戏拒绝读端看动作栏与服务端确认流，与睡觉能力共用一份。 */
    private static UseModule useModule(Deps deps, InventorySpace space, LiveApproaches bringsClose,
            ClientMovesToMainhand toMainhand, ItemNeeds needs, ClientGameRefusals refusals) {
        // 要空手却腾不出格子时，用和拿东西同一份背包空间先腾一格。
        return UseModule.live(deps.character().context(), deps.hands().interactions(), bringsClose, toMainhand, needs,
                deps.world()::scene, deps.world().blockScans(), refusals, deps.travel().walks(),
                deps.dropConfirmations(), deps.world().memory(), deps.guards()::protection,
                deps.guards().creatures(), space, deps.world().facilities());
    }

    /** 存东西能力的一份：找容器把现场扫描与世界记忆并起来，归属与压住盖子的方块问这个世界的保护判断。 */
    private static DepositModule depositModule(Deps deps, StoresInContainer.Parts storing,
            ClientCollectsBlocks collects, List<NetworkStorage> networks) {
        // 存进网络（into=network）用和腾地方同一份网络存储：联动模组登记的第一个。
        return DepositModule.live(deps.character().context(), storing, collects,
                deps.items().itemTags(), deps.world().memory(), deps.character().backpack(),
                deps.world().blockScans(), deps.guards().protection(), deps.world()::scene,
                deps.world().facilities(), () -> networks.stream().findFirst());
    }

    /**
     * 采集：观察编号从场景查，现场从世界读，靠近用站位与走到，挖用原生挖掘；收完把身上的种子补种回原格，
     * 没有种子就不补，不额外去找。点名展示框时走近按实体的够得着规则，左键打一下再捡弹出来的东西。
     */
    private static GatherAbility gatherModule(Deps deps, Shared shared) {
        ClientItemFrameHits frames = new ClientItemFrameHits(deps.character().context(), shared.bringClose());
        return new GatherAbility(new LiveSceneTargets(deps.world()::scene, frames),
                new LiveSpotReads(deps.character().context()),
                approaches(shared.bringClose()), shared.digging(), deps.items().toolRequirements(),
                new ClientCropReplanting(shared.toMainhand(), deps.hands().interactions(),
                        deps.character().context()),
                shared.drops(), shared.permission(), deps.character().backpack(), deps.character().offhand(),
                frames);
    }

    /** 采集的靠近：目标落实成一格要挖掉的方块后交给靠近模型，走过去能动多少地形按这次任务的许可来。 */
    private static ApproachesTargets approaches(LiveApproaches bringsClose) {
        return (target, permissions, records) ->
                bringsClose.toward(ApproachTarget.ofBlockToDig(target), permissions, records);
    }
}
