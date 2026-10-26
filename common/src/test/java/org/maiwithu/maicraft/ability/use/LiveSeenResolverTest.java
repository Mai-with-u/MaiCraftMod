// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.NearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.perception.SignLines;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 观察编号解析的生产实现：场景里哪一路的编号都认得出，告示牌与模组的东西也在内。 */
class LiveSeenResolverTest {

    private static final Instant NOW = Instant.ofEpochMilli(1000);

    private Scene scene;
    private LiveSeenResolver resolver;

    @BeforeEach
    void freshScene() {
        scene = new Scene(new NoMemory(), new FacilityKinds(FakeBlockTags.vanilla()));
        // 角色站在原点，脸朝北（视角角 180）。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 20, 300, 300, null, List.of(), List.of(), true, false));
        resolver = new LiveSeenResolver(() -> scene);
    }

    /** 感知写看见记录的替身：解析测试不关心写进世界记忆的内容。 */
    private static final class NoMemory implements RemembersSightings {
        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {}
    }

    @Test
    void facilityEntityFeatureAndSignsAllResolve() {
        scene.updateFacilities(10, NOW, List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(-4, 64, 0), "minecraft:chest")));
        scene.updateEntities(10, List.of(new EntitySight.Observation(1, "minecraft:zombie", null, null,
                WorldPosition.here(0, 65, -12), true, true, true, null, Map.of())));
        scene.updateSigns(10, List.of(new NearbyBlocksSight.SignSighting(WorldPosition.here(0, 64, -3),
                "minecraft:oak_sign", new SignLines(List.of("", "家 往北", "", ""), List.of("", "", "", "")))));

        String facilityId = scene.facilities().getFirst().id();
        ResolvesSeen.Resolved facility = resolver.resolve(facilityId).orElseThrow();
        assertEquals("minecraft:chest", facility.typeId());
        assertEquals(WorldPosition.here(-4, 64, 0), facility.position());

        ResolvesSeen.Resolved zombie = resolver.resolve("e1").orElseThrow();
        assertEquals("minecraft:zombie", zombie.typeId());
        assertEquals(1, zombie.gameEntityId());

        // 看得见的告示牌编号也要能指定：按牌子的方块类型说它是什么，位置在牌子所在格。
        String signId = scene.signs().getFirst().id();
        ResolvesSeen.Resolved sign = resolver.resolve(signId).orElseThrow();
        assertEquals("minecraft:oak_sign", sign.typeId());
        assertEquals(WorldPosition.here(0, 64, -3), sign.position());
    }

    @Test
    void modThingsResolveByCategoryWithTheirGameEntity() {
        // 物理结构领 s#：按种类说它是什么，位置照这次看到的给。
        scene.updateModThings(10, List.of(
                new ModThing("airship", ModThing.Identity.STRUCTURE, "ship-1", WorldPosition.here(10, 70, 0),
                        null, null)));
        String shipId = scene.things().getFirst().id();
        ResolvesSeen.Resolved ship = resolver.resolve(shipId).orElseThrow();
        assertEquals("airship", ship.typeId());
        assertEquals(WorldPosition.here(10, 70, 0), ship.position());

        // 列车这类实体东西与实体同号：游戏实体编号带着，交互前按它找回那一只。
        scene.updateEntities(10, List.of(new EntitySight.Observation(7, "minecraft:camel", null, null,
                WorldPosition.here(20, 64, 0), true, false, false, null, Map.of())));
        scene.updateModThings(10, List.of(
                new ModThing("train", ModThing.Identity.ENTITY, "7", WorldPosition.here(20, 64, 0), null, null)));
        String trainId = scene.things().getFirst().id();
        ResolvesSeen.Resolved train = resolver.resolve(trainId).orElseThrow();
        assertEquals(7, train.gameEntityId());
        assertEquals("minecraft:camel", train.typeId(), "实体样子在场时按实体说它是什么");
    }

    @Test
    void unknownOrExpiredIdsDoNotResolve() {
        assertTrue(resolver.resolve("b99").isEmpty());
        assertTrue(resolver.resolve(null).isEmpty());
        scene.updateEntities(100, List.of(new EntitySight.Observation(5, "minecraft:creeper", null, null,
                WorldPosition.here(0, 64, -3), true, true, false, null, Map.of())));
        scene.expire(400);
        assertTrue(resolver.resolve("e1").isEmpty(), "失效的编号按不在了结束");
    }
}
