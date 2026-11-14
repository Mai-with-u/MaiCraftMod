// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.kernel.result.Change;

/**
 * 确认生效的一下交互留下了什么：全按出手后读到的现场记，不按物品名猜。
 *
 * <p>手上的东西按出手前后的对照记：变成了别的（空桶变水桶、药水变空瓶）记用掉一件、得到一件；
 * 同一种少了几个（骨粉、染料）记用掉几个。方块按效果格此刻实际是什么记：倒下去的水遇上岩浆
 * 变成了黑曜石，就记黑曜石。对实体用时骑上了记移动，其余记对实体的影响。
 */
final class UseChanges {

    /** 带方块实体但只是收纳摆设或感测装置、不是加工容器的原版方块：排除后其余吃掉手中物品的方块实体按未知加工容器如实说明。 */
    private static final Set<String> NON_PROCESSOR_BLOCK_ENTITIES = Set.of(
            "minecraft:jukebox", "minecraft:lectern", "minecraft:chiseled_bookshelf",
            "minecraft:decorated_pot", "minecraft:ender_chest", "minecraft:beacon",
            "minecraft:conduit", "minecraft:enchanting_table", "minecraft:bell");

    private UseChanges() {}

    /**
     * 记下这一下确认生效后的变化。
     *
     * @param record      记变化的入口（任务的变化清单）
     * @param world       出手后的现场
     * @param facilities  设施分类（排除普通箱桶与已知工作台）
     * @param target      目标；只对手上的东西用时为 null
     * @param interaction 这一下交互：出手前冻结的手上东西与效果格
     * @param result      交互的结论与现场说明
     * @return 这一下是不是把手上的东西塞进了一个不认识的加工容器（有方块实体、吃掉了物品且方块本身未变）
     */
    static boolean recordApplied(Consumer<Change> record, UseSeams.ReadsWorld world, FacilityKinds facilities,
            ResolvedTarget target, UseSeams.Built.Ready interaction, InteractionResult result) {
        UseSeams.ReadsWorld.Held after = world.heldItem().orElse(null);
        boolean handConsumed = recordHandChange(record, interaction.heldBefore(), after);
        if (target == null) {
            return false;
        }
        if (target.isEntity()) {
            // 右键马、船、矿车就是骑上去：坐骑换成了它，记成角色的移动；剪毛、挤奶这类记对实体的影响。
            if (world.riding(target.entityId())) {
                record.accept(new Change(Change.Kind.MOVED, target.typeId(), 1, "骑上了" + target.describe()));
            } else {
                record.accept(new Change(Change.Kind.ENTITY_AFFECTED, target.typeId(), 1, result.scene()));
            }
            return false;
        }
        BlockPos effect = interaction.effectCell();
        if (effect != null && !effect.equals(target.cell())) {
            // 倒流体、点火：东西落在被点面外面那一格，记那一格此刻实际出现的方块；落在自己站的格子里时写明，
            // LLM 看到"岩浆落在自己脚下"就知道接下来的掉血、着火是自己点的，不是别的危险。
            String placed = world.blockId(effect).orElse("未加载");
            String onSelf = ItemUseAim.overlapsPlayer(effect, world.feet()) ? "，正是自己站的地方" : "";
            record.accept(new Change(Change.Kind.BLOCK_PLACED, placed, 1, "落在 " + effect.toShortString() + onSelf));
            return false;
        }
        // 效果就在目标本身：门开了、锄成了耕地、点着了营火，或把原料塞进了带方块实体的加工容器。
        String now = world.blockId(target.cell()).orElse("未加载");
        boolean placedIntoProcessor = handConsumed && now.equals(target.typeId())
                && isUnknownProcessorBlock(world, facilities, target);
        String note = placedIntoProcessor
                ? "放进去了，不确认会不会做出东西"
                : now.equals(target.typeId()) ? result.scene() : target.typeId() + " 变成了 " + now;
        record.accept(new Change(Change.Kind.BLOCK_CHANGED, target.typeId(), 1, note));
        return placedIntoProcessor;
    }

    static boolean consumedHeld(UseSeams.ReadsWorld.Held before, UseSeams.ReadsWorld.Held after) {
        if (before == null) return false;
        if (after != null && after.itemId().equals(before.itemId())) {
            return before.count() > after.count();
        }
        return true;
    }

    // 目标方块带方块实体，且不是普通储物容器、床、告示牌或纯摆设：把手中物品塞进去后，Mod 不掌握它的加工条件与产出。
    private static boolean isUnknownProcessorBlock(UseSeams.ReadsWorld world, FacilityKinds facilities,
            ResolvedTarget target) {
        String typeId = target.typeId();
        if (typeId == null || NON_PROCESSOR_BLOCK_ENTITIES.contains(typeId) || FacilityKinds.isBed(typeId)) {
            return false;
        }
        if (world.sign(target.cell()) != UseSeams.ReadsWorld.Sign.NOT_A_SIGN) {
            return false;
        }
        if (facilities != null && facilities.isContainer(typeId)) {
            return false;
        }
        return world.hasBlockEntity(target.cell());
    }

    // 手上的东西出手前后对照：换成了别的记用掉一件、得到一件；同一种少了记用掉几个；空手出手不记。返回手上的东西是否确实减少了。
    private static boolean recordHandChange(Consumer<Change> record, UseSeams.ReadsWorld.Held before,
            UseSeams.ReadsWorld.Held after) {
        if (before == null) {
            return false;
        }
        Optional<UseSeams.ReadsWorld.Held> now = Optional.ofNullable(after);
        if (now.isPresent() && now.get().itemId().equals(before.itemId())) {
            int used = before.count() - now.get().count();
            if (used > 0) {
                record.accept(new Change(Change.Kind.ITEM_CONSUMED, before.itemId(), used, "用掉了"));
                return true;
            }
            return false;
        }
        record.accept(new Change(Change.Kind.ITEM_CONSUMED, before.itemId(), 1, "用掉了"));
        now.ifPresent(held -> record.accept(new Change(Change.Kind.ITEM_GAINED, held.itemId(), 1, "拿在了手上")));
        return true;
    }
}
