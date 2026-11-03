// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gomoku;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.gomoku.spi.GomokuBoards;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 找棋盘的生产实现：观察编号从场景换回那一格，逐个问联动登记的下五子棋的办法；
 * 谁都不认时如实说它是什么方块、它所属的模组联动登没登记上。
 */
public final class LiveBoards implements FindsBoard {

    private final Supplier<Scene> scene;
    private final Supplier<PlayerContext> contexts;
    private final Supplier<List<GomokuBoards>> boards;
    private final Function<String, Optional<String>> whyNotWorking;

    /**
     * @param boards        联动登记的下五子棋的办法：进世界时建好的那一份
     * @param whyNotWorking 某个模组装了、联动却没在工作时为什么；联动清单里没有、或好好登记着时给空
     */
    public LiveBoards(Supplier<Scene> scene, Supplier<PlayerContext> contexts, Supplier<List<GomokuBoards>> boards,
            Function<String, Optional<String>> whyNotWorking) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.boards = Objects.requireNonNull(boards, "boards");
        this.whyNotWorking = Objects.requireNonNull(whyNotWorking, "whyNotWorking");
    }

    @Override public Found find(String seenId) {
        Scene current = scene.get();
        PlayerContext player = contexts.get();
        if (current == null || player == null || player.level() == null) return new Found.Gone();
        Optional<SeenRegistry.Entry> entry = current.seen().get(seenId);
        if (entry.isEmpty()) return new Found.Gone();
        WorldPosition position = entry.get().position();
        BlockPos at = new BlockPos(position.x(), position.y(), position.z());
        BlockState state = player.level().getBlockState(at);
        // 编号登记的那一格已经空了：棋盘被收起来了。
        if (state.isAir()) return new Found.Gone();
        for (GomokuBoards each : boards.get()) {
            Optional<GomokuBoards.Board> board = each.board(player, at);
            if (board.isPresent()) return new Found.Board(board.get());
        }
        // 谁都不认：按方块 ID 的命名空间认它属于哪个模组，那个模组装了却没登记上时如实说。
        ResourceLocation block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new Found.NotBoard(block.toString(), block.getNamespace(),
                whyNotWorking.apply(block.getNamespace()).orElse(null));
    }
}
