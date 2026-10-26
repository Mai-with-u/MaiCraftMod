// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 通用机械的一种机器或管道：机器按侧面配置给进出口与设置项（side.方向.介质），用配置器一下一下改；
 * 发电机不能配侧面，按模组写死的那几面给进出口（正面出电）；
 * 管道（线缆、管道、导管）按连着的面给进出口，连着别的管道、机器的面是通的，空着的面能再接。
 * 运行状态只说看得见的：每一面的侧面配置（化学品、热也列出来，只读），机器内部的东西要打开界面看。
 */
final class MekanismMachineType implements MachineType {

    private final MekanismMachineCatalog.Kind kind;
    private final MekanismCompat compat;
    private final PlayerServices services;
    private final Predicate<BlockState> covers;
    private final ConfiguratorClicks configurator;
    /** 每台分拣器上次打开界面时看见的规则与开关：规则只在开着界面时同步。 */
    private final Map<BlockPos, MekanismReads.SorterState> sorterSeen = new HashMap<>();
    /** 这次 machine_run 定下要往附加格投的换料：投的时候按它扣掉机器储罐里已有的，投完就去掉。 */
    private final Map<ConversionAt, ChemicalConversions.Plan> conversionFeeds = new HashMap<>();

    /** 哪台机器、投哪样换料。 */
    private record ConversionAt(BlockPos at, String itemId) {
    }

    MekanismMachineType(MekanismMachineCatalog.Kind kind, MekanismCompat compat, PlayerServices services,
            Predicate<BlockState> covers) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.covers = Objects.requireNonNull(covers, "covers");
        this.configurator = new ConfiguratorClicks(compat, services);
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return MekanismCompat.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) {
        return covers.test(state);
    }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        ClientLevel level = level();
        if (level == null) return List.of();
        Optional<MekanismReads.Transmitter> pipe = compat.transmitter(level, at);
        if (pipe.isPresent()) return pipePoints(pipe.get(), at);
        Optional<MekanismReads.SideConfig> config = compat.sideConfig(level, at);
        if (config.isPresent()) return SideSettings.points(config.get(), at);
        // 发电机不能配侧面：出电、收燃料的面是模组按朝向写死的。
        if (kind.role() == MachineRole.POWER_SOURCE) {
            return compat.fixedSides(level, at).map(fixed -> SideSettings.generatorPoints(fixed, at)).orElse(List.of());
        }
        return List.of();
    }

    // 管道每一面都能接：连着的面写清连着什么，空着的面能再接一段。导热管传的是热，不当能量线接。
    private static List<ExchangePoint> pipePoints(MekanismReads.Transmitter pipe, BlockPos at) {
        List<ExchangePoint> points = new ArrayList<>();
        for (String medium : pipe.media()) {
            if (medium.equals("heat")) continue;
            for (Direction side : Direction.values()) {
                String note = pipe.pipes().contains(side) ? "这一面连着别的管道"
                        : pipe.machines().contains(side) ? "这一面连着机器" : "这一面能再接";
                points.add(new ExchangePoint(at, side, SideSettings.mediumOf(medium), ExchangePoint.Flow.BOTH, note));
            }
        }
        return points;
    }

    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return MachineState.unknown("角色现在不在世界里，读不到机器");
        Optional<MekanismReads.Transmitter> pipe = compat.transmitter(level, at);
        if (pipe.isPresent()) {
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("pipes", (long) pipe.get().pipes().size());
            readings.put("machines", (long) pipe.get().machines().size());
            return new MachineState(MachineState.Activity.UNKNOWN, null, null, List.of(), readings,
                    "读不到存量：管道里存着多少电、多少流体客户端看不到，这里只看得出连着几面");
        }
        Optional<MekanismReads.SideConfig> config = compat.sideConfig(level, at);
        if (config.isEmpty() && kind.role() == MachineRole.POWER_SOURCE) return generatorState(level, at);
        if (config.isEmpty()) return MachineState.unknown("这一格读不到 Mekanism 机器（没加载，或已经不是它）");
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("facing", config.get().facing().getName());
        config.get().sides().forEach((medium, sides) -> {
            List<String> line = new ArrayList<>();
            sides.forEach((side, mode) -> {
                if (!mode.equals("none")) line.add(side.getName() + "=" + mode);
            });
            readings.put("side_" + medium, line.isEmpty() ? "none" : String.join("，", line));
        });
        return new MachineState(MachineState.Activity.UNKNOWN, null, null, List.of(), readings,
                "侧面配置看得见；在不在干活、进度、电量要打开界面看");
    }

    // 发电机看得见的只有朝向与出电的那几面；在不在发电、存了多少电、燃料剩多少只在界面里。
    private MachineState generatorState(ClientLevel level, BlockPos at) {
        Optional<MekanismReads.FixedSides> fixed = compat.fixedSides(level, at);
        if (fixed.isEmpty()) return MachineState.unknown("这一格读不到 Mekanism 发电机（没加载，或已经不是它）");
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("facing", fixed.get().facing().getName());
        List<String> out = fixed.get().sides().getOrDefault("energy", Set.of()).stream()
                .map(Direction::getName).sorted().toList();
        readings.put("energy_out", out.isEmpty() ? "none" : String.join("，", out));
        return new MachineState(MachineState.Activity.UNKNOWN, null, null, List.of(), readings,
                "出电的面看得见；在不在发电、存了多少电、燃料还剩多少要打开界面看");
    }

    @Override public Optional<String> installationProblem(Installation installation) {
        return Optional.of(kind.name() + "不装安装段");
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        return Optional.of("Mekanism 的机器没有装在面上的部件");
    }

    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        return Optional.empty();
    }

    @Override public boolean installed(Installation installation) {
        return false;
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        return Optional.empty();
    }

    @Override public boolean mounted(PartCell part) {
        return false;
    }

    // 侧面配置随同步包下发，随时读得到；分拣器的规则与开关只在开着界面时同步，读的是上次打开时看见的。
    @Override public List<MachineSetting> settings(BlockPos at) {
        ClientLevel level = level();
        if (level == null) return List.of();
        List<MachineSetting> settings = new ArrayList<>(compat.sideConfig(level, at).map(SideSettings::of)
                .orElse(List.of()));
        if (sorter()) {
            compat.openSorter(at).ifPresent(state -> sorterSeen.put(at.immutable(), state));
            settings.addAll(SorterSettings.of(Optional.ofNullable(sorterSeen.get(at))));
        }
        return settings;
    }

    // 改一项：分拣器的规则与开关开界面改；侧面配置是配置器点一下，读回与循环由 machine_configure 做。
    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        if (sorter() && (key.equals(SorterSettings.ITEM) || key.equals(SorterSettings.AUTO_EJECT))) {
            return Optional.of(new SorterChange(compat, services, at, key, value, permissions, sorterSeen));
        }
        return SideSettings.parse(key).map(parsed -> configurator.once(at, parsed, permissions));
    }

    private boolean sorter() {
        return kind.id().equals(MekanismMachineCatalog.MOD_ID + ":logistical_sorter");
    }

    // 投料：走过去点开机器的界面，把料从背包搬进去，机器自己把它放进收它的格；发电机的燃料也这样投进燃料格。
    // 能量立方、管道、太阳能这类不加工也不烧燃料的不收料。
    @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
        ClientLevel level = level();
        boolean takesFeed = kind.role() == MachineRole.PROCESSING || kind.fuel().kind() == FuelUse.Kind.FUEL;
        if (level == null || !takesFeed) return Optional.empty();
        String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
        // 投的是这次定下的换料：界面打开后先读机器储罐，已有的够几件就少投几件。
        ChemicalConversions.Plan conversion = conversionFeeds.remove(new ConversionAt(at.immutable(), itemId));
        MenuFeed.TopUp topUp = conversion == null ? null : (asked, notes) -> {
            ChemicalConversions.Deduction deduction = ChemicalConversions.deduct(conversion.chemicalId(),
                    conversion.perItem(), asked, compat.openMachineChemicals(at));
            if (!deduction.note().isEmpty()) notes.add(deduction.note());
            return deduction.skip();
        };
        return Optional.of(new MenuFeed(services, kind.name(), at, blockId, itemId, count, permissions, topUp));
    }

    // 配方要的化学品（灌注物这类）背包带不进去：加工机器从附加格吃物品再换成它。按化学品转化配方挑一样能换的，
    // 身上带够的先用；记下这一样，投的时候扣掉机器里已有的。查看器把化学品列成"别的东西"，挨个试能不能换。
    @Override public Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
        if (kind.role() != MachineRole.PROCESSING) return Optional.empty();
        Map<String, Integer> carried = new HashMap<>();
        for (BackpackStack stack : services.backpack().stacks()) carried.merge(stack.itemId(), stack.count(), Integer::sum);
        for (ShownStack option : ingredient.options()) {
            if (option.kind() != ShownStack.Kind.OTHER) continue;
            Optional<ChemicalConversions.Plan> plan = ChemicalConversions.plan(option.id(),
                    compat.conversionsTo(option.id()), carried, amount);
            if (plan.isEmpty()) continue;
            conversionFeeds.put(new ConversionAt(at.immutable(), plan.get().itemId()), plan.get());
            return Optional.of(new IngredientFeed(plan.get().itemId(), plan.get().count(),
                    ChemicalConversions.describe(plan.get(), amount)));
        }
        return Optional.empty();
    }

    // 发电机烧燃料或不吃料，别的机器按配方投料；见目录里每种的说明。
    @Override public FuelUse fuelUse(BlockPos at) {
        return kind.fuel();
    }

    // 机器的格子在界面里，站在外面看不见：做没做好要打开界面看。
    @Override public boolean outputVisible(BlockPos at) {
        return false;
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        return List.of();
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.empty();
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
