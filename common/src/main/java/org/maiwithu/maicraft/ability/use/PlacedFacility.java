// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.perception.FacilityKinds;

/**
 * 这一下生效的放置把设施放在了哪：确认生效只说明手上少了、点的地方变了，
 * 不说明放下的方块落在哪一格——床、工作台这类放在被点面的邻居格，放进可替换格时占的就是被点格本身。
 *
 * <p>判定读出手后的现场：点过的格与它的六个邻居里，此刻是手上那种方块的格子就是落点。
 * 只认记进世界记忆的设施（床与工作设施，分类与感知记设施是同一份）：放火把、垫脚料这类
 * 不往记忆里补账。找不到落点（现场还没同步过来、放出去的其实是别的）给空，调用方不瞎记位置。
 */
final class PlacedFacility {

    /** 放下的一处设施：落在哪格、是什么方块。 */
    record Placed(BlockPos cell, String blockType) {}

    private PlacedFacility() {}

    /**
     * 从确认生效的一下交互里找放下的设施。
     *
     * @param world       出手后的现场
     * @param interaction 这一下交互：出手前冻结的手上东西
     * @param target      被点的目标格；只对手上的东西用或对实体用时没有放置
     * @param kinds       设施分类：什么方块放下来值得记进世界记忆
     */
    static Optional<Placed> judge(UseSeams.ReadsWorld world, UseSeams.Built.Ready interaction,
            ResolvedTarget target, FacilityKinds kinds) {
        if (target == null || target.isEntity() || interaction.heldBefore() == null) {
            return Optional.empty();
        }
        String blockType = interaction.heldBefore().itemId();
        if (!kinds.isBed(blockType) && !kinds.isWorkstation(blockType)) {
            return Optional.empty();
        }
        // 被点格自己（点是可替换格时放下的方块就占它）优先，再看六个邻居（放在被点面上的）；
        // 紧邻已有同种方块时可能认到它头上，但那一格确实有这种方块，记下的位置不算错。
        BlockPos clicked = target.cell();
        if (world.blockId(clicked).map(blockType::equals).orElse(false)) {
            return Optional.of(new Placed(clicked, blockType));
        }
        for (Direction side : Direction.values()) {
            BlockPos cell = clicked.relative(side);
            if (world.blockId(cell).map(blockType::equals).orElse(false)) {
                return Optional.of(new Placed(cell, blockType));
            }
        }
        return Optional.empty();
    }
}
