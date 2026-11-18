// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 亮度读取的生产实现：从当刻的客户端世界读光照引擎的方块光层。
 * 每次读都从当刻的角色上下文取世界，不留到下一刻；区块没加载时如实说没加载。
 */
public final class ClientLightLevels implements ReadsLightLevels {

    private final Supplier<PlayerContext> contexts;

    public ClientLightLevels(Supplier<PlayerContext> contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    /**
     * 一格方块露出来的那几面挨着的格（不是整块挡光的方块）里最亮的那一格亮度（0..15）：
     * 方块光与按当前天色折减后的天空光取大，火把、岩浆、发光方块照到的都算。看洞壁与矿脉看不看得清共用。
     */
    public static int exposedFaceLight(ClientLevel level, BlockPos block) {
        if (level == null || block == null || !level.isLoaded(block)) {
            return 0;
        }
        int light = 0;
        for (Direction face : Direction.values()) {
            BlockPos beside = block.relative(face);
            if (!level.getBlockState(beside).isSolidRender(level, beside)) {
                light = Math.max(light, level.getMaxLocalRawBrightness(beside));
            }
        }
        return light;
    }

    @Override public OptionalInt blockLight(BlockPos pos) {
        PlayerContext context = contexts.get();
        if (context == null || context.level() == null || !context.level().isLoaded(pos)) {
            return OptionalInt.empty();
        }
        // 方块光只看光源，不受昼夜天气影响；那一列没同步完时光照引擎自己会给旧值，等几刻再量是调用方的纪律。
        return OptionalInt.of(context.level().getBrightness(LightLayer.BLOCK, pos));
    }

    @Override public OptionalInt skyLight(BlockPos pos) {
        PlayerContext context = contexts.get();
        if (context == null || context.level() == null || !context.level().isLoaded(pos)) {
            return OptionalInt.empty();
        }
        // 天空光层的原始值：只看头顶遮没遮住，不随昼夜天气变（昼夜的折算在别处，这里不掺）。
        return OptionalInt.of(context.level().getBrightness(LightLayer.SKY, pos));
    }
}
