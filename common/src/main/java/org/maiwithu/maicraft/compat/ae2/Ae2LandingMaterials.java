// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 没带落地料时，拿出身上的 AE2 无线终端，从它绑定的网络里现取一件（水桶）。
 * 只开随身那件、不走去装着的终端：坠落中每一刻都在往下掉，走开就来不及接住自己。
 * 终端没绑访问点、没电、没连上网络时取不到，动作会如实失败，落地防护照旧说没有东西接。
 */
public final class Ae2LandingMaterials implements LandingMaterials {

    private final Ae2Compat compat;
    private final Supplier<PlayerServices> services;
    /** 进世界后建一次；换世界（services 换了）就重建。 */
    private PlayerServices builtFor;
    private MenuTerminalTakes takes;

    public Ae2LandingMaterials(Ae2Compat compat, Supplier<PlayerServices> services) {
        this.compat = compat;
        this.services = services;
    }

    @Override public Optional<Action> fetch(PlayerContext context, List<String> itemIds) {
        PlayerServices current = services.get();
        if (current == null) return Optional.empty();
        String terminal = carriedTerminal(context);
        if (terminal == null) return Optional.empty();
        if (takes == null || builtFor != current) {
            builtFor = current;
            takes = new MenuTerminalTakes(compat, current, new SeenNetworkStock(), Instant::now);
        }
        for (String itemId : itemIds) {
            if (itemId == null || itemId.isBlank()) continue;
            // 空中不合成：等网络把水桶做出来时人已经落地了；网络里现成有才拿。
            Optional<Action> action = takes.takeWirelessNow(terminal,
                    new ItemRequest(WantedItem.ofItem(itemId), 1, "落地自救"));
            if (action.isPresent()) return action;
        }
        return Optional.empty();
    }

    @Override public String describe() {
        return "身上的无线终端";
    }

    // 身上（主背包、快捷栏、副手）第一件绑过访问点的无线终端。
    private String carriedTerminal(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        if (player == null) return null;
        for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && compat.linkedWirelessTerminal(stack)) {
                return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty() && compat.linkedWirelessTerminal(offhand)) {
            return BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString();
        }
        return null;
    }
}
