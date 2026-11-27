// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.advancedae;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
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
 * 高级 AE（AdvancedAE）的反应仓机器类型：
 * 认领 {@code advanced_ae:reaction_chamber}，六面接入 ME 电网并占用 1 个频道；
 * 将状态读数、流体桶换算与直点倒桶、九格固体投料、成品与流体桶收货及配置项委托给 {@link ReactionChamberOps}。
 */
final class AdvancedAeMachineType implements MachineType, MachineFeeding, MachineSettings {

    private final AdvancedAeMachineCatalog.Kind kind;
    private final PlayerServices services;
    private final Set<Block> blocks;
    private final ReactionChamberOps ops;

    AdvancedAeMachineType(AdvancedAeMachineCatalog.Kind kind, AdvancedAeCompat compat,
                          PlayerServices services, Set<Block> blocks) {
        this(kind, services, blocks, new ReactionChamberOps(compat, services));
    }

    AdvancedAeMachineType(AdvancedAeMachineCatalog.Kind kind, PlayerServices services,
                          Set<Block> blocks, ReactionChamberOps ops) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.services = Objects.requireNonNull(services, "services");
        this.blocks = Set.copyOf(Objects.requireNonNull(blocks, "blocks"));
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    @Override public String id() { return kind.id(); }

    @Override public String name() { return kind.name(); }

    @Override public String modId() { return AdvancedAeCompat.MOD_ID; }

    @Override public MachineRole role() { return kind.role(); }

    @Override public boolean covers(BlockState state) {
        return blocks.contains(state.getBlock());
    }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        return ops.exchangePoints(at);
    }

    @Override public Optional<NetworkLoad> load(BlockState state) {
        return Optional.of(new NetworkLoad(ReactionChamberOps.ME_NETWORK.id(), 1, 0, "频道"));
    }

    @Override public MachineState state(BlockPos at) {
        return ops.state(at, level());
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        return ops.output(at, level());
    }

    // 反应仓实体同步流自带输出物品槽与双流体槽，站外面直接可见。
    @Override public boolean outputVisible(BlockPos at) {
        return true;
    }

    @Override public Optional<MachineFeeding> feeding() {
        return Optional.of(this);
    }

    @Override public Optional<MachineSettings> settings() {
        return Optional.of(this);
    }

    @Override public FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
        return ops.plan(at, recipe, batches, permissions);
    }

    @Override public Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
        return ops.ingredientFeed(at, ingredient, amount);
    }

    @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
        return ops.feed(at, item, permissions);
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return ops.take(at, itemId, count, permissions);
    }

    @Override public Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
        return ops.fullRun(at, request, permissions);
    }

    @Override public List<MachineSetting> settings(BlockPos at) {
        return ops.settings(at);
    }

    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        return ops.change(at, key, value, permissions);
    }

    private ClientLevel level() {
        PlayerContext current = services.context().get();
        return current == null ? null : current.level();
    }
}
