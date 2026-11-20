// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.behavior.construction.spi.PlacementRules;

/**
 * 联动放置规则包装层测试：验证 turnsTo 在联动启用时正确转发底层扳手转正方案，
 * 联动停用或底层抛模组接口对不上时安全收住交回空。
 */
class CompatPlacementRulesTest {

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class StubModule extends CompatModule {
        StubModule() {
            super("stub", "测试联动");
        }

        @Override public void contribute(CompatRegistry registry) {}

        void trip() {
            try {
                call("模拟接口变更", () -> {
                    throw new NoSuchMethodError("missing");
                });
            } catch (ModApiMismatch ignored) {
                // 触发联动停用。
            }
        }
    }

    @Test
    void 联动启用时正确转发扳手转正方案() {
        StubModule module = new StubModule();
        PlacementRules.Turns expected = new PlacementRules.Turns(Items.STICK, List.of(Direction.UP, Direction.NORTH));
        PlacementRules inner = new PlacementRules() {
            @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
                return Optional.empty();
            }

            @Override public List<BlockPos> placeAfter(BlockState state) {
                return List.of();
            }

            @Override public Optional<Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
                return Optional.of(expected);
            }
        };
        CompatPlacementRules wrapped = new CompatPlacementRules(module, inner);

        assertEquals(Optional.of(expected), wrapped.turnsTo(Blocks.STONE.defaultBlockState(), state -> true));
    }

    @Test
    void 联动停用或接口对不上时turnsTo交回空() {
        StubModule module = new StubModule();
        PlacementRules throwing = new PlacementRules() {
            @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
                return Optional.empty();
            }

            @Override public List<BlockPos> placeAfter(BlockState state) {
                return List.of();
            }

            @Override public Optional<Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
                throw new ModApiMismatch("测试联动接口对不上", new NoSuchMethodError("turns"));
            }
        };
        CompatPlacementRules wrapped = new CompatPlacementRules(module, throwing);
        assertTrue(wrapped.turnsTo(Blocks.STONE.defaultBlockState(), state -> true).isEmpty());

        // 联动停用后不再调用底层规则，直接返回空。
        module.trip();
        PlacementRules alwaysReturns = new PlacementRules() {
            @Override public Optional<Placement> placementFor(BlockState state, BlockPos at, ReadsBlocks world) {
                return Optional.empty();
            }

            @Override public List<BlockPos> placeAfter(BlockState state) {
                return List.of();
            }

            @Override public Optional<Turns> turnsTo(BlockState placed, Predicate<BlockState> wanted) {
                return Optional.of(new PlacementRules.Turns(Items.STICK, List.of(Direction.UP)));
            }
        };
        assertTrue(new CompatPlacementRules(module, alwaysReturns)
                .turnsTo(Blocks.STONE.defaultBlockState(), state -> true).isEmpty());
    }
}
