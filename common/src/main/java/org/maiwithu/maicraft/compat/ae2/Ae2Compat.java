// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;
import org.maiwithu.maicraft.behavior.interaction.spi.DismantleTool;
import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ExternalKeyWrappers;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2Advanced;
import org.maiwithu.maicraft.compat.ae2.advanced.Ae2AdvancedReads;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2MachineReads;
import org.maiwithu.maicraft.compat.ae2.machine.Ae2Machines;
import org.maiwithu.maicraft.compat.ae2.part.Ae2PartControl;
import org.maiwithu.maicraft.compat.ae2.tool.Ae2ToolReads;
import org.maiwithu.maicraft.compat.ae2.tool.Ae2Tools;
import org.maiwithu.maicraft.compat.ae2.tool.NetworkToolboxSource;
import org.maiwithu.maicraft.compat.ae2.tool.PortableCellSource;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeSource;

/**
 * 应用能源2（AE2）的联动入口。角色从附近的 ME 终端取网络里已有的东西，要经这里碰模组：
 * 找终端、核对点开的界面、读网络存货、发取货操作，每一下都经联动入口包一层，
 * 装的 AE2 和编译时用的对不上时整个联动停用，不让 LinkageError 漏出去。
 *
 * <p>交给登记表的：ME 终端界面的布局证明（终端能像原版界面一样打开、读角色那一侧）、
 * ME 终端来源的建法——来源要用的保护判断、靠近与交互是进世界后才有的，进世界时登记表带着它们建来源；
 * 每次进世界建一份新的"上次看到的网络存货"，换世界、重开游戏都从没看过开始。
 * 机器这一半：每种 ME 设备的机器类型（线缆格还管接线缆、装部件）、ME 网络读取器，以及要向服务端问的只读操作 ae2.network。
 * 拿东西还多一条途径 ae2_transform：AE2 的转化配方（往水里扔充能石英、红石、下界石英变福鲁伊克斯水晶）交给玩家行为层去扔。
 */
public final class Ae2Compat extends CompatModule {

    public static final String MOD_ID = "ae2";
    /** 问 ME 网络汇总的服务端只读操作：服务器装了 MaiCraft 的 AE2 读数才答。 */
    public static final String NETWORK_OPERATION = "ae2.network";

    private final Ae2Terminals terminals;
    private final Ae2TerminalMenu menus;
    private final Ae2Grid grid;
    private final Ae2Patterns patterns;
    private final Ae2Transforms transforms;
    private final Ae2Inscribers inscribers;
    private final Ae2MenuSlots menuSlots;
    private final Ae2MachineReads machineReads;
    private final Ae2PartControl partControl;
    private final Ae2ToolReads toolReads;
    private final Ae2AdvancedReads advancedReads;
    private final ExternalKeyWrappers externalKeys;
    private final Ae2DeviceOpsRouter router;
    /** 进世界时记下当前世界的玩家行为服务，供落地补料、服务端只读通道与放置转正规则实时取用；没进世界时为空。 */
    private volatile PlayerServices services;
    /**
     * 上次在各台终端、身上无线终端里看到的网络存货与连没连上：取货报价、存东西挑终端、落地补料记的是同一份，
     * 存东西时看到哪台没连上，下次取货也知道。只在这次启动内有效。
     */
    private final SeenNetworkStock seen = new SeenNetworkStock();

    /** 没有样板读写端时：认不出样板，样板供应器的界面也不认。 */
    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid) {
        this(terminals, menus, grid, Ae2Patterns.NONE);
    }

    /** 没有转化配方读写端时：往水里扔料这条途径给不了。 */
    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid, Ae2Patterns patterns) {
        this(terminals, menus, grid, patterns, Ae2Transforms.NONE);
    }

    /** 没有压印器读写端时：读不出压印器的输出槽。 */
    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid, Ae2Patterns patterns,
            Ae2Transforms transforms) {
        this(terminals, menus, grid, patterns, transforms, Ae2Inscribers.NONE);
    }

    /** 没有界面语义读写端时：压印器退回固定 8 槽判定，通用语义查槽与发包为空实现。 */
    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid, Ae2Patterns patterns,
            Ae2Transforms transforms, Ae2Inscribers inscribers) {
        this(terminals, menus, grid, patterns, transforms, inscribers, Ae2MenuSlots.NONE);
    }

    /** 没有工作方块、线缆部件、手持工具与高级机制读写端时，默认回落各自空实现。 */
    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid, Ae2Patterns patterns,
            Ae2Transforms transforms, Ae2Inscribers inscribers, Ae2MenuSlots menuSlots) {
        this(terminals, menus, grid, patterns, transforms, inscribers, menuSlots,
                Ae2MachineReads.NONE, Ae2PartControl.NONE, Ae2ToolReads.NONE, Ae2AdvancedReads.NONE);
    }

    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid, Ae2Patterns patterns,
            Ae2Transforms transforms, Ae2Inscribers inscribers, Ae2MenuSlots menuSlots,
            Ae2MachineReads machineReads, Ae2PartControl partControl, Ae2ToolReads toolReads,
            Ae2AdvancedReads advancedReads) {
        this(terminals, menus, grid, patterns, transforms, inscribers, menuSlots,
                machineReads, partControl, toolReads, advancedReads, ExternalKeyWrappers.NONE);
    }

    public Ae2Compat(Ae2Terminals terminals, Ae2TerminalMenu menus, Ae2Grid grid, Ae2Patterns patterns,
            Ae2Transforms transforms, Ae2Inscribers inscribers, Ae2MenuSlots menuSlots,
            Ae2MachineReads machineReads, Ae2PartControl partControl, Ae2ToolReads toolReads,
            Ae2AdvancedReads advancedReads, ExternalKeyWrappers externalKeys) {
        super(MOD_ID, "应用能源2");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.grid = Objects.requireNonNull(grid, "grid");
        this.patterns = Objects.requireNonNull(patterns, "patterns");
        this.transforms = Objects.requireNonNull(transforms, "transforms");
        this.inscribers = Objects.requireNonNull(inscribers, "inscribers");
        this.menuSlots = Objects.requireNonNull(menuSlots, "menuSlots");
        this.machineReads = Objects.requireNonNull(machineReads, "machineReads");
        this.partControl = Objects.requireNonNull(partControl, "partControl");
        this.toolReads = Objects.requireNonNull(toolReads, "toolReads");
        this.advancedReads = Objects.requireNonNull(advancedReads, "advancedReads");
        this.externalKeys = Objects.requireNonNull(externalKeys, "externalKeys");
        this.router = new Ae2DeviceOpsRouter(this, this.machineReads, this.partControl, this.advancedReads);
    }

    /** 查某附属外部键类型（如 {@code appmek:chemical}）此刻是否已由对应联动模组登记且可用。 */
    public boolean supportsExternalKeyType(String keyTypeId) {
        return externalKeys.supports(keyTypeId);
    }

    @Override public void contribute(CompatRegistry registry) {
        Ae2Machines machines = new Ae2Machines(this, machineReads);
        Ae2Tools tools = new Ae2Tools(this, toolReads);
        contributeLayouts(registry, machines, tools);
        contributeSourcesAndStorage(registry, machines, tools);
        contributeMachinesAndNetwork(registry, machines);
        contributeToolsAndKnowledge(registry, machines, tools);
    }

    // 登记既有 6 份专属布局证明与四路子模块汇总后的 21 个通用语义布局规则，彼此零碰撞。
    private void contributeLayouts(CompatRegistry registry, Ae2Machines machines, Ae2Tools tools) {
        registry.menuLayout(this, new Ae2TerminalLayout());
        registry.menuLayout(this, new Ae2PatternProviderLayout(this));
        registry.menuLayout(this, new Ae2AccessPointLayout());
        registry.menuLayout(this, new Ae2InscriberLayout(this));
        registry.menuLayout(this, new Ae2DriveLayout());
        registry.menuLayout(this, new Ae2SkyChestLayout());
        registry.menuLayout(this, new Ae2SemanticsLayout(this, Ae2DeviceOpsRouter.allLayoutRules(machines, tools)));
    }

    // 登记终端取货、压印器取占槽物、合成终端手搓、便携元件与工具箱、奇点爆炸转化、网络存储、随身背包与落地补料。
    private void contributeSourcesAndStorage(CompatRegistry registry, Ae2Machines machines, Ae2Tools tools) {
        registry.itemSource(this, services -> new Ae2TerminalSource(this, remember(services),
                new MenuTerminalTakes(this, services, seen, Instant::now), seen, Instant::now));
        registry.itemSource(this, services -> new Ae2InscriberSource(this, remember(services)));
        registry.itemSource(this, services -> firstOf(machines.itemSources(remember(services))));
        registry.itemSource(this, services -> new PortableCellSource(this, toolReads, remember(services)));
        registry.itemSource(this, services -> new NetworkToolboxSource(this, toolReads, remember(services)));
        registry.itemSource(this, services -> firstOf(Ae2Advanced.itemSources(advancedReads, remember(services))));
        registry.carriedBackpack(this, services -> tools.carriedBackpacks(remember(services)).getFirst());
        registry.networkStorage(this, services -> new MenuNetworkStores(this, remember(services), seen, Instant::now));
        registry.landingMaterial(this, new Ae2LandingMaterials(this, this::services));
        registry.fluidTransforms(this, new Ae2FluidTransforms(this));
    }

    // 登记全部 25 种 AE2 设备（共享本世界设备委托表与线缆格双路部件委托）、ME 网络读取器及两项服务端只读操作。
    private void contributeMachinesAndNetwork(CompatRegistry registry, Ae2Machines machines) {
        for (Ae2MachineCatalog.Kind kind : Ae2DeviceOpsRouter.allKinds(machines)) {
            registry.machineType(this, services -> new Ae2MachineType(kind, this, inscribers, remember(services),
                    blocksOf(kind.blocks()), router.deviceOpsFor(services)));
        }
        registry.networkReader(this, services -> new MeNetworkReader(this, remember(services)));
        registry.serverRead(this, NETWORK_OPERATION, 1);
        registry.serverRead(this, Ae2Advanced.DEVICE_OPERATION, Ae2Advanced.DEVICE_OPERATION_VERSION);
    }

    // 登记拆解工具、放置转正规则（含随身石英扳手上下文）、手持工具设置、物质炮、陨石罗盘感知与知识来源。
    private void contributeToolsAndKnowledge(CompatRegistry registry, Ae2Machines machines, Ae2Tools tools) {
        for (Function<PlayerServices, DismantleTool> factory : tools.dismantleTools()) {
            registry.dismantleTool(this, services -> factory.apply(remember(services)));
        }
        for (PlacementRules rule : machines.placementRules()) {
            registry.placementRules(this, rule);
        }
        for (PlacementRules rule : tools.placementRules(this::playerContext)) {
            registry.placementRules(this, rule);
        }
        registry.itemSettings(this, services -> Ae2DeviceOpsRouter.composeItemSettings(
                tools.itemSettings(remember(services))));
        for (RangedWeapon weapon : tools.rangedWeapons()) {
            registry.rangedWeapon(this, weapon);
        }
        for (ModSight sight : tools.modSights()) {
            registry.modSight(this, sight);
        }
        registry.knowledgeSource(this, new Ae2MachineKnowledge());
        for (KnowledgeSource source : tools.knowledgeSources()) {
            registry.knowledgeSource(this, source);
        }
    }

    private PlayerServices remember(PlayerServices current) {
        this.services = current;
        return current;
    }

    private PlayerContext playerContext() {
        PlayerServices current = services;
        return current == null ? null : current.context().get();
    }

    /** 当前世界的服务端只读通道；尚未进入世界时回落为空实现。 */
    ServerReads serverReads() {
        PlayerServices current = services;
        return current == null ? ServerReads.NONE : current.serverReads();
    }

    private static ItemSource firstOf(List<ItemSource> sources) {
        return sources.getFirst();
    }

    /** 这个世界里泡在流体里的转化配方；见 {@link Ae2Transforms#fluidRecipes}。 */
    public List<Ae2Transforms.Recipe> transformRecipes(Level level) {
        return call("读 AE2 的转化配方", () -> transforms.fluidRecipes(level));
    }

    /** 进世界时物品来源建好记下的那一份；没进世界时为空。落地补料拿它开无线终端。 */
    PlayerServices services() {
        return services;
    }

    /** 记网络存货的那一份；见 {@link #seen}。 */
    SeenNetworkStock seenStock() {
        return seen;
    }

    /** 注册 ID 换成方块对象；注册表里没有的不算进来。 */
    static Set<Block> blocksOf(List<String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null) BuiltInRegistries.BLOCK.getOptional(key).ifPresent(blocks::add);
        }
        return Set.copyOf(blocks);
    }

    /** 一格 ME 设备的读数；见 {@link Ae2Grid#cell}。 */
    public Optional<Ae2Grid.GridCell> gridCell(ClientLevel level, BlockPos at) {
        return call("读 ME 设备", () -> grid.cell(level, at));
    }

    /** 一格 ME 驱动器客户端同步到的十槽元件与指示灯状态；见 {@link Ae2Grid#driveSlots}。 */
    public Optional<Ae2Grid.DriveSlots> driveSlots(ClientLevel level, BlockPos at) {
        return call("读 ME 驱动器的十个槽位", () -> grid.driveSlots(level, at));
    }

    /** 一格压印器的输出槽；见 {@link Ae2Inscribers#output}。 */
    public Optional<List<MachineState.Shown>> inscriberOutput(ClientLevel level, BlockPos at) {
        return call("读压印器的输出槽", () -> inscribers.output(level, at));
    }

    /** 一格压印器的前三个投入工作格（顶模板、底模板、中间输入）；见 {@link Ae2Inscribers#inputs}。 */
    public Optional<Ae2Inscribers.Inputs> inscriberInputs(ClientLevel level, BlockPos at) {
        return call("读压印器的投入格", () -> inscribers.inputs(level, at));
    }

    /** 一格压印器客户端同步到的全部四个工作格（顶、底、中间、输出）物品与数量；见 {@link Ae2Inscribers#slots}。 */
    public Optional<Ae2Inscribers.Slots> inscriberSlots(ClientLevel level, BlockPos at) {
        return call("读压印器的四个工作格", () -> inscribers.slots(level, at));
    }

    /** 读写端直接提供的附近压印器位置（供测试替身注入）；见 {@link Ae2Inscribers#near}。 */
    public List<BlockPos> inscribersNear(ClientLevel level, BlockPos center, int radius) {
        return call("找附近的压印器", () -> inscribers.near(level, center, radius));
    }

    /** 是不是 AE2 的部件；见 {@link Ae2Grid#partItem}。 */
    public boolean partItem(String itemId) {
        return call("认 AE2 部件", () -> grid.partItem(itemId));
    }

    /** 是不是 AE2 的线缆；见 {@link Ae2Grid#cableItem}。 */
    public boolean cableItem(String itemId) {
        return call("认 AE2 线缆", () -> grid.cableItem(itemId));
    }

    /** 角色周围已加载区块里装着 ME 终端的面；见 {@link Ae2Terminals#near}。 */
    public List<Ae2Terminals.Terminal> terminalsNear(ClientLevel level, BlockPos center, int radius) {
        return call("找附近的 ME 终端", () -> terminals.near(level, center, radius));
    }

    /** 这一格这一面还装着 ME 终端没有；见 {@link Ae2Terminals#stillThere}。 */
    public boolean terminalStillThere(ClientLevel level, BlockPos block, Direction side) {
        return call("核对 ME 终端还在不在", () -> terminals.stillThere(level, block, side));
    }

    /** 这件物品是不是绑过无线访问点的无线终端；见 {@link Ae2Terminals#linkedWirelessTerminal}。 */
    public boolean linkedWirelessTerminal(ItemStack stack) {
        return call("认无线终端", () -> terminals.linkedWirelessTerminal(stack));
    }

    /** 这件物品是不是无线终端（绑没绑过不算）；见 {@link Ae2Terminals#wirelessTerminal}。 */
    public boolean wirelessTerminal(ItemStack stack) {
        return call("认无线终端", () -> terminals.wirelessTerminal(stack));
    }

    /** 角色周围已加载区块里的无线访问点；见 {@link Ae2Terminals#accessPointsNear}。 */
    public List<Ae2Terminals.AccessPoint> accessPointsNear(ClientLevel level, BlockPos center, int radius) {
        return call("找附近的无线访问点", () -> terminals.accessPointsNear(level, center, radius));
    }

    /**
     * 身上（主背包、快捷栏、护甲栏、副手）第一件还没绑定访问点的无线终端的物品 ID；没带给空。
     * 绑定动作拿它认原料；绑过的走 {@link #carriedWirelessTerminal} 那条路。
     */
    public Optional<String> carriedUnboundWirelessTerminal(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && wirelessTerminal(stack) && !linkedWirelessTerminal(stack)) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return Optional.empty();
    }

    /**
     * 身上（主背包、快捷栏、护甲栏、副手）第一件绑过访问点的无线终端的物品 ID；没带给空。
     * 取货、存东西、落地补料认"身上有没有无线终端"都问这一处，翻背包的顺序只写一份。
     */
    public Optional<String> carriedWirelessTerminal(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && linkedWirelessTerminal(stack)) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return Optional.empty();
    }

    /** 点开的界面是不是身上的无线终端打开的；见 {@link Ae2TerminalMenu#wirelessMenu}。 */
    public boolean wirelessMenu(AbstractContainerMenu menu) {
        return call("核对点开的是不是无线终端", () -> menus.wirelessMenu(menu));
    }

    /** 点开的界面是不是一台无线访问点的；见 {@link Ae2TerminalMenu#accessPointMenu}。 */
    public boolean accessPointMenu(AbstractContainerMenu menu) {
        return call("核对点开的是不是无线访问点", () -> menus.accessPointMenu(menu));
    }

    /** 访问点界面里的绑定格；见 {@link Ae2TerminalMenu#bindingInputSlot}。 */
    public Optional<Integer> bindingInputSlot(AbstractContainerMenu menu) {
        return call("认访问点界面的绑定格", () -> menus.bindingInputSlot(menu));
    }

    /** 访问点界面里的产出格；见 {@link Ae2TerminalMenu#bindingOutputSlot}。 */
    public Optional<Integer> bindingOutputSlot(AbstractContainerMenu menu) {
        return call("认访问点界面的产出格", () -> menus.bindingOutputSlot(menu));
    }

    /** 终端没连上网络时界面上写的原因；见 {@link Ae2TerminalMenu#unlinkedWhy}。 */
    public Optional<String> unlinkedWhy(AbstractContainerMenu menu) {
        return call("读 ME 终端没连上的原因", () -> menus.unlinkedWhy(menu));
    }

    /** 点开的界面是不是这台终端的；见 {@link Ae2TerminalMenu#belongsTo}。 */
    public boolean menuBelongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
        return call("核对点开的是不是这台 ME 终端", () -> menus.belongsTo(menu, level, block, side));
    }

    /** 终端界面显示网络连没连上；见 {@link Ae2TerminalMenu#linked}。 */
    public Optional<Boolean> networkLinked(AbstractContainerMenu menu) {
        return call("读 ME 终端连没连上网络", () -> menus.linked(menu));
    }

    /** 终端界面里的网络存货；见 {@link Ae2TerminalMenu#stock}。 */
    public Optional<List<Ae2TerminalMenu.StockEntry>> networkStock(AbstractContainerMenu menu) {
        return call("读 ME 终端里的网络存货", () -> menus.stock(menu));
    }

    /** 下单合成这一条；见 {@link Ae2TerminalMenu#requestCraft}。 */
    public void requestCraft(AbstractContainerMenu menu, long serial) {
        run("向 ME 网络下单合成", () -> menus.requestCraft(menu, serial));
    }

    /** 是不是填合成数量的界面；见 {@link Ae2TerminalMenu#craftAmountMenu}。 */
    public boolean craftAmountMenu(AbstractContainerMenu menu) {
        return call("认合成数量界面", () -> menus.craftAmountMenu(menu));
    }

    /** 定下合成数量并请网络开工；见 {@link Ae2TerminalMenu#confirmCraft}。 */
    public void confirmCraft(AbstractContainerMenu menu, int amount) {
        run("定下合成数量", () -> menus.confirmCraft(menu, amount));
    }

    /** 合成确认页此刻的样子；见 {@link Ae2TerminalMenu#craftPlanPage}。 */
    public Optional<Ae2TerminalMenu.CraftPlanPage> craftPlanPage(AbstractContainerMenu menu) {
        return call("读合成确认页", () -> menus.craftPlanPage(menu));
    }

    /** 角色自己下的这种东西的合成作业还没结束；见 {@link Ae2TerminalMenu#craftPending}。 */
    public boolean craftPending(ItemStack sample) {
        return call("读自己下的合成作业", () -> menus.craftPending(sample));
    }

    /** 发一次整组取货；见 {@link Ae2TerminalMenu#takeStack}。 */
    public void takeStack(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端整组取货", () -> menus.takeStack(menu, serial));
    }

    /** 发一次取一件到光标上；见 {@link Ae2TerminalMenu#takeOne}。 */
    public void takeOne(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端取一件", () -> menus.takeOne(menu, serial));
    }

    /** 拿网络里的空桶装一桶流体放进背包；见 {@link Ae2TerminalMenu#fillBucket}。 */
    public void fillBucket(AbstractContainerMenu menu, long serial) {
        run("从 ME 终端装一桶", () -> menus.fillBucket(menu, serial));
    }

    /** 发一次把光标上的东西放回网络；见 {@link Ae2TerminalMenu#putBack}。 */
    public void putBack(AbstractContainerMenu menu) {
        run("把光标上的东西放回 ME 网络", () -> menus.putBack(menu));
    }

    /** 打开着的样板供应器界面里放样板的格号；见 {@link Ae2Patterns#openPatternSlots}。 */
    public Set<Integer> openPatternSlots() {
        return call("认样板供应器的样板格", patterns::openPatternSlots);
    }

    /** 一件样板主要产出的物品；见 {@link Ae2Patterns#patternOutput}。 */
    public Optional<String> patternOutput(ItemStack stack, ClientLevel level) {
        return call("解样板", () -> patterns.patternOutput(stack, level));
    }

    /** 当前角色打开的界面是不是 AE2 菜单；见 {@link Ae2MenuSlots#isAe2MenuOpen}。 */
    public boolean ae2MenuOpen() {
        return call("检查 AE2 界面有没有打开", menuSlots::isAe2MenuOpen);
    }

    /** 当前打开的 AE2 菜单里某种槽位语义对应的槽位编号；见 {@link Ae2MenuSlots#slotsOf}。 */
    public List<Integer> menuSlotsOf(String semanticId) {
        return call("按语义查 AE2 界面槽位", () -> menuSlots.slotsOf(semanticId));
    }

    /** 发送 AE2 配置按钮包；见 {@link Ae2MenuSlots#sendConfigButton}。 */
    public boolean sendConfigButton(String settingName, boolean backwards) {
        return call("切换 AE2 界面配置", () -> menuSlots.sendConfigButton(settingName, backwards));
    }

    /** 给 AE2 幽灵过滤槽设过滤物品；见 {@link Ae2MenuSlots#sendSetFilter}。 */
    public boolean sendSetFilter(int slotIndex, ItemStack stack) {
        return call("设置 AE2 过滤槽", () -> menuSlots.sendSetFilter(slotIndex, stack));
    }

    /** 向当前打开的 AE2 菜单发送无参界面动作；见 {@link Ae2MenuSlots#sendNoArgAction}。 */
    public boolean sendMenuAction(String actionName) {
        return call("发送 AE2 界面动作", () -> menuSlots.sendNoArgAction(actionName));
    }

    /** 向当前打开的 AE2 菜单发送整数参数的界面动作；见 {@link Ae2MenuSlots#sendIntAction}。 */
    public boolean sendMenuIntAction(String actionName, int value) {
        return call("发送 AE2 界面动作", () -> menuSlots.sendIntAction(actionName, value));
    }

    /** 向当前打开的 AE2 菜单发送长整型参数的界面动作；见 {@link Ae2MenuSlots#sendLongAction}。 */
    public boolean sendMenuLongAction(String actionName, long value) {
        return call("发送 AE2 界面动作", () -> menuSlots.sendLongAction(actionName, value));
    }

    /** 向当前打开的 AE2 菜单发送布尔参数的界面动作；见 {@link Ae2MenuSlots#sendBooleanAction}。 */
    public boolean sendMenuBooleanAction(String actionName, boolean value) {
        return call("发送 AE2 界面动作", () -> menuSlots.sendBooleanAction(actionName, value));
    }

    /** 向当前打开的 AE2 菜单发送字符串参数的界面动作；见 {@link Ae2MenuSlots#sendStringAction}。 */
    public boolean sendMenuStringAction(String actionName, String value) {
        return call("发送 AE2 界面动作", () -> menuSlots.sendStringAction(actionName, value));
    }

    /** 向当前打开的 AE2 菜单发送 JSON 载荷的界面动作；见 {@link Ae2MenuSlots#sendRawJsonAction}。 */
    public boolean sendMenuJsonAction(String actionName, String jsonPayload) {
        return call("发送 AE2 界面动作", () -> menuSlots.sendRawJsonAction(actionName, jsonPayload));
    }

    /** 切换到指定注册 ID 的 AE2 子界面；见 {@link Ae2MenuSlots#openSubMenu}。 */
    public boolean openSubMenu(String menuTypeId) {
        return call("打开 AE2 子界面", () -> menuSlots.openSubMenu(menuTypeId));
    }

    /** 从当前 AE2 子界面返回上一级主界面；见 {@link Ae2MenuSlots#returnToParentMenu}。 */
    public boolean returnToParentMenu() {
        return call("返回 AE2 上一级界面", menuSlots::returnToParentMenu);
    }
}
