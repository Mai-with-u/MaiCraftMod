// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;

/**
 * 联动模组交来的放置规则，登记表给它包的一层：模组停用后什么都不答（施工按原版默认放），
 * 碰到模组接口对不上也在这里收住，同样当作不答。
 */
public final class CompatPlacementRules implements PlacementRules {

    private final CompatModule module;
    private final PlacementRules rules;

    public CompatPlacementRules(CompatModule module, PlacementRules rules) {
        this.module = Objects.requireNonNull(module, "module");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
        return DegradedAnswer.ask(module, () -> rules.placementFor(state, at, world), Optional.empty());
    }

    @Override public List<BlockPos> placeAfter(BlockState state) {
        return DegradedAnswer.ask(module, () -> rules.placeAfter(state), List.of());
    }

    @Override public BlockState afterPlacing(Item item, BlockState placed, ClientLevel level, BlockPos at, Direction playerFacing) {
        return DegradedAnswer.ask(module, () -> rules.afterPlacing(item, placed, level, at, playerFacing), placed);
    }

    // 联动启用时转发扳手转正方案（如机械动力方向机器放下后用扳手点几面转到位）；停用或接口对不上时交回空。
    @Override public Optional<Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
        return DegradedAnswer.ask(module, () -> rules.turnsTo(placed, wanted), Optional.empty());
    }

}
