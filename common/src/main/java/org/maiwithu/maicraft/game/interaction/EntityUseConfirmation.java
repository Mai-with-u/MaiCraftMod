// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 右键一只生物算没算生效：全仓只有这一份，游戏接口层的交互提交与 use 都用它。
 *
 * <p>出手前冻结现场，之后下面任一条成立就算游戏接了这一下：主手的东西变了（挤到奶、喂掉一个）、打开了新界面
 * （村民交易、女仆界面）、角色骑上或下了坐骑、目标的乘客变了（把动物塞进船）、角色身上的乘客变了（拿鞍抱起女仆）、
 * 目标的坐骑变了、目标的坐姿变了（让狼、猫、女仆坐下或站起）、目标的驯服者变了（驯服了它）、目标当场不在了
 * （用魂符收起女仆、取回墓碑）、角色背包里的东西多了（取回墓碑）。
 *
 * <p>只认原版实体事实，不读联动的观察，也不认"它看起来变了"；羊有没有被剪毛这类结果不在这份观察里。
 */
public final class EntityUseConfirmation {

    private EntityUseConfirmation() {}

    /**
     * 右键这只生物之前冻结现场，给出之后怎样算生效。
     *
     * @param player 角色
     * @param target 要右键的生物
     * @param hand   用哪只手
     */
    public static InteractionConfirmation of(Player player, Entity target, InteractionHand hand) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        Snapshot before = Snapshot.of(player, target);
        ItemStack heldBefore = player.getItemInHand(hand).copy();
        int menuBefore = player.containerMenu.containerId;
        int targetId = target.getId();
        return InteractionConfirmation.anyOf(
                InteractionConfirmation.heldItemChanged(hand, heldBefore),
                InteractionConfirmation.menuChanged(menuBefore),
                context -> {
                    Player self = context.localPlayer();
                    Entity now = context.level() == null ? null : context.level().getEntity(targetId);
                    // 目标当场不在了：被收进魂符、墓碑被取走，都是这一下带来的。
                    if (now == null || now.isRemoved()) return InteractionConfirmation.Verdict.APPLIED;
                    return before.differsFrom(Snapshot.of(self, now))
                            ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
                });
    }

    /**
     * 不对着任何生物、只用手上的东西之前冻结现场：主手的东西变了，或角色身上的乘客变了（拿鞍对空处右键放下抱着的女仆），
     * 都算生效。
     */
    public static InteractionConfirmation ofHeldItem(Player player, InteractionHand hand) {
        Objects.requireNonNull(player, "player");
        ItemStack heldBefore = player.getItemInHand(hand).copy();
        List<UUID> carriedBefore = passengers(player);
        return InteractionConfirmation.anyOf(
                InteractionConfirmation.heldItemChanged(hand, heldBefore),
                context -> passengers(context.localPlayer()).equals(carriedBefore)
                        ? InteractionConfirmation.Verdict.PENDING : InteractionConfirmation.Verdict.APPLIED);
    }

    /**
     * 出手前后要比的几项原版实体事实。
     *
     * @param selfVehicle      角色骑着谁；没骑为 null
     * @param selfPassengers   角色身上驮着谁
     * @param targetPassengers 目标身上驮着谁
     * @param targetVehicle    目标骑着谁；没骑为 null
     * @param targetSitting    目标是不是坐着（只有听令坐下的生物有坐姿）
     * @param targetOwner      目标被谁驯服；没驯服为 null
     * @param carried          角色背包里一共几件东西
     */
    record Snapshot(UUID selfVehicle, List<UUID> selfPassengers, List<UUID> targetPassengers, UUID targetVehicle,
            boolean targetSitting, UUID targetOwner, int carried) {

        static Snapshot of(Player self, Entity target) {
            return new Snapshot(
                    self.getVehicle() == null ? null : self.getVehicle().getUUID(),
                    passengers(self),
                    passengers(target),
                    target.getVehicle() == null ? null : target.getVehicle().getUUID(),
                    target instanceof TamableAnimal tamable && tamable.isInSittingPose(),
                    target instanceof OwnableEntity ownable ? ownable.getOwnerUUID() : null,
                    itemsCarried(self.getInventory()));
        }

        /** 现在和出手前比：坐骑、乘客、坐姿、驯服者哪一项变了，或背包里的东西多了。 */
        boolean differsFrom(Snapshot now) {
            return !Objects.equals(selfVehicle, now.selfVehicle)
                    || !selfPassengers.equals(now.selfPassengers)
                    || !targetPassengers.equals(now.targetPassengers)
                    || !Objects.equals(targetVehicle, now.targetVehicle)
                    || targetSitting != now.targetSitting
                    || !Objects.equals(targetOwner, now.targetOwner)
                    || now.carried > carried;
        }
    }

    private static List<UUID> passengers(Entity entity) {
        return entity.getPassengers().stream().map(Entity::getUUID).toList();
    }

    // 背包里一共几件：主背包、快捷栏、盔甲与副手都算，取回墓碑时东西可能落进任意一格。
    private static int itemsCarried(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += inventory.getItem(slot).getCount();
        }
        return total;
    }
}
