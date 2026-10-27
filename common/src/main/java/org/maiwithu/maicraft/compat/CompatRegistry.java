// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import org.maiwithu.maicraft.behavior.acquire.spi.FluidTransforms;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.Optional;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.StructureWorksites;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;
import org.maiwithu.maicraft.behavior.travel.spi.TravelMode;
import org.maiwithu.maicraft.behavior.navigation.spi.ObstacleSource;
import org.maiwithu.maicraft.behavior.landing.spi.FallBrake;
import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.behavior.navigation.spi.FlyUpGear;
import org.maiwithu.maicraft.behavior.navigation.spi.StowBeforeWalking;
import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.ability.follow.spi.RideAlong;
import org.maiwithu.maicraft.ability.vehicle.spi.FlightStabilizers;
import org.maiwithu.maicraft.ability.vehicle.spi.TrainScheduler;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleDriver;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleReader;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.spi.MenuLayoutProof;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.serverlink.ClientOperation;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联动登记表：启动时按联动清单逐行检查，收集登记了的联动入口交来的 spi 接口实现，
 * 再由启动交给玩家行为层与能力（拿到物品的来源、机器类型、网络读取器、任务书操作、
 * 查资料的知识来源、查配方的配方查看器、认得出的模组界面、施工的放置规则、战斗的远程武器、出行方式、寻路的障碍来源、落地防护的刹车、场景里模组的东西、组装载具的办法）。不是 Minecraft 的注册表。
 *
 * <p>要用进了世界才有的玩家行为（走过去、点开、保护判断）的，存的是建法，进世界时带着联动能用的玩家行为再建；
 * 建出来的都包一层：模组停用、碰到模组接口对不上时如实回答"用不了"，不让它变成任务的内部错误。
 *
 * <p>每种 spi 能力是一个 {@link Slot}：收东西、包装、回滚都在槽里；下面的槽字段用 {@link #slot} 声明，
 * 声明即进入槽集合，回滚按槽集合撤——没有第二份要手工同步的槽清单，新加一种槽不会漏。
 *
 * <p>逐行检查的规矩：没装的跳过；装的版本不在验证过的范围内，不登记；创建或交接时出错，不登记并撤掉它交了一半的东西。
 * 每种情况写一行日志说明原因，一个模组出问题不影响别的模组。确认装了、版本对了之后才调创建，
 * 引用模组类的代码在那之前不会被加载。
 */
public final class CompatRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(CompatRegistry.class);

    private final List<CompatModule> modules = new ArrayList<>();

    /** 全部的槽，按声明顺序：交接到一半出错时，每个槽按交接前的长度一起撤回。 */
    private final List<Slot<?>> allSlots = new ArrayList<>();

    private final Slot<ItemSource> itemSources = slot("物品来源", CompatItemSource::new);
    private final Slot<MachineType> machineTypes = slot("机器类型", CompatMachineType::new);
    private final Slot<NetworkReader> networkReaders = slot("网络读取器", CompatNetworkReader::new);
    private final Slot<QuestBookOperations> questBooks = slot("任务书操作", CompatQuestBookOperations::new);
    private final Slot<BreakAccelerator> breakAccelerators = slot("挖掘加速", CompatBreakAccelerator::new);
    private final Slot<DismantleTool> dismantleTools = slot("拆卸工具", CompatDismantleTool::new);
    private final Slot<ItemSettings> itemSettings = slot("物品设置", CompatItemSettings::new);
    private final Slot<TravelMode> travelModes = slot("出行方式", CompatTravelMode::new);
    private final Slot<NetworkStorage> networkStorages = slot("网络存储", CompatNetworkStorage::new);
    private final Slot<MenuLayoutProof> menuLayouts = slot("界面布局证明", CompatMenuLayoutProof::new);
    private final Slot<KnowledgeSource> knowledgeSources = slot("知识来源", CompatKnowledgeSource::new);
    private final Slot<RecipeViewer> recipeViewers = slot("配方查看器", CompatRecipeViewer::new);
    private final Slot<ClientOperation> serverReads = slot("只读操作声明");
    private final Slot<PlacementRules> placementRules = slot("放置规则", CompatPlacementRules::new);
    private final Slot<RangedWeapon> rangedWeapons = slot("远程武器", CompatRangedWeapon::new);
    private final Slot<ObstacleSource> obstacleSources = slot("障碍来源", CompatObstacleSource::new);
    private final Slot<FluidTransforms> fluidTransforms = slot("流体转化配方表", CompatFluidTransforms::new);
    private final Slot<FallBrake> fallBrakes = slot("刹住一掉的办法", CompatFallBrake::new);
    private final Slot<LandingMaterials> landingMaterials = slot("落地补料来源", CompatLandingMaterials::new);
    private final Slot<StowBeforeWalking> stowsBeforeWalking = slot("走路前收起来的东西", CompatStowBeforeWalking::new);
    private final Slot<FlyUpGear> flyUpGears = slot("能飞上高处的装备", CompatFlyUpGear::new);
    private final Slot<ModSight> modSights = slot("观察", CompatModSight::new);
    private final Slot<VehicleAssembler> vehicleAssemblers = slot("组装载具的办法", CompatVehicleAssembler::new);
    private final Slot<VehicleDriver> vehicleDrivers = slot("驾驶载具的办法", CompatVehicleDriver::new);
    private final Slot<VehicleReader> vehicleReaders = slot("读载具的办法", CompatVehicleReader::new);
    private final Slot<TrainScheduler> trainSchedulers = slot("定列车时刻表的办法", CompatTrainScheduler::new);
    private final Slot<RideAlong> rideAlongs = slot("同乘的办法", CompatRideAlong::new);
    private final Slot<FlightStabilizers> flightStabilizers = slot("飞行稳定器", CompatFlightStabilizers::new);
    private final Slot<StructureWorksites> structureWorksites = slot("物理结构上的工地", CompatStructureWorksites::new);
    private final List<String> decisions = new ArrayList<>();

    /** 声明一种槽：建这个动作同时把它放进槽集合，回滚按槽集合撤，不用另记一份。 */
    private <T> Slot<T> slot(String what, BiFunction<CompatModule, T, T> wrap) {
        Slot<T> made = new Slot<>(what, wrap);
        allSlots.add(made);
        return made;
    }

    /** 声明一种不用包装的槽。 */
    private <T> Slot<T> slot(String what) {
        return slot(what, null);
    }

    private CompatRegistry() {}

    /** 没有任何联动的登记表：Fabric 一侧与离线测试用。 */
    public static CompatRegistry empty() {
        return new CompatRegistry();
    }

    /** 从联动清单建登记表：逐行检查，登记通过的，每行的结论写进日志。 */
    public static CompatRegistry load(List<SupportedMod<CompatModule>> catalog, LoaderEnvironment loader) {
        Objects.requireNonNull(loader, "loader");
        CompatRegistry registry = new CompatRegistry();
        for (SupportedMod<CompatModule> mod : catalog) {
            registry.check(mod, loader);
        }
        return registry;
    }

    private void check(SupportedMod<CompatModule> mod, LoaderEnvironment loader) {
        Optional<String> skip = mod.skipReason(loader);
        if (skip.isPresent()) {
            decide(mod, skip.get());
            return;
        }
        // 创建与交接可能抛 LinkageError（读写端一碰模组类就对不上），也一并当作这一行出错，不让它炸掉启动。
        // 槽集合由槽字段自举：每个槽都在这里撤，没有哪个槽能漏在回滚外面。
        int[] before = allSlots.stream().mapToInt(Slot::size).toArray();
        CompatModule module;
        try {
            module = Objects.requireNonNull(mod.create().get(), "创建返回了 null");
            module.contribute(this);
        } catch (RuntimeException | LinkageError failure) {
            for (int i = 0; i < allSlots.size(); i++) {
                allSlots.get(i).truncate(before[i]);
            }
            decide(mod, "创建时出错，不登记：" + failure);
            return;
        }
        modules.add(module);
        decide(mod, "已登记，版本 " + mod.installedVersion(loader));
    }

    private void decide(SupportedMod<CompatModule> mod, String conclusion) {
        String line = mod.name() + "（" + mod.modId() + "）：" + conclusion;
        decisions.add(line);
        LOG.info("联动 {}", line);
    }

    /** 联动入口交一个不用玩家行为的物品来源：带上自己，登记表包一层，模组停用后这个来源问价回答不支持。 */
    public void itemSource(CompatModule module, ItemSource source) {
        Objects.requireNonNull(source, "source");
        itemSource(module, services -> source);
    }

    /**
     * 联动入口交一个物品来源的建法：进世界时带着这一份玩家行为建出来源（走过去、点开、搬东西都和自带来源一样做）。
     * 建出来的来源由登记表包一层，模组停用后问价回答不支持。
     */
    public void itemSource(CompatModule module, Function<PlayerServices, ItemSource> build) {
        itemSources.add(module, build);
    }

    /** 联动入口交网络存储的建法：腾地方时把东西存进网络（AE2 的无线终端），包一层后交给背包空间。 */
    public void networkStorage(CompatModule module, Function<PlayerServices, NetworkStorage> build) {
        networkStorages.add(module, build);
    }

    /** 联动入口交一种机器的建法：进世界时建，包一层后交给机器能力；模组停用后不再认领方块。 */
    public void machineType(CompatModule module, Function<PlayerServices, MachineType> build) {
        machineTypes.add(module, build);
    }

    /** 联动入口交一种网络读取器的建法：进世界时建，包一层后交给机器能力；模组停用后汇总如实说读不到。 */
    public void networkReader(CompatModule module, Function<PlayerServices, NetworkReader> build) {
        networkReaders.add(module, build);
    }

    /** 联动入口交任务书操作的建法：进世界时建，包一层后交给 quest 能力；模组停用后任务书说用不了。 */
    public void questBook(CompatModule module, Function<PlayerServices, QuestBookOperations> build) {
        questBooks.add(module, build);
    }

    /** 联动入口交一种物品设置（过滤器写规则）的建法：进世界时建，包一层后交给机器能力；模组停用后不再认物品。 */
    public void itemSettings(CompatModule module, Function<PlayerServices, ItemSettings> build) {
        itemSettings.add(module, build);
    }

    /** 联动入口交一种出行方式（电梯、喷气背包、火车）的建法：进世界时建，包一层后交给出行；模组停用后报价答用不了。 */
    public void travelMode(CompatModule module, Function<PlayerServices, TravelMode> build) {
        travelModes.add(module, build);
    }

    /** 联动入口交一个挖掘加速（连锁挖）的建法：进世界时建，包一层后交给施工的清障；模组停用后给不出批，退回逐格挖。 */
    public void breakAccelerator(CompatModule module, Function<PlayerServices, BreakAccelerator> build) {
        breakAccelerators.add(module, build);
    }

    /** 联动入口交一个拆卸工具的建法：进世界时建，包一层后交给施工的清障；模组停用后不再认领方块，照原来的挖。 */
    public void dismantleTool(CompatModule module, Function<PlayerServices, DismantleTool> build) {
        dismantleTools.add(module, build);
    }

    /**
     * 联动入口交一份界面布局证明：证明过的模组界面能和原版界面一样读两侧、搬东西。
     * 同一种界面重复登记、或想改写原版界面时当场出错，这个模组不登记。
     */
    public void menuLayout(CompatModule module, MenuLayoutProof proof) {
        menuLayouts.add(module, proof);
        // 当场试建一次：重复或改写原版的登记在启动时就暴露，并按交接出错撤掉这个模组交的东西。
        new MenuLayouts(menuLayouts.ready());
    }

    /** 联动入口交一个知识来源：带上自己，登记表包一层，模组停用后目录为空、状态写明原因。 */
    public void knowledgeSource(CompatModule module, KnowledgeSource source) {
        knowledgeSources.add(module, source);
    }

    /**
     * 联动入口交一个配方查看器（EMI、JEI 这类）：带上自己，登记表包一层，模组停用后回答"用不了"，配方查询就去问下一个。
     * 和知识来源一样不用玩家行为，启动时就交给查资料与查配方；只在客户端线程上读。
     */
    public void recipeViewer(CompatModule module, RecipeViewer viewer) {
        recipeViewers.add(module, viewer);
    }

    /**
     * 联动入口声明要向服务端问的一个只读操作（例如 ae2.network）：启动时就要登记进客户端的请求路由，
     * 进世界后才问得了；服务端没装对应的读数、或所有者关了，问的时候如实说读不到。
     */
    public void serverRead(CompatModule module, String operationId, int version) {
        Objects.requireNonNull(module, "module");
        serverReads.add(new ClientOperation(operationId, version, false));
    }

    /**
     * 联动入口交一份放置规则（带漏斗拿漏斗放、等下面的带子）：不用玩家行为，启动时就交给施工与机器评审；
     * 登记表包一层，模组停用后什么都不答，施工按原版默认放。
     */
    public void placementRules(CompatModule module, PlacementRules rules) {
        placementRules.add(module, rules);
    }

    /**
     * 联动入口交一种远程武器（土豆加农炮）的事实：不用玩家行为，启动时就交给战斗；
     * 登记表包一层，模组停用后什么都不认，战斗只用弓弩。
     */
    public void rangedWeapon(CompatModule module, RangedWeapon weapon) {
        rangedWeapons.add(module, weapon);
    }

    /** 联动入口交一种组装载具的办法（物理组装器）的建法：进世界时建，包一层后交给 vehicle_assemble。 */
    public void vehicleAssembler(CompatModule module, Function<PlayerServices, VehicleAssembler> build) {
        vehicleAssemblers.add(module, build);
    }

    /** 联动入口交一种驾驶载具的办法（无线红石打字机）的建法：进世界时建，包一层后交给 vehicle_drive。 */
    public void vehicleDriver(CompatModule module, Function<PlayerServices, VehicleDriver> build) {
        vehicleDrivers.add(module, build);
    }

    /** 联动入口交一种读载具的办法（物理结构的座位、回路、姿态）的建法：进世界时建，包一层后交给 machine_inspect。 */
    public void vehicleReader(CompatModule module, Function<PlayerServices, VehicleReader> build) {
        vehicleReaders.add(module, build);
    }

    /** 联动入口交一种给列车定时刻表的办法（机械动力）的建法：进世界时建，包一层后交给 machine_configure。 */
    public void trainScheduler(CompatModule module, Function<PlayerServices, TrainScheduler> build) {
        trainSchedulers.add(module, build);
    }

    /** 联动入口交一种同乘办法（机械动力的列车座位）的建法：进世界时建，包一层后交给跟随。 */
    public void rideAlong(CompatModule module, Function<PlayerServices, RideAlong> build) {
        rideAlongs.add(module, build);
    }

    /** 联动入口交一种读写飞行稳定器的办法（Flight Control）的建法：进世界时建，包一层后交给看载具与驾驶。 */
    public void flightStabilizers(CompatModule module, Function<PlayerServices, FlightStabilizers> build) {
        flightStabilizers.add(module, build);
    }

    /** 联动入口交一种在物理结构上施工的工地（Sable）的建法：进世界时建，包一层后交给机器施工；模组停用后认不出结构。 */
    public void structureWorksites(CompatModule module, Function<PlayerServices, StructureWorksites> build) {
        structureWorksites.add(module, build);
    }

    /** 联动入口交一种观察（电梯、车站、列车、物理结构在 observe 里的样子）：包一层后交给场景；模组停用后不再报。 */
    public void modSight(CompatModule module, ModSight sight) {
        modSights.add(module, sight);
    }

    /** 联动入口交一种刹住一掉的办法（喷气背包开悬停）：包一层后交给落地防护；模组停用后刹不住，照原来放水。 */
    public void fallBrake(CompatModule module, FallBrake brake) {
        fallBrakes.add(module, brake);
    }

    /** 联动入口交一样"没带落地料时从哪儿现取"（AE2 无线终端）：包一层后交给落地防护；模组停用后取不到。 */
    public void landingMaterial(CompatModule module, LandingMaterials materials) {
        landingMaterials.add(module, materials);
    }

    /** 联动入口交一样走路前要收起来的东西（误开的喷气背包）：包一层后交给寻路；模组停用后不再收。 */
    public void stowBeforeWalking(CompatModule module, StowBeforeWalking stow) {
        stowsBeforeWalking.add(module, stow);
    }

    /** 联动入口交一样能直接飞上高处的装备（喷气背包）：包一层后交给寻路；模组停用后当没有。 */
    public void flyUpGear(CompatModule module, FlyUpGear gear) {
        flyUpGears.add(module, gear);
    }

    /** 联动入口交一个障碍来源（会动的结构）：包一层后交给寻路；模组停用后不再报障碍。 */
    public void obstacleSource(CompatModule module, ObstacleSource source) {
        obstacleSources.add(module, source);
    }

    /** 联动入口交一份"扔进流体里会变"的配方表（AE2 的转化）：包一层后交给拿东西；模组停用后一条都不给。 */
    public void fluidTransforms(CompatModule module, FluidTransforms transforms) {
        fluidTransforms.add(module, transforms);
    }

    /**
     * 这局真正登记上、此刻还开着的联动模组 ID，按字母排：没装的、版本不对没登记的、用着用着接口对不上停用了的都不在里面。
     * lookup 列能力时把它放在顶上，宿主据此决定哪些靠模组的技能进目录。
     */
    public List<String> installedModIds() {
        return modules.stream().filter(CompatModule::active).map(CompatModule::modId).distinct().sorted().toList();
    }

    /** 登记了的联动入口，按清单顺序。 */
    public List<CompatModule> modules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * 进世界时建联动模组的物品来源，拿到物品的引擎把它们排在自带来源之后。
     * 停用了的模组不建；建的时候出错（模组接口对不上、读写端自己出错）只少这一个来源，写一行日志说明。
     *
     * @param services 这个世界里的玩家行为；不用它的来源不会碰它
     */
    public List<ItemSource> itemSources(PlayerServices services) {
        return itemSources.build(services);
    }

    /** 进世界时建联动模组的机器类型；规矩同物品来源。 */
    public List<MachineType> machineTypes(PlayerServices services) {
        return machineTypes.build(services);
    }

    /** 进世界时建联动模组的网络读取器；规矩同物品来源。 */
    public List<NetworkReader> networkReaders(PlayerServices services) {
        return networkReaders.build(services);
    }

    /** 进世界时建联动模组的任务书操作；规矩同物品来源。 */
    public List<QuestBookOperations> questBooks(PlayerServices services) {
        return questBooks.build(services);
    }

    /** 进世界时建联动模组的物品设置；规矩同物品来源。 */
    public List<ItemSettings> itemSettings(PlayerServices services) {
        return itemSettings.build(services);
    }

    /** 进世界时建联动模组的网络存储（身上的无线终端存东西）；规矩同物品来源。 */
    public List<NetworkStorage> networkStorages(PlayerServices services) {
        return networkStorages.build(services);
    }

    /** 进世界时建联动模组的出行方式；规矩同物品来源。 */
    public List<TravelMode> travelModes(PlayerServices services) {
        return travelModes.build(services);
    }

    /** 进世界时建联动模组的组装载具办法；规矩同物品来源。 */
    public List<VehicleAssembler> vehicleAssemblers(PlayerServices services) {
        return vehicleAssemblers.build(services);
    }

    /** 进世界时建联动模组的驾驶载具办法；规矩同物品来源。 */
    public List<VehicleDriver> vehicleDrivers(PlayerServices services) {
        return vehicleDrivers.build(services);
    }

    /** 进世界时建联动模组的读载具办法；规矩同物品来源。 */
    public List<VehicleReader> vehicleReaders(PlayerServices services) {
        return vehicleReaders.build(services);
    }

    /** 进世界时建联动模组的定时刻表办法；规矩同物品来源。 */
    public List<TrainScheduler> trainSchedulers(PlayerServices services) {
        return trainSchedulers.build(services);
    }

    /** 进世界时建联动模组的同乘办法；规矩同物品来源。 */
    public List<RideAlong> rideAlongs(PlayerServices services) {
        return rideAlongs.build(services);
    }

    /** 进世界时建联动模组的飞行稳定器读写办法；规矩同物品来源。 */
    public List<FlightStabilizers> flightStabilizers(PlayerServices services) {
        return flightStabilizers.build(services);
    }

    /** 进世界时建联动模组的物理结构工地；规矩同物品来源。 */
    public List<StructureWorksites> structureWorksites(PlayerServices services) {
        return structureWorksites.build(services);
    }

    /** 进世界时建联动模组的挖掘加速；规矩同物品来源。 */
    public List<BreakAccelerator> breakAccelerators(PlayerServices services) {
        return breakAccelerators.build(services);
    }

    /** 进世界时建联动模组的拆卸工具；规矩同物品来源。 */
    public List<DismantleTool> dismantleTools(PlayerServices services) {
        return dismantleTools.build(services);
    }

    /** 认得出哪些界面：原版加上联动模组证明过的。 */
    public MenuLayouts menuLayouts() {
        return new MenuLayouts(menuLayouts.ready());
    }

    /** 联动模组提供的知识来源。 */
    public List<KnowledgeSource> knowledgeSources() {
        return knowledgeSources.ready();
    }

    /** 联动模组提供的配方查看器，按联动清单的顺序；配方查询自己决定先问谁。 */
    public List<RecipeViewer> recipeViewers() {
        return recipeViewers.ready();
    }

    /** 联动模组的放置规则，按联动清单的顺序；施工取第一个非空的答案。 */
    public List<PlacementRules> placementRules() {
        return placementRules.ready();
    }

    /** 联动模组的观察：场景每次刷新时问一遍。 */
    public List<ModSight> modSights() {
        return modSights.ready();
    }

    /** 联动模组刹住一掉的办法，按联动清单的顺序；落地防护先问它们。 */
    public List<FallBrake> fallBrakes() {
        return fallBrakes.ready();
    }

    /** 联动模组登记的落地补料来源：身上没带落地料时按顺序问；都没有就当取不到。 */
    public List<LandingMaterials> landingMaterials() {
        return landingMaterials.ready();
    }

    /** 联动模组登记的走路前要收起来的东西：走路每刻推进前问一遍。 */
    public List<StowBeforeWalking> stowsBeforeWalking() {
        return stowsBeforeWalking.ready();
    }

    /** 联动模组登记的能直接飞上高处的装备：寻路算路时问它能飞多高。 */
    public List<FlyUpGear> flyUpGears() {
        return flyUpGears.ready();
    }

    /** 联动模组的障碍来源：进世界时交给寻路。 */
    public List<ObstacleSource> obstacleSources() {
        return obstacleSources.ready();
    }

    /** 联动模组的"扔进流体里会变"的配方表：拿东西为每一份建一条途径。 */
    public List<FluidTransforms> fluidTransforms() {
        return fluidTransforms.ready();
    }

    /** 联动模组的远程武器，按联动清单的顺序。 */
    public List<RangedWeapon> rangedWeapons() {
        return rangedWeapons.ready();
    }

    /** 联动模组声明的只读操作：启动时登记进客户端的请求路由。 */
    public List<ClientOperation> serverReads() {
        return serverReads.ready();
    }

    /** 清单每一行的结论，和日志里写的一样；调试面板与测试看它。 */
    public List<String> decisions() {
        return Collections.unmodifiableList(decisions);
    }
}
