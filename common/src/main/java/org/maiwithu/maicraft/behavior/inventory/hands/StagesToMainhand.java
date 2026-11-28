// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.hands;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.world.entity.player.Inventory;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把一件东西备到主手的动作，跨刻推进到主手拿的是它为止：身上有就换到主手，换手做完了主手还拿的不是它
 * 就留在原地重判；身上没有时按拿到物品的引擎去取（要几件、为了一声什么由调用方说），取到了而主手还空着
 * 就接着换手。只准用身上现成的那种情形（给工具补电不另造新件）身上没有就如实失败。
 * 换不成、取不到都以原样的问题收场，绝不装作备好了。
 */
public final class StagesToMainhand implements Action {

    private final PlayerServices services;
    private final Permissions permissions;
    private final String itemId;
    private final int count;
    private final String acquireLabel;
    /** 只准用身上现成时、身上没有的失败说明，例如"身上没有待补电的 ae2:portable_item_cell_1k"。 */
    private final String missingMessage;
    private final Function<PlayerContext, Inventory> wholeInventory;
    private Action current;

    /**
     * @param itemId        要备到主手的东西
     * @param count         身上没有时去取几件；换手只动现成的那件，不受它影响
     * @param acquireLabel  去取时对引擎说这是为了什么，例如"充能器充能原料"
     * @param missingMessage 只准用身上现成时、身上没有的失败说明；给 null 表示身上没有就去取
     * @param wholeInventory 从当刻角色读背包本体（含盔甲与副手格）的办法；生产上就是角色的 Inventory，离线测试换替身
     */
    public StagesToMainhand(PlayerServices services, Permissions permissions, String itemId, int count,
            String acquireLabel, String missingMessage, Function<PlayerContext, Inventory> wholeInventory) {
        this.services = Objects.requireNonNull(services, "services");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.count = count;
        this.acquireLabel = Objects.requireNonNull(acquireLabel, "acquireLabel");
        this.missingMessage = missingMessage;
        this.wholeInventory = Objects.requireNonNull(wholeInventory, "wholeInventory");
    }

    @Override public ActionStatus tick(TickContext tick) {
        PlayerContext player = tick.player();
        if (player == null) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "角色不在世界里，无法备手"));
        }
        if (current == null) {
            Inventory inventory = wholeInventory.apply(player);
            if (WholeInventory.count(inventory, itemId) > 0) {
                Optional<Action> hold = services.toMainhand().moveToMainhand(itemId);
                if (hold.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "无法将 " + itemId + " 换到主手"));
                }
                current = hold.get();
            } else if (missingMessage != null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, missingMessage));
            } else {
                current = services.needs().actionFor(
                        new ItemRequest(WantedItem.ofItem(itemId), count, acquireLabel), permissions);
            }
        }
        ActionStatus status = current.tick(tick);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) return failed;
        return WholeInventory.mainhandHolds(wholeInventory.apply(player), itemId)
                ? ActionStatus.done() : ActionStatus.progressed();
    }

    @Override public void pause() { if (current != null) current.pause(); }

    @Override public void close() { if (current != null) { current.close(); current = null; } }

    @Override public Interruptibility interruptibility() {
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() { return "备 " + itemId + " 到主手"; }
}
