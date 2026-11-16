// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.HitsItemFrames;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.NearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 采集观察目标解析测试：只有真正的掉落物才标为可捡掉落物，非掉落物实体不再误标。 */
class LiveSceneTargetsTest {

    private Scene scene;
    private LiveSceneTargets targets;

    /** 不关心记忆写入的场景替身。 */
    private static final class NoMemory implements RemembersSightings {
        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {}
    }

    @BeforeEach
    void setUp() {
        scene = new Scene(new NoMemory(), new FacilityKinds(FakeBlockTags.vanilla()));
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 0f,
                20, 20, 300, 300, null, List.of(), List.of(), true, false));
        HitsItemFrames frames = new HitsItemFrames() {
            @Override public Optional<String> contents(int entityId) {
                return entityId == 30 ? Optional.of("minecraft:elytra") : Optional.empty();
            }

            @Override public Action approach(int entityId, Permissions permissions) {
                throw new AssertionError("解析阶段不调用走近");
            }

            @Override public Action hit(int entityId) {
                throw new AssertionError("解析阶段不调用敲击");
            }
        };
        targets = new LiveSceneTargets(() -> scene, frames);
    }

    @Test
    void onlyRealItemEntitiesAreMarkedAsDrops() {
        // 登记地上掉落物、绵羊、强力胶选区与展示框四只实体，核对只有真正的掉落物标为 drop。
        scene.updateEntities(10, List.of(
                new EntitySight.Observation(10, "minecraft:item", null, null,
                        WorldPosition.here(2, 64, 2), true, false, false, null, Map.of("物品", "minecraft:cobblestone")),
                new EntitySight.Observation(20, "minecraft:sheep", null, null,
                        WorldPosition.here(4, 64, 2), true, false, false, null, Map.of()),
                new EntitySight.Observation(25, "create:super_glue", null, null,
                        WorldPosition.here(3, 64, 3), true, false, false, null, Map.of()),
                new EntitySight.Observation(30, "minecraft:item_frame", null, null,
                        WorldPosition.here(1, 65, 2), true, false, false, null, Map.of("框里", "minecraft:elytra"))));
        scene.updateFacilities(10, Instant.ofEpochMilli(1000), List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(5, 64, 5), "minecraft:chest")));
        scene.updateModThings(10, List.of(
                new ModThing("structure", ModThing.Identity.STRUCTURE, "ship-1",
                        WorldPosition.here(8, 70, 8), null, null)));

        GatherAbility.SeesTargets.SeenTarget drop = targets.lookup("e1").orElseThrow();
        assertFalse(drop.block());
        assertTrue(drop.drop(), "原版掉落物实体应标为掉落物");
        assertEquals(-1, drop.itemFrame());

        GatherAbility.SeesTargets.SeenTarget sheep = targets.lookup("e2").orElseThrow();
        assertFalse(sheep.block());
        assertFalse(sheep.drop(), "生物不能误标成掉落物");
        assertEquals(-1, sheep.itemFrame());

        GatherAbility.SeesTargets.SeenTarget glue = targets.lookup("e3").orElseThrow();
        assertFalse(glue.block());
        assertFalse(glue.drop(), "技术附着实体不能误标成掉落物");
        assertEquals(-1, glue.itemFrame());

        GatherAbility.SeesTargets.SeenTarget frame = targets.lookup("e4").orElseThrow();
        assertFalse(frame.block());
        assertFalse(frame.drop());
        assertEquals(30, frame.itemFrame(), "展示框走敲击取物分支");

        GatherAbility.SeesTargets.SeenTarget chest = targets.lookup("b1").orElseThrow();
        assertTrue(chest.block());
        assertFalse(chest.drop());

        GatherAbility.SeesTargets.SeenTarget structure = targets.lookup("s1").orElseThrow();
        assertFalse(structure.block());
        assertFalse(structure.drop());
    }
}
