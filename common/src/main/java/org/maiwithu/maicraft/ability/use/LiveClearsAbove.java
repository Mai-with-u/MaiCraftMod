// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.interaction.BlockBreaking;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 锄地、压土径前打掉压在目标上面的野生草木的生产实现：许可问这个世界的保护判断与挖方块的档位，
 * 打掉用全仓那一份挖一格的动作（和挖方块、换气挖头顶同一套瞄准、挑工具、等确认）。
 */
final class LiveClearsAbove implements UseSeams.ClearsAbove {

    private final Supplier<BlockBreaking> diggings;
    private final Supplier<Protection> protection;
    private final ReadsCreatureSituation creatures;

    LiveClearsAbove(Supplier<BlockBreaking> diggings, Supplier<Protection> protection,
            ReadsCreatureSituation creatures) {
        this.diggings = Objects.requireNonNull(diggings, "diggings");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.creatures = Objects.requireNonNull(creatures, "creatures");
    }

    // 打草和挖方块是同一回事：别人的、受保护的不动，change_blocks 到 natural 才动（草木是地图上长的）。
    @Override public Optional<Problem> allowed(Permissions permissions, BlockPos cell, String blockId) {
        Protection now = protection.get();
        if (now == null) {
            return Optional.of(Problem.of(Problem.Kind.WRONG_TIME,
                    "这一刻读不到这个世界的保护判断，不动 " + cell.toShortString() + " 的 " + blockId, null));
        }
        return new PermissionCheck(now, creatures).blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                WorldPosition.here(cell.getX(), cell.getY(), cell.getZ()), blockId);
    }

    @Override public Action clear(BlockPos cell, String blockId) {
        return new ClearPlant(diggings.get(), cell, blockId);
    }

    /** 打掉一格草木：那一格不再是原来的东西就算做完，挖下来的东西要不要都行。 */
    private static final class ClearPlant implements Action {
        private final BlockBreaking breaking;
        private final BlockPos cell;
        private final String blockId;
        private boolean aimed;

        ClearPlant(BlockBreaking breaking, BlockPos cell, String blockId) {
            this.breaking = breaking;
            this.cell = cell;
            this.blockId = blockId;
        }

        // 先看那一格还在不在（可能被别人踩掉、被水冲掉），还在就瞄准它挖；挖开一格就算打掉了。
        @Override public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            Level level = player == null ? null : player.level();
            if (level != null && !blockId.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(cell).getBlock())
                    .toString())) {
                return ActionStatus.done();
            }
            if (!aimed) {
                aimed = true;
                breaking.aimAt(cell);
                // 只为让出那一格：挖不到掉落也行，不为掉落换剪刀。
                breaking.wantsDrop(false);
            }
            ActionStatus status = breaking.tick(context);
            if (status instanceof ActionStatus.Running running && running.progressed()) return ActionStatus.done();
            return status;
        }

        @Override public void pause() {
            breaking.pause();
        }

        @Override public void close() {
            breaking.close();
        }

        @Override public String describe() {
            return "打掉 " + cell.toShortString() + " 的 " + blockId;
        }
    }
}
