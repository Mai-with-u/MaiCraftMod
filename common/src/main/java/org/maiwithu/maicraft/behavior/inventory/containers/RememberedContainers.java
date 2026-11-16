// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryRecord;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;

/**
 * 已知容器读端：把世界记忆里记过的容器读成腾地方能用的候选。
 *
 * <p>每次挑候选时先经 {@link StandingContainers} 核对已加载区块的现场方块：
 * 若箱子已被敲掉变成空气或换成其他方块，当场从世界记忆里划掉并不再选作腾包目标；
 * 未加载区块保留记忆候选，待走到现场再核对。别人的箱子（受圈地或保护限制）不往里塞东西。
 */
public final class RememberedContainers implements KnownContainers {

    private final StandingContainers standing;
    private final ReadsCharacterPosition position;
    private final Protection protection;

    public RememberedContainers(WorldMemory memory, ReadsCharacterPosition position, Protection protection) {
        this(new StandingContainers(Objects.requireNonNull(memory, "memory"), at -> Optional.empty(), blockId -> true),
                position, protection);
    }

    public RememberedContainers(StandingContainers standing, ReadsCharacterPosition position, Protection protection) {
        this.standing = Objects.requireNonNull(standing, "standing");
        this.position = Objects.requireNonNull(position, "position");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override
    public List<KnownContainer> within(int blockRange) {
        // 先经现存性核验淘汰已加载区块里被拆掉的旧箱子，只从眼下仍成立的容器记忆里挑腾包目标。
        return standing.near(position.currentPosition(), blockRange).stream()
                // 只往能用的容器里存：自己或自家人放的、野外无主的；受圈地保护的不碰。
                .filter(record -> protection.mayUseStorage(record.position(), blockTypeOf(record), Set.of()))
                .map(RememberedContainers::toKnown)
                .toList();
    }

    // 记忆里没记下方块类型的按箱子对待：只是名字给人看，开箱时以现场为准。
    private static String blockTypeOf(MemoryRecord record) {
        return record.blockType() == null ? "minecraft:chest" : record.blockType();
    }

    private static KnownContainer toKnown(MemoryRecord record) {
        return new KnownContainer(describe(record), record.position(), blockTypeOf(record));
    }

    // 容器的一句话说法与找容器读端一致：方块种类加坐标，例如"chest（120, 64, -8）"。
    private static String describe(MemoryRecord record) {
        String blockType = blockTypeOf(record);
        String kind = blockType.substring(blockType.indexOf(':') + 1);
        return kind + "（" + record.position().x() + ", " + record.position().y() + ", " + record.position().z() + "）";
    }
}
