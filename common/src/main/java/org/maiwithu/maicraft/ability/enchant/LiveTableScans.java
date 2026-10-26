// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.enchant;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ScanTargets;

/**
 * 找台读端的生产实现：找台与核对都走扫描服务的同一份索引，与 find 找方块是同一个认法。
 * 每次查询前按维度补登记附魔台：索引只索引登记过的方块种类，不登记就永远查不到，
 * 会把现场摆着的台子误报成"半径内没有"。
 */
final class LiveTableScans implements TableScans {

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private final ScanTargets registered;

    LiveTableScans(BlockScanService scans, Supplier<PlayerContext> context) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
        this.registered = new ScanTargets(scans);
    }

    @Override public Found nearest(int chunkRadius, int buildBudget) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null || current.localPlayer() == null) {
            return new Found(List.of(), false);
        }
        // 查询前补登记：这个维度还没登记过附魔台就先登记，索引才认得出这一格摆的是什么。
        registered.ensure(level, Set.of(Blocks.ENCHANTING_TABLE));
        var result = scans.query(level, current.localPlayer().blockPosition(),
                Set.of(Blocks.ENCHANTING_TABLE), 1, Math.max(1, chunkRadius), buildBudget);
        return new Found(List.copyOf(result.hits()), result.complete());
    }

    @Override public At inspect(BlockPos pos) {
        PlayerContext current = context.get();
        ClientLevel level = current == null ? null : current.level();
        if (level == null) {
            return new At(false, "读不到现场");
        }
        var state = level.getBlockState(pos);
        return new At(state.is(Blocks.ENCHANTING_TABLE), state.getBlock().getName().getString());
    }
}
