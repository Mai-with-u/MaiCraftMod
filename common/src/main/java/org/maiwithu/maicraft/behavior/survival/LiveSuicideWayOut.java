// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;

/**
 * 寻死出路的生产实现：读这个世界的死亡不掉落规则与角色此刻的游戏模式。
 * 规则读端和 observe(self) 共用一份、每刻读着，走投无路要提的那一刻就有现成的值，不会碰上"还在读"。
 */
public final class LiveSuicideWayOut implements SuicideWayOut {

    private final Supplier<PlayerContext> context;
    private final KeepInventoryRule keepInventory;

    public LiveSuicideWayOut(Supplier<PlayerContext> context, KeepInventoryRule keepInventory) {
        this.context = Objects.requireNonNull(context, "context");
        this.keepInventory = Objects.requireNonNull(keepInventory, "keepInventory");
    }

    // 读不到角色（不在世界、人接管中）就不提：说不清此刻是什么模式。
    @Override public Optional<String> line() {
        PlayerContext current = context.get();
        LocalPlayer player = current == null ? null : current.localPlayer();
        if (player == null) return Optional.empty();
        return SuicideWayOut.line(keepInventory.read(), player.isCreative(), player.isSpectator(),
                player.level().getLevelData().isHardcore());
    }
}
