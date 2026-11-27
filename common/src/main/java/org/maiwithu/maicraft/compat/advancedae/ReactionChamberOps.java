// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 反应仓（advanced_ae:reaction_chamber）核心业务编排：
 * 整合客户端直读、服务端 {@code advanced_ae.device} 读数与开窗缓存；
 * 负责流体原料桶换算（扣减输入槽余量、异种流体冲突报错）、方块直点倒桶与空桶舀液、
 * 九格固体投料与一格成品收货，以及全部配置项读写。
 */
public final class ReactionChamberOps {

    /** 反应仓可接入 ME 网络的种类声明（不依赖 compat.ae2）。 */
    static final NetworkKind ME_NETWORK = new NetworkKind("me", "ME 网络：反应仓六面均可接入 ME 线缆并受电");
    static final List<String> YES_NO = List.of("yes", "no");
    static final List<String> TRUE_FALSE = List.of("true", "false");
    static final List<String> UPGRADE_CHOICES = List.of("ae2:speed_card", "empty");
    static final List<String> RELATIVE_SIDES = List.of("front", "back", "left", "right", "top", "bottom");
    static final String OUTPUT_SIDE_PREFIX = "output_side.";
    static final String UPGRADE_PREFIX = "upgrade.";

    /** 记录哪台反应仓、哪个桶物品在投料阶段走方块直点倒桶以及对应期望流体。 */
    private record BucketFeedKey(BlockPos at, String bucketItemId) {
    }

    /** 反应仓本地会话缓存：记录上次开窗同步到的自动输出、升级卡、清空标志与相对六面输出配置。 */
    private static final class ChamberCache {
        AdvancedAeReads.ChamberMenuView menuView;
        Set<String> outputSides;
        boolean flushedIn;
        boolean flushedOut;
    }

    private final AdvancedAeCompat compat;
    private final PlayerServices services;
    private final AdvancedAeDeviceReadings serverReadings;
    private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;
    private final Function<String, Optional<String>> fluidToBucket;
    private final Function<String, Optional<String>> bucketToFluid;
    private final Map<BlockPos, ChamberCache> caches = new HashMap<>();
    private final Map<BucketFeedKey, String> plannedBucketFluids = new HashMap<>();

    ReactionChamberOps(AdvancedAeCompat compat, PlayerServices services) {
        this(compat, services, new AdvancedAeDeviceReadings(services.serverReads()),
                (at, permissions) -> new ClientMenuOpening(at, permissions, services.menuLayouts(),
                        services.approach(), services.interactions(), services.context()),
                ReactionChamberOps::defaultBucketForFluid,
                ReactionChamberOps::defaultFluidForBucket);
    }

    ReactionChamberOps(AdvancedAeCompat compat, PlayerServices services,
                       AdvancedAeDeviceReadings serverReadings,
                       BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings,
                       Function<String, Optional<String>> fluidToBucket,
                       Function<String, Optional<String>> bucketToFluid) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.serverReadings = Objects.requireNonNull(serverReadings, "serverReadings");
        this.menuOpenings = Objects.requireNonNull(menuOpenings, "menuOpenings");
        this.fluidToBucket = Objects.requireNonNull(fluidToBucket, "fluidToBucket");
        this.bucketToFluid = Objects.requireNonNull(bucketToFluid, "bucketToFluid");
    }

    /** 反应仓进出口：六面全部可连 ME 线缆与受电，另附流体桶直点/流体管道口说明。 */
    List<ExchangePoint> exchangePoints(BlockPos at) {
        List<ExchangePoint> points = new ArrayList<>(Direction.values().length + 1);
        for (Direction side : Direction.values()) {
            points.add(new ExchangePoint(at, side, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                    "这一面可连 ME 线缆并受电（反应仓六面均可连网，单次批量配方耗电较高请配足能源元件）"));
        }
        points.add(new ExchangePoint(at, null, ExchangePoint.Medium.FLUID, ExchangePoint.Flow.BOTH,
                "手持水/岩浆等满桶直接右键反应仓方块即可向输入流体槽灌入 1000 mB（不开界面）；空桶右键可从输出流体槽舀出 1000 mB，也可接相邻流体管道"));
        return List.copyOf(points);
    }

    /** 汇总反应仓运行状态：合并客户端直读、服务端权威只读与本地开窗缓存。 */
    MachineState state(BlockPos at, ClientLevel level) {
        Optional<AdvancedAeReads.ChamberView> clientOpt = compat.chamberAt(level, at);
        Optional<JsonObject> serverOpt = serverReadings.latest(at);
        ChamberCache cache = caches.get(at.immutable());
        if (clientOpt.isEmpty() && serverOpt.isEmpty() && cache == null) {
            return MachineState.unknown("这一格读不到反应仓（没加载，或已经不是反应仓）");
        }
        Map<String, Object> readings = new LinkedHashMap<>();
        boolean working = resolveWorking(clientOpt, serverOpt);
        Boolean powered = resolvePowered(clientOpt, serverOpt);
        Boolean active = resolveActive(clientOpt, serverOpt);
        readings.put("working", working);
        if (powered != null) readings.put("powered", powered);
        if (active != null) readings.put("active", active);
        fillFluidReadings(readings, clientOpt, serverOpt, cache);
        Double progress = fillProgressAndEnergy(readings, serverOpt, cache);
        fillConfigReadings(readings, serverOpt, cache);
        List<MachineState.Shown> shown = fillItemReadings(readings, clientOpt, serverOpt);
        // 反应仓无手动开关，空闲一律报 IDLE（不报 OFFLINE 以免 machine_run 误把方块当开关右键）。
        MachineState.Activity activity = working ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        String note = buildStateNote(powered, active, readings, serverOpt.isPresent());
        return new MachineState(activity, null, progress, shown, readings, note);
    }

    /** 计算投料计划：先处理配方里的流体原料（余量扣减、异种流体冲突拦截），再接固体原料。 */
    public FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
        List<FeedPlan.Entry> converted = new ArrayList<>();
        List<FeedPlan.Entry> main = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Optional<String> conflict = Optional.empty();
        AdvancedAeReads.TankSlot inputTank = knownInputTank(at);
        for (ShownIngredient ingredient : recipe.inputs()) {
            Optional<WantedItem> wanted = MachineFeeding.wantedItemOf(ingredient);
            if (wanted.isPresent()) {
                long count = ingredient.amount() * batches;
                if (count > 0) {
                    main.add(new FeedPlan.Entry(wanted.get().specifier(),
                            (int) Math.min(Integer.MAX_VALUE, count), false));
                }
                continue;
            }
            Optional<String> fluidOpt = firstFluidId(ingredient);
            if (fluidOpt.isEmpty()) {
                notes.add("配方还要反应仓无法直接带入的原料（" + MachineFeeding.describe(ingredient) + "）");
                continue;
            }
            String fluidId = fluidOpt.get();
            long neededMb = ingredient.amount() * batches;
            if (!inputTank.isEmpty() && !inputTank.fluidId().equals(fluidId)) {
                conflict = Optional.of("反应仓输入流体槽（槽 1）当前存有异种流体 " + inputTank.fluidId() + "（"
                        + inputTank.amountMb() + " mB），与配方所需 " + fluidId
                        + " 冲突；请先用 machine_configure 设置 flush_fluid_in=true 清空输入流体槽");
                continue;
            }
            if (!inputTank.isEmpty() && inputTank.amountMb() >= neededMb) {
                notes.add("反应仓输入流体槽已有 " + inputTank.amountMb() + " mB " + fluidId
                        + "，已满足本次所需 " + neededMb + " mB，跳过备桶");
                continue;
            }
            Optional<IngredientFeed> feed = ingredientFeed(at, ingredient, neededMb);
            if (feed.isPresent()) {
                notes.add(feed.get().note());
                converted.add(new FeedPlan.Entry(feed.get().itemId(), feed.get().count(), false));
                plannedBucketFluids.put(new BucketFeedKey(at.immutable(), feed.get().itemId()), fluidId);
            } else {
                notes.add("配方需要流体 " + fluidId + "（" + neededMb + " mB），找不到对应流体桶物品，请经流体管道接入");
            }
        }
        List<FeedPlan.Entry> entries = new ArrayList<>(converted.size() + main.size());
        entries.addAll(converted);
        entries.addAll(main);
        return new FeedPlan(entries, notes, conflict, Optional.empty());
    }

    /** 将流体原料扣减输入槽同种流体余量后按 Fluid.getBucket() 换算为标准桶物品。 */
    public Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amountMb) {
        Optional<String> fluidOpt = firstFluidId(ingredient);
        if (fluidOpt.isEmpty() || amountMb <= 0L) return Optional.empty();
        String fluidId = fluidOpt.get();
        AdvancedAeReads.TankSlot inputTank = knownInputTank(at);
        if (!inputTank.isEmpty() && !inputTank.fluidId().equals(fluidId)) {
            return Optional.empty();
        }
        long existingSame = !inputTank.isEmpty() && inputTank.fluidId().equals(fluidId) ? inputTank.amountMb() : 0L;
        int buckets = bucketsNeeded(amountMb, existingSame);
        if (buckets <= 0) return Optional.empty();
        Optional<String> bucketItem = fluidToBucket.apply(fluidId);
        if (bucketItem.isEmpty()) return Optional.empty();
        String note = existingSame > 0
                ? "配方需 " + amountMb + " mB " + fluidId + "，反应仓输入槽已有 " + existingSame
                        + " mB，还需备 " + buckets + " 桶 " + bucketItem.get() + " 直接右键方块灌入"
                : "配方需 " + amountMb + " mB " + fluidId + "，按每桶 1000 mB 备 " + buckets
                        + " 桶 " + bucketItem.get() + " 直接右键反应仓方块灌入";
        plannedBucketFluids.put(new BucketFeedKey(at.immutable(), bucketItem.get()), fluidId);
        return Optional.of(new IngredientFeed(bucketItem.get(), buckets, note));
    }

    /** 投料：流体桶走方块直点倒桶动作，固体原料走九格输入 MenuFeed。 */
    Optional<Action> feed(BlockPos at, FeedPlan.Entry entry, Permissions permissions) {
        String plannedFluid = plannedBucketFluids.remove(new BucketFeedKey(at.immutable(), entry.item()));
        if (plannedFluid != null || isFluidBucketItem(entry.item())) {
            String expectedFluid = plannedFluid != null ? plannedFluid
                    : bucketToFluid.apply(entry.item()).orElse("");
            return Optional.of(new ReactionChamberBucketFeed(at, entry.item(), expectedFluid, entry.count(),
                    permissions, services, () -> Optional.of(knownInputTank(at))));
        }
        return Optional.of(new MenuFeed(services, "反应仓", at, AdvancedAeMachineCatalog.REACTION_CHAMBER,
                entry.item(), entry.count(), permissions));
    }

    /** 出口快照：同步的 1 格输出物品槽，加上优先取服务端权威读数的输出流体槽折算桶数。 */
    List<MachineState.Shown> output(BlockPos at, ClientLevel level) {
        List<MachineState.Shown> out = new ArrayList<>();
        compat.chamberAt(level, at).ifPresent(view -> out.addAll(view.outputs()));
        AdvancedAeReads.TankSlot outFluid = knownOutputTank(at);
        if (!outFluid.isEmpty() && outFluid.amountMb() >= ReactionChamberBucketFeed.BUCKET_MB) {
            fluidToBucket.apply(outFluid.fluidId()).ifPresent(bucketId ->
                    out.add(new MachineState.Shown(bucketId, (int) (outFluid.amountMb() / ReactionChamberBucketFeed.BUCKET_MB))));
        }
        return List.copyOf(out);
    }

    /** 收货：目标为流体产物桶时手持空桶直点反应仓舀取，否则经 MenuOutputLook 从输出槽取走固体成品。 */
    Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        Optional<String> fluid = bucketToFluid.apply(itemId);
        if (fluid.isPresent()) {
            return Optional.of(ReactionChamberBucketFeed.scoop(at, itemId, fluid.get(), count,
                    permissions, services, () -> Optional.of(knownOutputTank(at))));
        }
        return Optional.of(new MenuOutputLook(itemId, true, menuOpenings.apply(at, permissions), services.context()));
    }

    /** 流体产物桶目标（如量子灌注液桶）走整段运行：等出液后备空桶直点反应仓舀取。 */
    Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
        if (request == null || request.item() == null || request.file() != null) return Optional.empty();
        Optional<String> targetFluid = bucketToFluid.apply(request.item());
        if (targetFluid.isEmpty()) return Optional.empty();
        AdvancedAeReads.TankSlot outFluid = knownOutputTank(at);
        boolean outputMatches = !outFluid.isEmpty() && outFluid.fluidId().equals(targetFluid.get());
        boolean advancedFluidBucket = request.item().startsWith("advanced_ae:");
        if (!outputMatches && !advancedFluidBucket) return Optional.empty();
        return Optional.of(ReactionChamberBucketFeed.fluidBucketRun(at, request, targetFluid.get(),
                permissions, services, () -> liveChamberView(at)));
    }

    /** 列出反应仓全部支持的配置项与当前已知值。 */
    public List<MachineSetting> settings(BlockPos at) {
        Optional<JsonObject> serverOpt = serverReadings.latest(at);
        ChamberCache cache = caches.get(at.immutable());
        AdvancedAeReads.TankSlot inTank = knownInputTank(at);
        AdvancedAeReads.TankSlot outTank = knownOutputTank(at);
        List<MachineSetting> list = new ArrayList<>(13);
        String autoExport = resolveAutoExport(serverOpt, cache);
        list.add(new MachineSetting("auto_export", autoExport, YES_NO,
                "自动输出：yes 时成品自动推向已允许的输出面"));
        List<String> upgrades = resolveUpgrades(serverOpt, cache);
        for (int i = 0; i < AdvancedAeReads.UPGRADE_SLOT_COUNT; i++) {
            String value = i < upgrades.size() ? upgrades.get(i) : "unknown";
            list.add(new MachineSetting(UPGRADE_PREFIX + i, value, UPGRADE_CHOICES,
                    "反应仓第 " + i + " 号升级槽（最多 4 张 ae2:speed_card 加速卡）"));
        }
        String flushedInVal = inTank.isEmpty() || (cache != null && cache.flushedIn) ? "true" : "false";
        String flushedOutVal = outTank.isEmpty() || (cache != null && cache.flushedOut) ? "true" : "false";
        list.add(new MachineSetting("flush_fluid_in", flushedInVal, List.of("true"),
                "清空输入流体槽（槽 1）：设为 true 清掉残留输入流体"));
        list.add(new MachineSetting("flush_fluid_out", flushedOutVal, List.of("true"),
                "清空输出流体槽（槽 0）：设为 true 清掉残留输出流体"));
        Optional<Set<String>> sides = resolveOutputSides(serverOpt, cache);
        for (String side : RELATIVE_SIDES) {
            String val = sides.map(set -> set.contains(side) ? "true" : "false").orElse("unknown");
            list.add(new MachineSetting(OUTPUT_SIDE_PREFIX + side, val, TRUE_FALSE,
                    "相对输出面 " + side + " 是否允许自动输出（经输出方向子菜单配置）"));
        }
        return List.copyOf(list);
    }

    /** 根据配置键分派对应配置动作。 */
    public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        if (key == null || value == null) return Optional.empty();
        BlockPos pos = at.immutable();
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        if ("auto_export".equals(normalizedKey)) {
            if (!YES_NO.contains(normalizedValue)) return Optional.empty();
            return Optional.of(new ReactionChamberConfigActions.AutoExportAction(
                    menuOpenings.apply(pos, permissions), compat, normalizedValue,
                    view -> recordMenuView(pos, view)));
        }
        if ("flush_fluid_in".equals(normalizedKey)) {
            if (!"true".equals(normalizedValue)) return Optional.empty();
            return Optional.of(new ReactionChamberConfigActions.FlushFluidAction(
                    menuOpenings.apply(pos, permissions), compat, true,
                    serverReadings::available,
                    () -> serverTankOf(pos, true),
                    () -> serverReadings.invalidate(pos),
                    view -> recordMenuView(pos, view),
                    () -> markTankFlushed(pos, true)));
        }
        if ("flush_fluid_out".equals(normalizedKey)) {
            if (!"true".equals(normalizedValue)) return Optional.empty();
            return Optional.of(new ReactionChamberConfigActions.FlushFluidAction(
                    menuOpenings.apply(pos, permissions), compat, false,
                    serverReadings::available,
                    () -> serverTankOf(pos, false),
                    () -> serverReadings.invalidate(pos),
                    view -> recordMenuView(pos, view),
                    () -> markTankFlushed(pos, false)));
        }
        if (normalizedKey.startsWith(OUTPUT_SIDE_PREFIX)) {
            String side = normalizedKey.substring(OUTPUT_SIDE_PREFIX.length());
            if (!RELATIVE_SIDES.contains(side) || !TRUE_FALSE.contains(normalizedValue)) return Optional.empty();
            return Optional.of(new ReactionChamberConfigActions.OutputSideAction(
                    menuOpenings.apply(pos, permissions), compat, side,
                    Boolean.parseBoolean(normalizedValue),
                    sides -> cacheOf(pos).outputSides = Set.copyOf(sides)));
        }
        int upgradeSlot = parseUpgradeSlot(normalizedKey);
        if (upgradeSlot < 0) return Optional.empty();
        boolean remove = "empty".equals(normalizedValue) || "out".equals(normalizedValue);
        if (!remove && !"ae2:speed_card".equals(normalizedValue)) return Optional.empty();
        return Optional.of(new ReactionChamberConfigActions.UpgradeSlotAction(
                menuOpenings.apply(pos, permissions), compat, upgradeSlot,
                remove ? null : "ae2:speed_card",
                view -> recordMenuView(pos, view)));
    }

    /** 计算补齐 totalNeededMb 所需桶数：扣减同种余量后向上取整（每桶 1000 mB）。 */
    public static int bucketsNeeded(long totalNeededMb, long existingSameFluidMb) {
        long missing = totalNeededMb - Math.max(0L, existingSameFluidMb);
        if (missing <= 0L) return 0;
        long buckets = (missing + ReactionChamberBucketFeed.BUCKET_MB - 1L) / ReactionChamberBucketFeed.BUCKET_MB;
        return (int) Math.min(Integer.MAX_VALUE, buckets);
    }

    /** 经游戏注册表 Fluid.getBucket() 解析流体对应的标准桶物品注册 ID。 */
    public static Optional<String> defaultBucketForFluid(String fluidId) {
        ResourceLocation key = ResourceLocation.tryParse(fluidId);
        if (key == null) return Optional.empty();
        Optional<Fluid> fluid = BuiltInRegistries.FLUID.getOptional(key);
        if (fluid.isEmpty()) return Optional.empty();
        Item bucket = fluid.get().getBucket();
        if (bucket == null || bucket == Items.AIR) return Optional.empty();
        return Optional.of(BuiltInRegistries.ITEM.getKey(bucket).toString());
    }

    /** 反向查找某个桶物品对应的源流体注册 ID（空桶返回空）。 */
    public static Optional<String> defaultFluidForBucket(String itemId) {
        if (itemId == null || itemId.isBlank() || ReactionChamberBucketFeed.EMPTY_BUCKET_ID.equals(itemId)) {
            return Optional.empty();
        }
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        if (key == null) return Optional.empty();
        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(key);
        if (itemOpt.isEmpty() || itemOpt.get() == Items.AIR) return Optional.empty();
        Item target = itemOpt.get();
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid.getBucket() == target && fluid.isSource(fluid.defaultFluidState())) {
                return Optional.of(BuiltInRegistries.FLUID.getKey(fluid).toString());
            }
        }
        return Optional.empty();
    }

    private boolean isFluidBucketItem(String itemId) {
        return bucketToFluid.apply(itemId).isPresent();
    }

    private Optional<AdvancedAeReads.ChamberView> currentChamberView(BlockPos at) {
        PlayerContext ctx = services.context().get();
        ClientLevel level = ctx == null ? null : ctx.level();
        return compat.chamberAt(level, at);
    }

    // 合并客户端实时工作态与服务端优先的双储罐读数，供流体产物桶 FullRun 使用。
    private Optional<AdvancedAeReads.ChamberView> liveChamberView(BlockPos at) {
        Optional<AdvancedAeReads.ChamberView> client = currentChamberView(at);
        if (client.isEmpty()) return Optional.empty();
        AdvancedAeReads.ChamberView v = client.get();
        return Optional.of(new AdvancedAeReads.ChamberView(
                v.working(), v.powered(), v.active(), v.front(),
                knownInputTank(at), knownOutputTank(at), v.inputs(), v.outputs()));
    }

    // 储罐读数优先取服务端 advanced_ae.device 权威值，避免客户端 fluidInv 未触发 markForUpdate 时滞后覆盖。
    private AdvancedAeReads.TankSlot knownInputTank(BlockPos at) {
        Optional<JsonObject> server = serverReadings.latest(at);
        if (server.isPresent()) {
            return tankFromServer(server.get(), "input_fluid", "input_fluid_mb", "input_fluid_capacity_mb");
        }
        Optional<AdvancedAeReads.ChamberView> client = currentChamberView(at);
        if (client.isPresent()) return client.get().inputFluid();
        ChamberCache cache = caches.get(at.immutable());
        if (cache != null && cache.menuView != null) return cache.menuView.inputFluid();
        return AdvancedAeReads.TankSlot.EMPTY;
    }

    private AdvancedAeReads.TankSlot knownOutputTank(BlockPos at) {
        Optional<JsonObject> server = serverReadings.latest(at);
        if (server.isPresent()) {
            return tankFromServer(server.get(), "output_fluid", "output_fluid_mb", "output_fluid_capacity_mb");
        }
        Optional<AdvancedAeReads.ChamberView> client = currentChamberView(at);
        if (client.isPresent()) return client.get().outputFluid();
        ChamberCache cache = caches.get(at.immutable());
        if (cache != null && cache.menuView != null) return cache.menuView.outputFluid();
        return AdvancedAeReads.TankSlot.EMPTY;
    }

    private Optional<AdvancedAeReads.TankSlot> serverTankOf(BlockPos pos, boolean inputTank) {
        return serverReadings.latest(pos).map(reply -> inputTank
                ? tankFromServer(reply, "input_fluid", "input_fluid_mb", "input_fluid_capacity_mb")
                : tankFromServer(reply, "output_fluid", "output_fluid_mb", "output_fluid_capacity_mb"));
    }

    private static AdvancedAeReads.TankSlot tankFromServer(JsonObject reply, String idKey,
                                                           String mbKey, String capKey) {
        String id = AdvancedAeDeviceReadings.readString(reply, idKey).orElse("");
        if ("empty".equals(id)) id = "";
        long mb = AdvancedAeDeviceReadings.readLong(reply, mbKey).orElse(0L);
        long cap = AdvancedAeDeviceReadings.readLong(reply, capKey).orElse((long) AdvancedAeReads.TANK_CAPACITY_MB);
        return new AdvancedAeReads.TankSlot(id, mb, cap);
    }

    private void recordMenuView(BlockPos pos, AdvancedAeReads.ChamberMenuView view) {
        ChamberCache cache = cacheOf(pos);
        cache.menuView = view;
        if (!view.inputFluid().isEmpty()) cache.flushedIn = false;
        if (!view.outputFluid().isEmpty()) cache.flushedOut = false;
    }

    private void markTankFlushed(BlockPos pos, boolean inputTank) {
        ChamberCache cache = cacheOf(pos);
        if (inputTank) cache.flushedIn = true;
        else cache.flushedOut = true;
        if (cache.menuView != null) {
            AdvancedAeReads.ChamberMenuView prev = cache.menuView;
            cache.menuView = new AdvancedAeReads.ChamberMenuView(
                    prev.autoExport(), prev.processingTime(), prev.maxProcessingTime(), prev.showWarning(),
                    inputTank ? AdvancedAeReads.TankSlot.EMPTY : prev.inputFluid(),
                    inputTank ? prev.outputFluid() : AdvancedAeReads.TankSlot.EMPTY,
                    prev.upgrades());
        }
    }

    private ChamberCache cacheOf(BlockPos pos) {
        return caches.computeIfAbsent(pos.immutable(), ignored -> new ChamberCache());
    }

    private static Optional<String> firstFluidId(ShownIngredient ingredient) {
        for (ShownStack option : ingredient.options()) {
            if (option.kind() == ShownStack.Kind.FLUID) return Optional.of(option.id());
        }
        return Optional.empty();
    }

    private static int parseUpgradeSlot(String key) {
        if (!key.startsWith(UPGRADE_PREFIX)) return -1;
        try {
            int idx = Integer.parseInt(key.substring(UPGRADE_PREFIX.length()));
            return idx >= 0 && idx < AdvancedAeReads.UPGRADE_SLOT_COUNT ? idx : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean resolveWorking(Optional<AdvancedAeReads.ChamberView> client, Optional<JsonObject> server) {
        if (client.isPresent()) return client.get().working();
        return server.flatMap(r -> AdvancedAeDeviceReadings.readBoolean(r, "working")).orElse(false);
    }

    // 供电与在线态优先取服务端权威读数；客户端 ManagedGridNode 无 node 时返回 null，避免假 false 误判未通电。
    private static Boolean resolvePowered(Optional<AdvancedAeReads.ChamberView> client, Optional<JsonObject> server) {
        Optional<Boolean> fromServer = server.flatMap(r -> AdvancedAeDeviceReadings.readBoolean(r, "powered"));
        if (fromServer.isPresent()) return fromServer.get();
        return client.map(AdvancedAeReads.ChamberView::powered).orElse(null);
    }

    private static Boolean resolveActive(Optional<AdvancedAeReads.ChamberView> client, Optional<JsonObject> server) {
        Optional<Boolean> fromServer = server.flatMap(r -> AdvancedAeDeviceReadings.readBoolean(r, "active"));
        if (fromServer.isPresent()) return fromServer.get();
        return client.map(AdvancedAeReads.ChamberView::active).orElse(null);
    }

    // 双流体槽读数优先取服务端权威值，再回落客户端方块实体与本地开窗缓存。
    private static void fillFluidReadings(Map<String, Object> readings,
                                          Optional<AdvancedAeReads.ChamberView> client,
                                          Optional<JsonObject> server, ChamberCache cache) {
        AdvancedAeReads.TankSlot in = server
                .map(r -> tankFromServer(r, "input_fluid", "input_fluid_mb", "input_fluid_capacity_mb"))
                .or(() -> client.map(AdvancedAeReads.ChamberView::inputFluid))
                .or(() -> cache != null && cache.menuView != null ? Optional.of(cache.menuView.inputFluid()) : Optional.empty())
                .orElse(AdvancedAeReads.TankSlot.EMPTY);
        AdvancedAeReads.TankSlot out = server
                .map(r -> tankFromServer(r, "output_fluid", "output_fluid_mb", "output_fluid_capacity_mb"))
                .or(() -> client.map(AdvancedAeReads.ChamberView::outputFluid))
                .or(() -> cache != null && cache.menuView != null ? Optional.of(cache.menuView.outputFluid()) : Optional.empty())
                .orElse(AdvancedAeReads.TankSlot.EMPTY);
        readings.put("input_fluid", in.isEmpty() ? "empty" : in.fluidId());
        readings.put("input_fluid_mb", in.amountMb());
        readings.put("input_fluid_capacity_mb", in.capacityMb());
        readings.put("output_fluid", out.isEmpty() ? "empty" : out.fluidId());
        readings.put("output_fluid_mb", out.amountMb());
        readings.put("output_fluid_capacity_mb", out.capacityMb());
    }

    private static Double fillProgressAndEnergy(Map<String, Object> readings,
                                                Optional<JsonObject> server, ChamberCache cache) {
        Long proc = server.flatMap(r -> AdvancedAeDeviceReadings.readLong(r, "processing_time"))
                .or(() -> cache != null && cache.menuView != null ? Optional.of((long) cache.menuView.processingTime()) : Optional.empty())
                .orElse(null);
        Long maxProc = server.flatMap(r -> AdvancedAeDeviceReadings.readLong(r, "max_processing_time"))
                .or(() -> cache != null && cache.menuView != null && cache.menuView.maxProcessingTime() > 0
                        ? Optional.of((long) cache.menuView.maxProcessingTime()) : Optional.empty())
                .orElse((long) AdvancedAeReads.MAX_PROCESSING_STEPS);
        Double progress = null;
        if (proc != null && proc >= 0) {
            readings.put("processing_time", proc);
            readings.put("max_processing_time", maxProc);
            if (maxProc > 0 && proc > 0) {
                progress = Math.min(1.0, (double) proc / maxProc);
            }
        }
        server.flatMap(r -> AdvancedAeDeviceReadings.readDouble(r, "energy_stored"))
                .ifPresent(v -> readings.put("energy_stored", v));
        server.flatMap(r -> AdvancedAeDeviceReadings.readDouble(r, "energy_capacity"))
                .ifPresent(v -> readings.put("energy_capacity", v));
        Boolean warning = server.flatMap(r -> AdvancedAeDeviceReadings.readBoolean(r, "show_warning"))
                .or(() -> cache != null && cache.menuView != null ? Optional.of(cache.menuView.showWarning()) : Optional.empty())
                .orElse(null);
        if (warning != null) readings.put("show_warning", warning);
        return progress;
    }

    private static void fillConfigReadings(Map<String, Object> readings,
                                           Optional<JsonObject> server, ChamberCache cache) {
        String autoExport = resolveAutoExport(server, cache);
        if (!"unknown".equals(autoExport)) readings.put("auto_export", autoExport);
        resolveOutputSides(server, cache).ifPresent(sides ->
                readings.put("output_sides", sides.isEmpty() ? "none" : sides.stream().sorted().collect(Collectors.joining(","))));
        List<String> upgrades = resolveUpgrades(server, cache);
        for (int i = 0; i < upgrades.size() && i < AdvancedAeReads.UPGRADE_SLOT_COUNT; i++) {
            readings.put(UPGRADE_PREFIX + i, upgrades.get(i));
        }
    }

    private static List<MachineState.Shown> fillItemReadings(Map<String, Object> readings,
                                                             Optional<AdvancedAeReads.ChamberView> client,
                                                             Optional<JsonObject> server) {
        List<MachineState.Shown> shown = new ArrayList<>();
        if (client.isPresent()) {
            List<MachineState.Shown> inputs = client.get().inputs();
            List<MachineState.Shown> outputs = client.get().outputs();
            readings.put("input_items", formatShown(inputs));
            readings.put("output_items", formatShown(outputs));
            shown.addAll(inputs);
            shown.addAll(outputs);
            return shown;
        }
        server.ifPresent(reply -> {
            List<String> inList = AdvancedAeDeviceReadings.readStringList(reply, "input_items");
            List<String> outList = AdvancedAeDeviceReadings.readStringList(reply, "output_items");
            readings.put("input_items", inList.isEmpty() ? "empty" : String.join(", ", inList));
            readings.put("output_items", outList.isEmpty() ? "empty" : String.join(", ", outList));
        });
        return shown;
    }

    private static String resolveAutoExport(Optional<JsonObject> server, ChamberCache cache) {
        Optional<String> fromServer = server.flatMap(r -> AdvancedAeDeviceReadings.readSetting(r, "auto_export")
                .or(() -> AdvancedAeDeviceReadings.readString(r, "auto_export")));
        if (fromServer.isPresent()) return fromServer.get();
        if (cache != null && cache.menuView != null) return cache.menuView.autoExport();
        return "unknown";
    }

    private static Optional<Set<String>> resolveOutputSides(Optional<JsonObject> server, ChamberCache cache) {
        if (server.isPresent() && server.get().has("readings")
                && server.get().getAsJsonObject("readings").has("output_sides")) {
            return Optional.of(AdvancedAeDeviceReadings.readStringSet(server.get(), "output_sides"));
        }
        if (cache != null && cache.outputSides != null) return Optional.of(cache.outputSides);
        return Optional.empty();
    }

    private static List<String> resolveUpgrades(Optional<JsonObject> server, ChamberCache cache) {
        if (server.isPresent()) {
            List<String> fromServer = AdvancedAeDeviceReadings.readUpgrades(server.get());
            if (!fromServer.isEmpty()) return fromServer;
        }
        if (cache != null && cache.menuView != null && !cache.menuView.upgrades().isEmpty()) {
            return cache.menuView.upgrades();
        }
        return List.of();
    }

    private static String formatShown(List<MachineState.Shown> items) {
        if (items.isEmpty()) return "empty";
        return items.stream().map(s -> s.itemId() + " x" + s.count()).collect(Collectors.joining(", "));
    }

    private static String buildStateNote(Boolean powered, Boolean active, Map<String, Object> readings,
                                         boolean hasServer) {
        List<String> parts = new ArrayList<>();
        if (Boolean.FALSE.equals(powered)) parts.add("未通电（请接入带能源元件的 ME 电网）");
        else if (Boolean.TRUE.equals(powered) && Boolean.FALSE.equals(active)) {
            parts.add("已通电但未连上在线 ME 网络");
        }
        if (Boolean.TRUE.equals(readings.get("show_warning"))) {
            parts.add("触发缺电或输出受阻告警（show_warning）");
        }
        if (!hasServer) {
            parts.add("供电态、进度、电量与允许输出面需服务端 advanced_ae.device 或打开界面查看");
        }
        return String.join("；", parts);
    }
}
