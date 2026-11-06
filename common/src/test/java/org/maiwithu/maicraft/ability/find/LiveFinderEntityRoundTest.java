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
import org.maiwithu.maicraft.kernel.result.TaskResult;

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

    @Test
    void 被挡住的掉落物_按物品数报_结论里就说在哪_走过去试着捡() {
        // 棚子地板挡住了下面那堆木板（6 块）和一颗苹果：observe 列得出（visible=false），find 的结论不许读成"附近没有"。
        EntitySight.Observation plank = new EntitySight.Observation(5, LiveFinder.DROPPED_ITEM, null, null,
                WorldPosition.here(-2, 62, 2), false, false, false, null,
                Map.of("物品", "minecraft:oak_planks", "数量", "6"));
        EntitySight.Observation apple = new EntitySight.Observation(6, LiveFinder.DROPPED_ITEM, null, null,
                WorldPosition.here(-4, 62, 3), false, false, false, null, Map.of("物品", "minecraft:apple"));
        FindsAround.Round round = LiveFinder.entityRound(List.of(plank, apple), Set.of(LiveFinder.DROPPED_ITEM), CENTER, 16,
                uuid -> Optional.empty());
        assertTrue(round.hiddenLead().contains("另有 7 件被挡住"), round.hiddenLead());
        assertTrue(round.hiddenLead().contains("最近的在 (-2, 62, 2)"), round.hiddenLead());
        assertTrue(round.hiddenLead().endsWith("走过去试着捡"), round.hiddenLead());
        FindInput input = new FindInput(FindInput.FindKind.ENTITY, List.of(LiveFinder.DROPPED_ITEM), 5, 16);
        TaskResult result = FindSettlement.insufficient(input, List.of(), Map.of(), round.hiddenLead());
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.summary().startsWith("寻找：这一片没有看得见的minecraft:item；范围内另有 7 件被挡住"),
                result.summary());
        assertTrue(result.summary().contains("(-2, 62, 2)"), result.summary());
        // 线索只在结论里说一次，问题里只写查了多大范围。
        assertTrue(!result.problem().message().contains("被挡住"), result.problem().message());
    }
}
