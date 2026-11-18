// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import java.util.function.BiFunction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineInstallations;
import org.maiwithu.maicraft.ability.machine.spi.MachineParts;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.MountedPart;
import org.maiwithu.maicraft.ability.machine.spi.NetworkLoad;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatMenuFeed;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 应用能源2的一种设备：按目录里的那一行认方块，挂在 ME 网上的六面都能接线缆；读得出亮没亮（有电、有频道）、
 * 控制器什么状态、线缆格各面装着什么部件。线缆格还管两件 AE2 自己的装法：一段线缆（安装段，种类是线缆物品 ID）
 * 与线缆某一面上的部件。网络本身的频道、能量、存储由 ME 网络读取器经服务端读。
 */
final class Ae2MachineType implements MachineType, MachineFeeding, MachineSettings, MachineInstallations,
        MachineParts {

    private final Ae2MachineCatalog.Kind kind;
    private final Ae2Compat compat;
    private final Ae2Inscribers inscribers;
    private final PlayerServices services;
    private final Set<Block> blocks;
    private final MeCables cables;
    /** 每台样板供应器上次打开界面时样板格里样板的产出：样板格只在开着界面时同步。 */
    private final Map<BlockPos, List<String>> patternsSeen = new HashMap<>();
    /** 本会话由角色自己放进各台压印器、收产物时应一并收回背包的不消耗压印模板；机内原有的不进此表。 */
    private final Map<BlockPos, Set<String>> placedPlates = new HashMap<>();
    private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;

    Ae2MachineType(Ae2MachineCatalog.Kind kind, Ae2Compat compat, Ae2Inscribers inscribers, PlayerServices services,
            Set<Block> blocks) {
        this(kind, compat, inscribers, services, blocks,
                (at, permissions) -> new ClientMenuOpening(at, permissions, services.menuLayouts(),
                        services.approach(), services.interactions(), services.context()));
    }

    Ae2MachineType(Ae2MachineCatalog.Kind kind, Ae2Compat compat, Ae2Inscribers inscribers, PlayerServices services,
            Set<Block> blocks, BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.inscribers = Objects.requireNonNull(inscribers, "inscribers");
        this.services = Objects.requireNonNull(services, "services");
        this.blocks = Set.copyOf(blocks);
        this.cables = new MeCables(compat, services);
        this.menuOpenings = Objects.requireNonNull(menuOpenings, "menuOpenings");
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return Ae2Compat.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) {
        return blocks.contains(state.getBlock());
    }

    // 挂在 ME 网上的设备六面都能接线缆；线缆格只算连着的面与空着的面（装了部件的那一面接不了线缆）。
    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        if (!kind.onGrid()) {
            return List.of(new ExchangePoint(at, null, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                    "不接 ME 网的单机：打开界面放料取货"));
        }
        ClientLevel level = level();
        Optional<Ae2Grid.GridCell> cell = level == null ? Optional.empty() : compat.gridCell(level, at);
        List<ExchangePoint> points = new ArrayList<>();
        for (Direction side : Direction.values()) {
            if (cell.isPresent() && cell.get().cableBus() && cell.get().parts().containsKey(side)) continue;
            points.add(new ExchangePoint(at, side, null, MeNetworkReader.KIND, ExchangePoint.Flow.BOTH,
                    cell.map(found -> found.links().contains(side)).orElse(false) ? "这一面连着线缆" : "这一面能接线缆"));
        }
        return points;
    }

    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (isInscriber()) {
            Optional<Ae2Inscribers.Slots> inscriberSlots = compat.inscriberSlots(level, at);
            if (inscriberSlots.isPresent()) {
                Optional<Ae2Grid.GridCell> gridCell = level == null ? Optional.empty() : compat.gridCell(level, at);
                return Ae2Readings.inscriberState(inscriberSlots.get(), gridCell);
            }
        }
        if (level == null) return MachineState.unknown("角色现在不在世界里，读不到设备");
        Optional<Ae2Grid.GridCell> cell = compat.gridCell(level, at);
        if (cell.isEmpty()) return MachineState.unknown("这一格读不到 ME 设备（没加载，或已经不是 AE2 的设备）");
        return Ae2Readings.state(cell.get());
    }

    // 线缆格各面装着的总线、终端：看机器时它们各自单列成设备，线缆本身合进 ME 网；不是线缆格或读不到给空。
    @Override public List<MountedPart> mountedParts(BlockPos at) {
        ClientLevel level = level();
        if (level == null || !isCableBus()) return List.of();
        return compat.gridCell(level, at).map(Ae2Readings::parts).orElse(List.of());
    }

    // 频道：挂在网上又占频道的设备算 1 个；控制器给频道（每个控制器面 32 个，审阅里按 32 估），线缆不占。
    @Override public Optional<NetworkLoad> load(BlockState state) {
        if (!kind.onGrid() || kind.role() == MachineRole.CABLE) return Optional.empty();
        // 网络种类按 ME 网读取器自报的那一种，不写死字面量。
        if (kind.role() == MachineRole.NETWORK_CORE) {
            return Optional.of(new NetworkLoad(MeNetworkReader.KIND.id(), 0, 32, "频道"));
        }
        return Optional.of(new NetworkLoad(MeNetworkReader.KIND.id(), 1, 0, "频道"));
    }

    @Override public Optional<String> installationProblem(Installation installation) {
        if (!isCableBus()) return Optional.of(kind.name() + "不装安装段");
        return cables.problem(installation);
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        if (!isCableBus()) return Optional.of(kind.name() + "上不装部件");
        return cables.partProblem(part, BuiltInRegistries.BLOCK.getKey(host.getBlock()).toString());
    }

    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        if (!isCableBus() || !compat.cableItem(installation.kind())) return Optional.empty();
        return Optional.of(cables.lay(installation, permissions));
    }

    @Override public boolean installed(Installation installation) {
        ClientLevel level = level();
        return level != null && isCableBus() && compat.cableItem(installation.kind())
                && cables.installed(level, installation);
    }

    // ME 线缆是这一段一格格接上的：拆整台时跟着拆。
    @Override public Optional<List<BlockPos>> placedBy(Installation installation) {
        if (!isCableBus() || !compat.cableItem(installation.kind())) return Optional.empty();
        return Optional.of(installation.cells());
    }

    // 读现场：线缆格不写成方块，同种线缆连着的写成一段段线缆、各面的部件写进部件；别的 ME 设备照抄方块。
    @Override public Optional<MachineCopy> copy(Map<BlockPos, BlockState> cells) {
        ClientLevel level = level();
        if (!isCableBus() || level == null) return Optional.empty();
        Map<BlockPos, Ae2Grid.GridCell> read = new LinkedHashMap<>();
        for (BlockPos at : cells.keySet()) {
            compat.gridCell(level, at).ifPresent(cell -> read.put(at, cell));
        }
        return Optional.of(MeCableCopies.copy(cells.keySet(), read,
                at -> compat.gridCell(level, at).map(cell -> !cell.cableBus()).orElse(false)));
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        if (!isCableBus() || !compat.partItem(part.itemId())) return Optional.empty();
        return Optional.of(cables.mount(part, permissions));
    }

    @Override public boolean mounted(PartCell part) {
        ClientLevel level = level();
        return level != null && isCableBus() && cables.mounted(level, part);
    }

    // 样板供应器里放哪些样板：身上带着的与上次打开界面时看见的都列出来；别的设备没有可改的设置。
    @Override public List<MachineSetting> settings(BlockPos at) {
        if (!isPatternProvider()) return List.of();
        return PatternSettings.of(Optional.ofNullable(patternsSeen.get(at)), carriedPatterns());
    }

    // 改一项：pattern.产出物品 写 in 放进去、out 拿回背包，开界面快速移动那张样板；别的键不认。
    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        if (!isPatternProvider()) return Optional.empty();
        Optional<String> output = PatternSettings.outputOf(key);
        if (output.isEmpty() || !(value.equals(PatternSettings.IN) || value.equals(PatternSettings.OUT))) {
            return Optional.empty();
        }
        return Optional.of(new PatternChange(compat, services, at, output.get(), value.equals(PatternSettings.IN),
                permissions, patternsSeen));
    }

    private boolean isPatternProvider() {
        return kind.id().equals("ae2:pattern_provider");
    }

    // 身上带着的样板各产出什么：背包里每一件编码过的样板都解一下。
    private Set<String> carriedPatterns() {
        PlayerContext current = services.context().get();
        if (current == null || current.localPlayer() == null || current.level() == null) return Set.of();
        Set<String> outputs = new LinkedHashSet<>();
        Inventory inventory = current.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) compat.patternOutput(stack, current.level()).ifPresent(outputs::add);
        }
        return outputs;
    }

    // 这条配方在这台压印器怎么投，读一次四个工作格、一次算清：每格几件、模板先投、机内冲突与腾空动作。
    @Override public FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
        if (!isInscriber()) return MachineFeeding.super.plan(at, recipe, batches, permissions);
        BlockPos pos = at.immutable();
        Optional<Ae2Inscribers.Slots> fourSlots = compat.inscriberSlots(level(), pos);
        Ae2InscriberPlates.MachinePlan computed = Ae2InscriberPlates.plan(recipe, batches, fourSlots);
        // 有冲突才给腾空动作：打开界面把占槽的不兼容物取回背包；腾空后重算计划，冲突自然消失。
        Optional<Action> clearing = computed.conflict().isEmpty() ? Optional.empty()
                : Optional.of(new Ae2InscriberClear(recipe, batches, menuOpenings.apply(pos, permissions),
                        placedPlates.get(pos)));
        return new FeedPlan(computed.entries(), computed.notes(), computed.conflict(), clearing);
    }

    // 投料：压印器放好料自己压，料从界面放进对应工作格；计划里标明要收回的（本趟新放的不消耗模板）
    // 与投完新出现的不消耗模板都记进"自己放进机器的"，收产物时一并收回。
    // 真因机内占着别的物品放不进时在报错里把槽内占用物点名说清。线缆格、样板供应器这类不收料，照旧给空。
    @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
        if (!isInscriber()) return Optional.empty();
        BlockPos pos = at.immutable();
        Set<String> platesBefore = new LinkedHashSet<>();
        MenuFeed inner = new MenuFeed(services, kind.name(), pos, kind.id(), item.item(), item.count(),
                permissions, null);
        return Optional.of(new CompatMenuFeed(inner,
                () -> compat.inscriberInputs(level(), pos).ifPresent(slots -> platesBefore.addAll(slots.plates())),
                () -> recordPlacedPlate(pos, item, platesBefore),
                problem -> enrichFeedFailure(pos, item.item(), problem)));
    }

    // 这一下投完后哪些模板算"自己放进机器的"：计划标明要收回的直接记下；别的照投前投后工作格的差算。
    private void recordPlacedPlate(BlockPos pos, FeedPlan.Entry item, Set<String> platesBefore) {
        if (item.collectBack()
                || Ae2InscriberPlates.isPressItemId(item.item()) && !platesBefore.contains(item.item())) {
            placedPlates.computeIfAbsent(pos, key -> new LinkedHashSet<>()).add(item.item());
            return;
        }
        compat.inscriberInputs(level(), pos).ifPresent(after -> {
            for (String plate : after.plates()) {
                if (!platesBefore.contains(plate) && Ae2InscriberPlates.isPressItemId(plate)) {
                    placedPlates.computeIfAbsent(pos, key -> new LinkedHashSet<>()).add(plate);
                }
            }
        });
    }

    private Problem enrichFeedFailure(BlockPos pos, String itemId, Problem problem) {
        List<String> occupied = compat.inscriberSlots(level(), pos)
                .map(Ae2Inscribers.Slots::occupiedIds)
                .orElse(List.of());
        if (occupied.isEmpty()) return problem;
        return Problem.of(problem.kind(),
                "压印器里还放着 " + String.join("、", occupied) + "，与这次要投的 " + itemId + " 不兼容（"
                        + problem.message() + "）",
                "腾出背包空位后再运行 machine_run（会自动取出冲突占槽物），或用 obtain 按物品名从压印器取出占着的物品");
    }

    // 出口：压印器的产物格随方块实体同步，站在外面读得到。
    @Override public List<MachineState.Shown> output(BlockPos at) {
        ClientLevel level = level();
        if (level == null || !isInscriber()) return List.of();
        return compat.inscriberOutput(level, at).orElse(List.of());
    }

        // 收产物：点开压印器的界面把产物搬进背包；若本会话由我们放进了压印模板，顺手一并收回背包（机内原本就有的模板不动）。
    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        if (!isInscriber()) return Optional.empty();
        MenuOpening opening = menuOpenings.apply(at, permissions);
        Set<String> own = placedPlates.get(at);
        if (own == null || own.isEmpty()) {
            return Optional.of(new MenuOutputLook(itemId, true, opening, services.context()));
        }
        return Optional.of(new Ae2InscriberTake(itemId, own, opening, services.context()));
    }

    // AE2 的设备各有本事：线缆格管安装段与部件，样板供应器有设置项，压印器收料出料；同一个类按种类答。
    @Override public Optional<MachineFeeding> feeding() { return Optional.of(this); }

    @Override public Optional<MachineSettings> settings() { return Optional.of(this); }

    @Override public Optional<MachineInstallations> installations() { return Optional.of(this); }

    @Override public Optional<MachineParts> parts() { return Optional.of(this); }

    private boolean isCableBus() {
        return kind.id().equals(Ae2MachineCatalog.CABLE_BUS);
    }

    private boolean isInscriber() {
        return kind.id().equals("ae2:inscriber");
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }

    /** 一格设备的读数换成运行状态，纯函数。 */
    static final class Ae2Readings {

        private Ae2Readings() {
        }

        // 线缆格的读数只写中间那根线缆是哪一种：线缆往哪几面连着由 ME 网表示，各面的部件由 parts 单列成设备。
        static MachineState state(Ae2Grid.GridCell cell) {
            Map<String, Object> readings = new LinkedHashMap<>();
            cell.controllerState().ifPresent(state -> readings.put("controller_state", state));
            cell.cableItem().ifPresent(cable -> readings.put("cable", cable));
            if (cell.controllerState().map("conflicted"::equals).orElse(false)) {
                return new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(), readings,
                        "控制器摆法不对（几块控制器拼的形状不合规），整张网停摆");
            }
            if (cell.powered().map(powered -> !powered).orElse(false)) {
                return new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(), readings, "没电");
            }
            if (cell.active().map(active -> !active).orElse(false)) {
                return new MachineState(MachineState.Activity.IDLE, null, null, List.of(), readings,
                        "有电但没分到频道：网里没有控制器时最多 8 个频道，或线缆的频道用满了");
            }
            boolean known = cell.powered().isPresent() || cell.active().isPresent();
            return new MachineState(known ? MachineState.Activity.RUNNING : MachineState.Activity.UNKNOWN, null, null,
                    List.of(), readings, known ? "" : "这种设备读不出有没有电");
        }

        // 压印器的四个工作格（顶模板、底模板、中间输入、输出）客户端已同步，完整写入 shown 与四槽读数供看机器直接查看。
        static MachineState inscriberState(Ae2Inscribers.Slots slots, Optional<Ae2Grid.GridCell> gridCell) {
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("top_slot", formatSlot(slots.top()));
            readings.put("bottom_slot", formatSlot(slots.bottom()));
            readings.put("input_slot", formatSlot(slots.middle()));
            readings.put("output_slot", formatSlot(slots.output()));
            List<MachineState.Shown> shown = slots.allShown();
            if (gridCell.isPresent() && gridCell.get().powered().map(powered -> !powered).orElse(false)) {
                return new MachineState(MachineState.Activity.OFFLINE, null, null, shown, readings, "没电");
            }
            boolean poweredKnown = gridCell.flatMap(Ae2Grid.GridCell::powered).orElse(false);
            MachineState.Activity activity = poweredKnown
                    ? (slots.middle() != null ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE)
                    : MachineState.Activity.UNKNOWN;
            String note = poweredKnown ? "" : "四个工作格已同步读出；内部存电与当前压印进度客户端不同步";
            return new MachineState(activity, null, null, shown, readings, note);
        }

        private static String formatSlot(MachineState.Shown slot) {
            return slot == null ? "empty" : slot.itemId() + " ×" + slot.count();
        }

        // 各面的部件按 AE2 画出来的亮法换成运行状态：有电有频道在干活，有电没频道停着，没电离线；按六个方向的固定顺序列。
        static List<MountedPart> parts(Ae2Grid.GridCell cell) {
            List<MountedPart> parts = new ArrayList<>();
            for (Direction side : Direction.values()) {
                Ae2Grid.PartView part = cell.parts().get(side);
                if (part == null) continue;
                MachineState state = part.active()
                        ? new MachineState(MachineState.Activity.RUNNING, null, null, List.of(), Map.of(), "")
                        : part.powered()
                        ? new MachineState(MachineState.Activity.IDLE, null, null, List.of(), Map.of(),
                                "有电但没分到频道")
                        : new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(), Map.of(), "没电");
                parts.add(new MountedPart(side, part.itemId(), state));
            }
            return parts;
        }
    }
}
