// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.NearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
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

    @Test
    void foundNonFacilityBlockStaysResolvableAcrossSceneRefreshesAndInvalidatesWhenBroken() {
        WorldPosition shrinePos = WorldPosition.here(6, 65, -8);
        String shrineId = scene.recordFoundBlock(shrinePos, "touhou_little_maid:shrine", "前方", 100);

        // 5 秒后常规场景刷新只更新设施列表，非设施神龛不进设施列表但短期保活窗内仍可被 use 解析命中。
        scene.updateFacilities(200, NOW, List.of(), pos -> pos.equals(shrinePos)
                ? Optional.of("touhou_little_maid:shrine") : Optional.of("minecraft:air"));
        scene.expire(200);
        assertTrue(scene.facilities().isEmpty(), "非设施方块不污染 observe 的设施列表");
        ResolvesSeen.Resolved kept = resolver.resolve(shrineId).orElseThrow();
        assertEquals("touhou_little_maid:shrine", kept.typeId());
        assertEquals(shrinePos, kept.position());

        // 60 秒保活窗边界内依然有效。
        long withinWindow = 100L + SeenRegistry.FOUND_BLOCK_RETENTION_TICKS;
        scene.expire(withinWindow);
        assertTrue(resolver.resolve(shrineId).isPresent(), "60 秒保活窗内编号应保持有效");

        // 神龛真被挖掉变成空气后，下一次常规刷新立即使该编号失效。
        scene.updateFacilities(withinWindow, NOW, List.of(), pos -> Optional.of("minecraft:air"));
        assertTrue(resolver.resolve(shrineId).isEmpty(), "原位方块变成空气后编号必须失效");

        // 未被挖掉但超过 60 秒保活窗且没有任务推进时，按到期正常失效。
        WorldPosition altarPos = WorldPosition.here(12, 65, -8);
        String altarId = scene.recordFoundBlock(altarPos, "touhou_little_maid:altar", "右前方", 1000);
        scene.expire(1000L + SeenRegistry.FOUND_BLOCK_RETENTION_TICKS + 1);
        assertTrue(resolver.resolve(altarId).isEmpty(), "超过 60 秒保活窗后应正常过期失效");
    }
}
