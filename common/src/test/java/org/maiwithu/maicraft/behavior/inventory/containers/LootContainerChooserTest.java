// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.behavior.permission.StorageUse;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 挑战利品容器纯函数测试：优先未开过的 natural_loot，其次未开过的 no_record；
 * 已开过、自家、他人、归属未知、受圈地保护的容器一律排除并给出原因计数。
 */
class LootContainerChooserTest {

    private static LootContainerChooser.Candidate candidate(int x, double cost, ContainerWhose origin,
            List<String> knownContents, StorageUse storageUse) {
        ContainerChooser.Candidate base = new ContainerChooser.Candidate(
                "箱子（" + x + ", 64, 0）", "minecraft:chest",
                new WorldPosition(x, 64, 0, "minecraft:overworld"),
                knownContents, true, cost, false, true, ContainerChooser.Lid.CLEAR, false);
        return new LootContainerChooser.Candidate(base, origin, storageUse);
    }

    @Test
    void 自然战利品容器排在无记录容器前面_已开过和玩家放置的跳过() {
        List<LootContainerChooser.Candidate> all = List.of(
                // 离得最近但是别人放的：跳过。
                candidate(2, 2.0, new ContainerWhose(ContainerWhose.Kind.PLAYER, "玩家甲", false), null,
                        StorageUse.USABLE),
                // 较近的无记录原版箱子（未开过）：第二档。
                candidate(5, 5.0, new ContainerWhose(ContainerWhose.Kind.NO_RECORD, null, false), null,
                        StorageUse.USABLE),
                // 已开过的自然战利品箱：跳过。
                candidate(8, 8.0, new ContainerWhose(ContainerWhose.Kind.NATURAL_LOOT, null, true),
                        List.of("minecraft:tnt"), StorageUse.USABLE),
                // 稍远但未开过的自然战利品箱：第一档排最前。
                candidate(18, 18.0, new ContainerWhose(ContainerWhose.Kind.NATURAL_LOOT, null, false), null,
                        StorageUse.USABLE));

        LootContainerChooser.Pick.Chosen chosen = assertInstanceOf(LootContainerChooser.Pick.Chosen.class,
                LootContainerChooser.chooseUnopened(all, true, 24));
        assertEquals(2, chosen.ordered().size());
        assertEquals(18, chosen.ordered().get(0).container().at().x());
        assertEquals(5, chosen.ordered().get(1).container().at().x());
    }

    @Test
    void 全部不可用时合并说明原因() {
        List<LootContainerChooser.Candidate> all = List.of(
                candidate(2, 2.0, new ContainerWhose(ContainerWhose.Kind.SELF, null, false), null, StorageUse.USABLE),
                candidate(6, 6.0, new ContainerWhose(ContainerWhose.Kind.NATURAL_LOOT, null, true),
                        List.of(), StorageUse.USABLE),
                candidate(9, 9.0, new ContainerWhose(ContainerWhose.Kind.NATURAL_LOOT, null, false),
                        null, new StorageUse(StorageUse.Kind.IN_REMEMBERED_REGION, "农场")));

        LootContainerChooser.Pick.NotFound notFound = assertInstanceOf(LootContainerChooser.Pick.NotFound.class,
                LootContainerChooser.chooseUnopened(all, true, 24));
        assertTrue(notFound.searched().contains("已开过 1 只"));
        assertTrue(notFound.searched().contains("玩家放的 1 只"));
        assertTrue(notFound.searched().contains("受圈地或保护地标挡住 1 只"));
    }
}
