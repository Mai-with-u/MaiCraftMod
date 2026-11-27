// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkLoad;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 扩展（ExtendedAE）通用机器类型封装：按 {@link ExtendedAeMachineCatalog.Kind} 认领方块，
 * 将具体设备的连网面、状态读数、投料取货与设置项分发给对应的 {@link ExtendedAeDeviceOps} 委托。
 */
final class ExtendedAeMachineType implements MachineType, MachineFeeding, MachineSettings {

    private final ExtendedAeMachineCatalog.Kind kind;
    private final ExtendedAeCompat compat;
    private final PlayerServices services;
    private final Set<Block> blocks;
    private final ExtendedAeDeviceOps ops;

    ExtendedAeMachineType(ExtendedAeMachineCatalog.Kind kind, ExtendedAeCompat compat, PlayerServices services,
            Set<Block> blocks, ExtendedAeDeviceOps ops) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.blocks = Set.copyOf(Objects.requireNonNull(blocks, "blocks"));
        this.ops = ops;
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return ExtendedAeCompat.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) { return blocks.contains(state.getBlock()); }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        ClientLevel level = level();
        if (ops != null) {
            Optional<List<ExchangePoint>> custom = ops.exchangePoints(state, at, level);
            if (custom.isPresent()) return List.copyOf(custom.get());
        }
        if (!kind.onGrid()) {
            return List.of(new ExchangePoint(at, null, ExchangePoint.Medium.ITEM, ExchangePoint.Flow.BOTH,
                    "不接 ME 网的独立方块：打开界面或经管道交互"));
        }
        List<ExchangePoint> points = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            points.add(new ExchangePoint(at, side, null, ExtendedAeDeviceOps.ME_NETWORK,
                    ExchangePoint.Flow.BOTH, "这一面可连 ME 线缆"));
        }
        return List.copyOf(points);
    }

    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (ops != null) {
            Optional<MachineState> custom = ops.state(at, level);
            if (custom.isPresent()) return custom.get();
        }
        if (level == null) return MachineState.unknown("角色现在不在世界里，读不到 " + kind.name());
        Optional<ExtendedAeReads.NetworkDeviceView> net = compat.networkDeviceAt(level, at);
        if (net.isEmpty()) return MachineState.unknown("这一格读不到 " + kind.name());
        return new MachineState(ExtendedAeNetworkOps.activityOf(net), null, null, List.of(),
                Map.of("powered", net.get().powered(), "active", net.get().active()), "");
    }

    @Override public Optional<NetworkLoad> load(BlockState state) {
        if (!kind.onGrid() || kind.role() == MachineRole.CABLE) return Optional.empty();
        return Optional.of(new NetworkLoad(ExtendedAeDeviceOps.ME_NETWORK.id(), 1, 0, "频道"));
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        return ops == null ? List.of() : ops.output(at, level());
    }

    @Override public boolean outputVisible(BlockPos at) {
        return ops == null || ops.outputVisible(at);
    }

    @Override public Optional<MachineFeeding> feeding() {
        return ops != null && ops.feeding(BlockPos.ZERO, services).isPresent()
                ? Optional.of(this) : Optional.empty();
    }

    @Override public Optional<MachineSettings> settings() {
        return ops != null && ops.settings(BlockPos.ZERO, services).isPresent()
                ? Optional.of(this) : Optional.empty();
    }

    @Override public FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
        return feedingDelegate(at).map(f -> f.plan(at, recipe, batches, permissions))
                .orElseGet(() -> MachineFeeding.super.plan(at, recipe, batches, permissions));
    }

    @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
        return feedingDelegate(at).flatMap(f -> f.feed(at, item, permissions));
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return feedingDelegate(at).flatMap(f -> f.take(at, itemId, count, permissions));
    }

    @Override public Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
        return feedingDelegate(at).flatMap(f -> f.fullRun(at, request, permissions));
    }

    @Override public FuelUse fuelUse(BlockPos at) {
        return feedingDelegate(at).map(f -> f.fuelUse(at)).orElse(FuelUse.RECIPE);
    }

    @Override public Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
        return feedingDelegate(at).flatMap(f -> f.ingredientFeed(at, ingredient, amount));
    }

    @Override public List<MachineSetting> settings(BlockPos at) {
        return settingsDelegate(at).map(s -> s.settings(at)).orElse(List.of());
    }

    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        return settingsDelegate(at).flatMap(s -> s.change(at, key, value, permissions));
    }

    private Optional<MachineFeeding> feedingDelegate(BlockPos at) {
        return ops == null ? Optional.empty() : ops.feeding(at, services);
    }

    private Optional<MachineSettings> settingsDelegate(BlockPos at) {
        return ops == null ? Optional.empty() : ops.settings(at, services);
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
