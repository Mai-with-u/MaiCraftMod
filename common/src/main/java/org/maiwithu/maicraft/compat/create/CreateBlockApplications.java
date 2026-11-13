// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.List;
import java.util.Objects;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplication;
import org.maiwithu.maicraft.behavior.acquire.spi.BlockApplications;

/**
 * 机械动力手工涂抹（create:item_application）配方表的联动供数：
 * 把客户端配方表里读到的手工涂抹配方翻成玩家行为层的 {@link BlockApplication}，
 * 选点摆块、换手持物右键涂抹与挖回拾取都由玩家行为层完成。
 */
final class CreateBlockApplications implements BlockApplications {

    static final AcquireVia VIA = new AcquireVia(
            "item_application", "机械动力的手工涂抹：放下基底方块，拿材料右键变成机壳等再挖回");

    private final CreateCompat compat;

    CreateBlockApplications(CreateCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override
    public AcquireVia via() {
        return VIA;
    }

    @Override
    public List<BlockApplication> recipes(Level level) {
        if (!(level instanceof ClientLevel clientLevel)) return List.of();
        return compat.itemApplications(clientLevel).stream()
                .filter(item -> !item.baseItems().isEmpty() && !item.heldItems().isEmpty() && item.resultCount() > 0)
                .map(item -> new BlockApplication(
                        item.id(),
                        item.baseItems(),
                        item.heldItems(),
                        item.keepHeldItem(),
                        item.result(),
                        item.resultCount(),
                        item.placesResultBlock()))
                .toList();
    }
}
