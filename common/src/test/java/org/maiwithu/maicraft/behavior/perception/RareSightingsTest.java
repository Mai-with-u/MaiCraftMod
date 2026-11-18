// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 看见罕见目标（Boss 与天然稀有矿脉）：视野门限、矿脉合并、世界记忆去重与 Boss 脱战过期。 */
class RareSightingsTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final RareSightings.Viewer VIEWER = new RareSightings.Viewer(0.5, 64.0, 0.5, 0.0f, OVERWORLD);

    private final List<TaskEvent.Kind> kinds = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<RareSighting> facts = new ArrayList<>();
    private final List<String> rememberedOres = new ArrayList<>();
    private final Set<String> knownInMemory = new HashSet<>();

    private final TaskEventSink sink = new TaskEventSink() {
        @Override public void publish(TaskEvent.Kind kind, String message) {
            kinds.add(kind);
            messages.add(message);
        }

        @Override public void publish(TaskEvent.Kind kind, String message, EventFacts attached) {
            publish(kind, message);
            if (attached instanceof RareSighting rare) {
                facts.add(rare);
            }
        }
    };

    private final RemembersSightings memory = new RemembersSightings() {
        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) {}

        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) {}

        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {
            rememberedOres.addAll(roughlyThere);
        }
    };

    private final RareSightings sightings = new RareSightings(
            sink, memory, (spot, blockId) -> knownInMemory.contains(blockId + "@" + spot.dimension()));

    @Test
    void Boss在背后时不报_转头进入视野只报一次_远离五分钟后重逢才再报() {
        WorldPosition at = new WorldPosition(0, 64, 12, OVERWORLD);
        RareSightings.BossCandidate behind = new RareSightings.BossCandidate(
                "uuid-warden", "minecraft:warden", "监守者", "e3", at, false);
        sightings.observeBosses(VIEWER, 100L, List.of(behind));
        assertTrue(messages.isEmpty(), "背对着没看见不能报");

        RareSightings.BossCandidate seen = new RareSightings.BossCandidate(
                "uuid-warden", "minecraft:warden", "监守者", "e3", at, true);
        sightings.observeBosses(VIEWER, 120L, List.of(seen));
        assertEquals(List.of(TaskEvent.Kind.RARE_SIGHTING), kinds);
        assertEquals("眼前看见了监守者（e3）（前方 12 格）", messages.getFirst());
        assertEquals(new RareSighting("boss", "minecraft:warden", "监守者", "e3", 1, at), facts.getFirst());

        // 缠斗期间背身又回头仍在场，不再重复报
        sightings.observeBosses(VIEWER, 200L, List.of(behind));
        sightings.observeBosses(VIEWER, 300L, List.of(seen));
        assertEquals(1, messages.size());

        // 彻底不在周围超过五分钟后重逢，重新报一次
        sightings.observeBosses(VIEWER, 300L + RareSightings.BOSS_FORGET_TICKS + 10L, List.of());
        sightings.observeBosses(VIEWER, 300L + RareSightings.BOSS_FORGET_TICKS + 20L, List.of(seen));
        assertEquals(2, messages.size());
    }

    @Test
    void 相连稀有矿石合并为一条矿脉并发事件记入世界记忆_转头再看不再重报() {
        List<RareSightings.VisibleOre> ores = List.of(
                new RareSightings.VisibleOre(new BlockPos(0, 64, 6), "minecraft:deepslate_diamond_ore", "深层钻石矿石"),
                new RareSightings.VisibleOre(new BlockPos(1, 64, 6), "minecraft:deepslate_diamond_ore", "深层钻石矿石"),
                new RareSightings.VisibleOre(new BlockPos(1, 65, 6), "minecraft:deepslate_diamond_ore", "深层钻石矿石")
        );
        sightings.observeOres(VIEWER, Instant.EPOCH, ores);

        assertEquals(1, messages.size());
        assertEquals("眼前看见了深层钻石矿石 3 块（前方 6 格）", messages.getFirst());
        assertEquals(new RareSighting(
                "rare_ore",
                "minecraft:deepslate_diamond_ore",
                "深层钻石矿石",
                null,
                3,
                new WorldPosition(0, 64, 6, OVERWORLD)), facts.getFirst());
        assertEquals(List.of("minecraft:deepslate_diamond_ore"), rememberedOres);

        // 转头再看旁边同一簇矿脉，不重复发事件
        sightings.observeOres(VIEWER, Instant.EPOCH, List.of(
                new RareSightings.VisibleOre(new BlockPos(2, 64, 7), "minecraft:deepslate_diamond_ore", "深层钻石矿石")));
        assertEquals(1, messages.size());
    }

    @Test
    void 世界记忆里已记过的矿脉不再当新发现报() {
        knownInMemory.add("minecraft:ancient_debris@" + OVERWORLD);
        sightings.observeOres(VIEWER, Instant.EPOCH, List.of(
                new RareSightings.VisibleOre(new BlockPos(0, 64, 5), "minecraft:ancient_debris", "远古残骸")));

        assertTrue(messages.isEmpty());
        assertTrue(rememberedOres.isEmpty());
    }

    @Test
    void 通用Boss标签与原版四大强敌均算Boss_普通僵尸不算() {
        assertTrue(RareSightings.isBossType("minecraft:warden", false));
        assertTrue(RareSightings.isBossType("minecraft:elder_guardian", false));
        assertTrue(RareSightings.isBossType("minecraft:wither", false));
        assertTrue(RareSightings.isBossType("minecraft:ender_dragon", false));
        assertTrue(RareSightings.isBossType("cataclysm:ignis", true));
        assertFalse(RareSightings.isBossType("minecraft:zombie", false));
    }
}
