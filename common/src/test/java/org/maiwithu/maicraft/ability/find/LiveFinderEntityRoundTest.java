// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 找实体的筛选：命中的收成、被挡住的线索、半径外的忽略、别人的带保护事实。
 * 观察直接从感知读端的实体观察来（与 observe 同一份），这里摆的是
 * "observe 列得出、find 却说没有"的反面——看得见的羊必须进命中。
 */
class LiveFinderEntityRoundTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);

    private static EntitySight.Observation sheep(int id, int x, int z, boolean visible) {
        return new EntitySight.Observation(id, "minecraft:sheep", null, null,
                WorldPosition.here(x, 64, z), visible, false, false, null, Map.of("毛色", "gray"));
    }

    @Test
    void 半径内看得见的羊进命中() {
        // 复现 213 的形态：同刻 observe 列得出 15 格内可见的灰羊，找它不许报"没有看得见的"。
        FindsAround.Round round = LiveFinder.entityRound(
                List.of(sheep(1, 10, 5, true)), Set.of("minecraft:sheep"), CENTER, 32, uuid -> Optional.empty());
        assertTrue(round.complete());
        assertEquals(1, round.hits().size());
        assertEquals("minecraft:sheep", round.hits().getFirst().typeId());
        assertEquals(1, round.hits().getFirst().gameEntityId());
        assertEquals("gray", round.hits().getFirst().traits().get("毛色"));
    }

    @Test
    void 被挡住的羊不冒充看见_记进被挡住的线索() {
        FindsAround.Round round = LiveFinder.entityRound(
                List.of(sheep(1, 3, 0, false), sheep(2, 20, 0, false)),
                Set.of("minecraft:sheep"), CENTER, 32, uuid -> Optional.empty());
        assertTrue(round.hits().isEmpty());
        assertTrue(round.hiddenLead().contains("另有 2 只"), round.hiddenLead());
        assertTrue(round.hiddenLead().contains("(3, 64, 0)"), round.hiddenLead());
    }

    @Test
    void 半径外的忽略_别的类型不收() {
        EntitySight.Observation cow = new EntitySight.Observation(3, "minecraft:cow", null, null,
                WorldPosition.here(5, 64, 0), true, false, false, null, Map.of());
        FindsAround.Round round = LiveFinder.entityRound(
                List.of(sheep(1, 40, 0, true), cow), Set.of("minecraft:sheep"), CENTER, 32,
                uuid -> Optional.empty());
        assertTrue(round.hits().isEmpty(), "半径外的羊与牛都不算");
    }

    @Test
    void 有主的羊照常报告_带保护事实() {
        UUID ownerMarked = UUID.randomUUID();
        EntitySight.Observation named = new EntitySight.Observation(4, "minecraft:sheep", "小花", ownerMarked.toString(),
                WorldPosition.here(6, 64, 0), true, false, false, null, Map.of());
        FindsAround.Round round = LiveFinder.entityRound(List.of(named), Set.of("minecraft:sheep"), CENTER, 32,
                uuid -> Optional.of(new ReadsCreatureSituation.CreatureSituation(false, false, true, false, false, false)));
        assertEquals(1, round.hits().size());
        assertTrue(round.hits().getFirst().creatureProtected());
        assertTrue(round.hits().getFirst().protectionReason().contains("有名字"));
    }
}
