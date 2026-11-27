// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 水晶组装器（{@code extendedae:crystal_assembler}）设备委托：
 * 非顶面五面连 ME 网与受电；流体原料按机内储罐余量换算为对应标准桶物品并在异种流体冲突时拦截，
 * 倒桶经 {@link CrystalAssemblerTankFeed} 走界面 {@code EMPTY_ITEM} 发包；9 格固体走 {@link MenuFeed}，
 * 收产物走 {@link MenuOutputLook}（兼容 {@code wait=false}）；配置支持 {@code auto_export}、
 * {@code output_side.<dir>} 与 {@code upgrade.0..3}。
 */
final class CrystalAssemblerOps implements ExtendedAeDeviceOps {

    static final int UPGRADE_SLOTS = 4;
    static final long TANK_CAPACITY_MB = 16000L;
    static final int MAX_PROGRESS = 200;
    private static final long BUCKET_MB = 1000L;
    static final List<String> YES_NO = List.of("yes", "no");
    static final List<String> TRUE_FALSE = List.of("true", "false");
    private static final List<String> UPGRADE_CHOICES = List.of("ae2:speed_card", "empty");

    /** 储罐当前内容快照（来源可为开窗缓存或服务端 extendedae.device）。 */
    record TankStatus(String fluidId, long amountMb, long capacityMb) {
        static final TankStatus EMPTY = new TankStatus("", 0L, TANK_CAPACITY_MB);
    }

    /** 某台水晶组装器本次计划中映射到储罐倒桶的桶条目上下文。 */
    private record PendingTankBucket(String bucketItemId, String fluidId, long requiredMb) {}

    private final ExtendedAeCompat compat;
    private final ExtendedAeDeviceReadings readings;
    private final Function<String, Optional<String>> bucketLookup;
    private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;
    /** 记录最近一次计划中哪些桶条目属于储罐倒桶，供 feed 分流至倒桶动作。 */
    private final Map<BlockPos, Map<String, PendingTankBucket>> plannedTankBuckets = new HashMap<>();

    CrystalAssemblerOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings) {
        this(compat, readings, CrystalAssemblerOps::registryBucketForFluid, null);
    }

    CrystalAssemblerOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
            Function<String, Optional<String>> bucketLookup,
            BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.readings = Objects.requireNonNull(readings, "readings");
        this.bucketLookup = Objects.requireNonNull(bucketLookup, "bucketLookup");
        this.menuOpenings = menuOpenings;
    }

    // 水晶组装器仅非顶面五面可连 ME 网与受电。
    @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        Direction top = level == null ? Direction.UP : compat.crystalAssemblerAt(level, at)
                .map(ExtendedAeReads.CrystalAssemblerView::topFace).orElse(Direction.UP);
        List<ExchangePoint> points = new ArrayList<>(5);
        for (Direction side : Direction.values()) {
            if (side == top) continue;
            points.add(new ExchangePoint(at, side, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                    "水晶组装器非顶面：可连 ME 网、受电与物料交互"));
        }
        return Optional.of(List.copyOf(points));
    }

    @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到水晶组装器"));
        Optional<ExtendedAeReads.CrystalAssemblerView> found = compat.crystalAssemblerAt(level, at);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(buildState(at, found.get(), readings.crystalAssembler(at), readings.latest(at)));
    }

    /** 纯函数：整合客户端同步态、开窗缓存与服务端权威读数构造水晶组装器状态。 */
    static MachineState buildState(BlockPos at, ExtendedAeReads.CrystalAssemblerView view,
            Optional<ExtendedAeReads.CrystalAssemblerMenuView> cached, Optional<JsonObject> serverReply) {
        Map<String, Object> readingsMap = new LinkedHashMap<>();
        readingsMap.put("working", view.working());
        readingsMap.put("powered", view.powered());
        readingsMap.put("active", view.active());
        readingsMap.put("tank_capacity_mb", TANK_CAPACITY_MB);
        readingsMap.put("max_processing_time", (long) MAX_PROGRESS);
        cached.ifPresent(menu -> applyMenuCache(readingsMap, menu));
        serverReply.ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(readingsMap, reply));
        readingsMap.putIfAbsent("tank_fluid", "empty");
        readingsMap.putIfAbsent("tank_amount_mb", 0L);
        MachineState.Activity activity = !view.powered() ? MachineState.Activity.OFFLINE
                : view.working() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        String note = "非顶面五面可连 ME 网受电；流体需打开界面往储罐槽倒桶，固体放 9 格输入槽";
        return new MachineState(activity, null, null, List.of(), readingsMap, note);
    }

    private static void applyMenuCache(Map<String, Object> target, ExtendedAeReads.CrystalAssemblerMenuView menu) {
        target.put("tank_fluid", menu.tankFluidId().isEmpty() ? "empty" : menu.tankFluidId());
        target.put("tank_amount_mb", menu.tankAmountMb());
        target.put("tank_capacity_mb", menu.tankCapacityMb());
        target.put("processing_time", (long) menu.processingTime());
        target.put("max_processing_time", (long) menu.maxProcessingTime());
        target.put("auto_export", menu.autoExport());
        target.put("output_sides", menu.outputSides());
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            target.put("upgrade." + i, i < menu.upgrades().size() ? menu.upgrades().get(i) : "empty");
        }
    }

    // 水晶组装器输出槽不在客户端同步，交由 machine_run 走界面回头看或 wait=false 投完即返。
    @Override public boolean outputVisible(BlockPos at) {
        return false;
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient,
                    long amount) {
                return computeFluidIngredientFeed(at, ingredient, amount, currentTankStatus(at), bucketLookup);
            }

            @Override public FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
                return buildPlan(at, recipe, batches);
            }

            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                return buildFeedAction(at, item, permissions, services);
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                MenuOpening opening = openingFor(at, permissions, services);
                return Optional.of(new MenuOutputLook(itemId, true, opening, services.context()));
            }
        });
    }

    // 一次算清配方流体与固体投料计划：异种流体冲突直接给 FeedPlan.conflict，同种已够不备桶，不足按 1000 mB 向上取整换算桶数。
    FeedPlan buildPlan(BlockPos at, ShownRecipe recipe, int batches) {
        BlockPos pos = at.immutable();
        TankStatus tank = currentTankStatus(pos);
        List<FeedPlan.Entry> fluidEntries = new ArrayList<>();
        List<FeedPlan.Entry> solidEntries = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, PendingTankBucket> bucketsByItem = new LinkedHashMap<>();
        Optional<String> conflict = Optional.empty();

        for (ShownIngredient ingredient : recipe.inputs()) {
            Optional<WantedItem> wanted = MachineFeeding.wantedItemOf(ingredient);
            if (wanted.isPresent()) {
                long count = (long) ingredient.amount() * Math.max(1, batches);
                if (count > 0) {
                    solidEntries.add(new FeedPlan.Entry(wanted.get().specifier(),
                            (int) Math.min(Integer.MAX_VALUE, count), false));
                }
                continue;
            }
            Optional<String> fluidId = fluidIdOf(ingredient);
            if (fluidId.isEmpty()) {
                notes.add("配方还要机器自己要的东西（" + MachineFeeding.describe(ingredient)
                        + "），带不进机器，做不做得看机器里还有没有");
                continue;
            }
            long totalNeededMb = (long) ingredient.amount() * Math.max(1, batches);
            if (tank.amountMb() > 0 && !tank.fluidId().isEmpty() && !tank.fluidId().equals(fluidId.get())) {
                conflict = Optional.of("水晶组装器储罐内已有不同种流体 " + tank.fluidId() + "（" + tank.amountMb()
                        + " mB），与配方所需的 " + fluidId.get() + " 冲突；请先用空桶抽出或换一台机器");
                continue;
            }
            long existingSame = tank.fluidId().equals(fluidId.get()) ? tank.amountMb() : 0L;
            if (existingSame >= totalNeededMb) {
                notes.add("储罐里已有足够流体（" + fluidId.get() + " " + existingSame + " mB），不用再倒桶");
                continue;
            }
            Optional<IngredientFeed> feed = computeFluidIngredientFeed(pos, ingredient, totalNeededMb, tank,
                    bucketLookup);
            if (feed.isPresent()) {
                notes.add(feed.get().note());
                fluidEntries.add(new FeedPlan.Entry(feed.get().itemId(), feed.get().count(), false));
                bucketsByItem.put(feed.get().itemId(),
                        new PendingTankBucket(feed.get().itemId(), fluidId.get(), totalNeededMb));
            } else {
                notes.add("流体原料 " + fluidId.get() + " 没有对应的标准桶物品，带不进水晶组装器");
            }
        }
        plannedTankBuckets.put(pos, bucketsByItem);
        List<FeedPlan.Entry> entries = new ArrayList<>(fluidEntries.size() + solidEntries.size());
        entries.addAll(fluidEntries);
        entries.addAll(solidEntries);
        return new FeedPlan(entries, notes, conflict, Optional.empty());
    }

    /** 纯函数：把流体原料按机内储罐余量换算为标准桶物品与桶数；已够数或无对应桶或异种冲突时返回空。 */
    static Optional<IngredientFeed> computeFluidIngredientFeed(BlockPos at, ShownIngredient ingredient,
            long totalNeededMb, TankStatus tank, Function<String, Optional<String>> bucketLookup) {
        Optional<String> fluidOpt = fluidIdOf(ingredient);
        if (fluidOpt.isEmpty() || totalNeededMb <= 0) return Optional.empty();
        String fluidId = fluidOpt.get();
        if (tank.amountMb() > 0 && !tank.fluidId().isEmpty() && !tank.fluidId().equals(fluidId)) {
            return Optional.empty();
        }
        long existingSame = tank.fluidId().equals(fluidId) ? tank.amountMb() : 0L;
        if (existingSame >= totalNeededMb) return Optional.empty();
        Optional<String> bucketItem = bucketLookup.apply(fluidId);
        if (bucketItem.isEmpty()) return Optional.empty();
        long deficit = totalNeededMb - existingSame;
        int buckets = (int) Math.ceil(deficit / (double) BUCKET_MB);
        String note = "配方需要 " + fluidId + " " + totalNeededMb + " mB（储罐现有 " + existingSame
                + " mB），按 1000 mB/桶换算为 " + bucketItem.get() + " ×" + buckets;
        return Optional.of(new IngredientFeed(bucketItem.get(), Math.max(1, buckets), note));
    }

    private Optional<Action> buildFeedAction(BlockPos at, FeedPlan.Entry item, Permissions permissions,
            PlayerServices services) {
        BlockPos pos = at.immutable();
        Map<String, PendingTankBucket> buckets = plannedTankBuckets.get(pos);
        PendingTankBucket plannedBucket = buckets == null ? null : buckets.get(item.item());
        if (plannedBucket != null) {
            return Optional.of(new CrystalAssemblerTankFeed(pos, plannedBucket.bucketItemId(),
                    plannedBucket.fluidId(), plannedBucket.requiredMb(), item.count(),
                    openingFor(pos, permissions, services), compat, readings));
        }
        return Optional.of(new MenuFeed(services, "水晶组装器", pos, ExtendedAeMachineCatalog.CRYSTAL_ASSEMBLER,
                item.item(), item.count(), permissions, null));
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                return assemblerSettings(readings.crystalAssembler(at), readings.latest(at));
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                return changeSetting(at.immutable(), key, value, permissions, services);
            }
        });
    }

    /** 纯函数：构造水晶组装器设置项清单（自动输出、六向输出面开关、4 格升级槽）。 */
    static List<MachineSetting> assemblerSettings(Optional<ExtendedAeReads.CrystalAssemblerMenuView> cached,
            Optional<JsonObject> serverReply) {
        List<MachineSetting> list = new ArrayList<>(1 + Direction.values().length + UPGRADE_SLOTS);
        String autoExport = cached.map(ExtendedAeReads.CrystalAssemblerMenuView::autoExport)
                .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "auto_export")))
                .orElse("unknown");
        list.add(new MachineSetting("auto_export", autoExport, YES_NO,
                "自动输出：yes 时产物自动推送至已勾选的输出面"));
        List<String> enabledSides = cached.map(ExtendedAeReads.CrystalAssemblerMenuView::outputSides)
                .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readStringList(r, "output_sides")))
                .orElse(null);
        for (Direction dir : Direction.values()) {
            String dirName = dir.getSerializedName();
            String cur = enabledSides == null ? "unknown"
                    : enabledSides.contains(dirName) ? "true" : "false";
            list.add(new MachineSetting("output_side." + dirName, cur, TRUE_FALSE,
                    "自动输出方向 " + dirName + " 开关（true/false）"));
        }
        List<String> upgrades = cached.map(ExtendedAeReads.CrystalAssemblerMenuView::upgrades)
                .orElseGet(() -> serverReply.map(ExtendedAeDeviceReadings::readUpgrades).orElse(List.of()));
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            String card = i < upgrades.size() ? upgrades.get(i) : (cached.isPresent() ? "empty" : "unknown");
            list.add(new MachineSetting("upgrade." + i, card, UPGRADE_CHOICES,
                    "水晶组装器第 " + i + " 号升级槽（ae2:speed_card / empty）"));
        }
        return List.copyOf(list);
    }

    private Optional<Action> changeSetting(BlockPos target, String key, String value,
            Permissions permissions, PlayerServices services) {
        if ("auto_export".equals(key)) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                    openingFor(target, permissions, services), compat, "auto_export", normalized, 3,
                    () -> compat.openCrystalAssemblerMenu().map(ExtendedAeReads.CrystalAssemblerMenuView::autoExport),
                    () -> compat.openCrystalAssemblerMenu().ifPresent(v -> readings.putCrystalAssembler(target, v)),
                    "水晶组装器自动输出"));
        }
        if (key.startsWith("output_side.")) {
            Direction dir = Direction.byName(key.substring("output_side.".length()).toLowerCase(Locale.ROOT));
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (dir == null || !TRUE_FALSE.contains(normalized)) return Optional.empty();
            boolean enabled = "true".equals(normalized);
            String dirName = dir.getSerializedName();
            return Optional.of(new ExtendedAeMenuActionSupport.GenericPacketAction(
                    openingFor(target, permissions, services),
                    () -> compat.openCrystalAssemblerMenu().isPresent(),
                    () -> compat.openCrystalAssemblerMenu()
                            .map(v -> v.outputSides().contains(dirName) == enabled).orElse(false),
                    () -> compat.sendGenericAction("set_side", dirName, enabled),
                    () -> compat.openCrystalAssemblerMenu()
                            .map(v -> v.outputSides().contains(dirName) == enabled).orElse(false),
                    () -> compat.openCrystalAssemblerMenu().ifPresent(v -> readings.putCrystalAssembler(target, v)),
                    "设置水晶组装器输出面 " + dirName + "=" + enabled));
        }
        int slot = ExtendedAeMenuActionSupport.parseUpgradeSlot(key, UPGRADE_SLOTS);
        if (slot < 0) return Optional.empty();
        boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
        if (!remove && !"ae2:speed_card".equals(value)) return Optional.empty();
        return Optional.of(new ExtendedAeMenuActionSupport.SemanticSlotSwapAction(
                openingFor(target, permissions, services), compat, "UPGRADE", slot,
                remove ? null : snap -> "ae2:speed_card".equals(snap.key()),
                remove ? "空位" : "ae2:speed_card",
                () -> compat.openCrystalAssemblerMenu().ifPresent(v -> readings.putCrystalAssembler(target, v)),
                "水晶组装器升级槽 " + slot));
    }

    // 先读服务端权威储罐读数（防止上一轮开窗旧缓存掩盖已消耗完的储罐），无服务端读数时再回落开窗缓存。
    private TankStatus currentTankStatus(BlockPos at) {
        Optional<JsonObject> reply = readings.latest(at);
        if (reply.isPresent()
                && (ExtendedAeDeviceReadings.readString(reply.get(), "tank_fluid").isPresent()
                || ExtendedAeDeviceReadings.readLong(reply.get(), "tank_amount_mb").isPresent())) {
            String fluid = ExtendedAeDeviceReadings.readString(reply.get(), "tank_fluid").orElse("");
            if ("empty".equals(fluid)) fluid = "";
            long amount = ExtendedAeDeviceReadings.readLong(reply.get(), "tank_amount_mb").orElse(0L);
            long cap = ExtendedAeDeviceReadings.readLong(reply.get(), "tank_capacity_mb").orElse(TANK_CAPACITY_MB);
            return new TankStatus(fluid, amount, cap);
        }
        Optional<ExtendedAeReads.CrystalAssemblerMenuView> cached = readings.crystalAssembler(at);
        if (cached.isPresent()) {
            ExtendedAeReads.CrystalAssemblerMenuView v = cached.get();
            return new TankStatus(v.tankFluidId(), v.tankAmountMb(), v.tankCapacityMb());
        }
        return TankStatus.EMPTY;
    }

    private MenuOpening openingFor(BlockPos target, Permissions permissions, PlayerServices services) {
        return menuOpenings != null ? menuOpenings.apply(target, permissions)
                : ExtendedAeMenuActionSupport.openBlock(target, services, permissions);
    }

    private static Optional<String> fluidIdOf(ShownIngredient ingredient) {
        return ingredient.options().stream()
                .filter(opt -> opt.kind() == ShownStack.Kind.FLUID && !opt.id().isBlank())
                .map(ShownStack::id)
                .findFirst();
    }

    /** 经原版流体注册表查 {@code Fluid.getBucket()}，不硬编码流体白名单。 */
    static Optional<String> registryBucketForFluid(String fluidId) {
        if (fluidId == null || fluidId.isBlank()) return Optional.empty();
        ResourceLocation key = ResourceLocation.tryParse(fluidId);
        if (key == null) return Optional.empty();
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(key).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return Optional.empty();
        Item bucket = fluid.getBucket();
        if (bucket == null || bucket == Items.AIR) return Optional.empty();
        return Optional.of(BuiltInRegistries.ITEM.getKey(bucket).toString());
    }
}
