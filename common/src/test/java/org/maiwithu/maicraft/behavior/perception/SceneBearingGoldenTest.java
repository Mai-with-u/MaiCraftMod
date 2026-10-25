// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 方位报文金样：角色站在原点脸朝北时，场景六路观察对固定输入报出的相对方位、罗盘方位、
 * 距离与远近逐字钉住。收拢方位换算前后这些值必须一个字都不变。
 */
class SceneBearingGoldenTest {

    private static final Instant NOW = Instant.ofEpochMilli(1000);

    private RecordingMemory memory;
    private Scene scene;

    @BeforeEach
    void freshScene() {
        memory = new RecordingMemory();
        scene = new Scene(memory, new FacilityKinds(FakeBlockTags.vanilla()));
        // 角色站在原点，脸朝北（视角角 180）。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 20, 300, 300, null, List.of(), List.of(), true, false));
    }

    @Test
    void entityBearingIsPinned() {
        // 东北偏东 5 格余、高 2 格：报右前方、东北、距离取整 5。
        scene.updateEntities(10, List.of(new EntitySight.Observation(1, "minecraft:zombie", null, null,
                WorldPosition.here(3, 66, -4), true, false, false, null, Map.of())));
        SceneEntity zombie = scene.entities().getFirst();
        assertEquals("右前方", zombie.direction());
        assertEquals("东北", zombie.compass());
        assertEquals(5, zombie.distance());
        assertEquals(2, zombie.dy());
    }

    @Test
    void soundBearingIsPinned() {
        // 水平 5 格整：报右前方，远近档位按未取整的 5.0 分档，落"近处"。
        scene.updateSounds(10, List.of(new SubtitleEar.Event("苦力怕嘶嘶声", WorldPosition.here(3, 64, -4))));
        HeardSound sound = scene.heard().getFirst();
        assertEquals("右前方", sound.direction());
        assertEquals("近处", sound.nearness());
    }

    @Test
    void featureBearingIsPinned() {
        // 网格顶行三格树聚成一片，中心落在 (0, 64, -5)：正北 5 格。
        char[][] cells = {
                {'T', 'T', 'T'},
                {'.', '.', '.'},
                {'.', '.', '.'}};
        scene.updateGrid(new OverheadGrid.View(new BlockPos(0, 64, -4), 1, cells), 10, NOW);
        TerrainFeature forest = scene.features().getFirst();
        assertEquals("前方", forest.direction());
        assertEquals("北", forest.compass());
        assertEquals(5, forest.distance());
    }

    @Test
    void facilityBearingIsPinned() {
        // 西北偏北 4 格余：报左前方、西北、距离取整 4。
        scene.updateFacilities(10, NOW, List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(-4, 64, -2), "minecraft:chest")));
        FacilitySighting chest = scene.facilities().getFirst();
        assertEquals("左前方", chest.direction());
        assertEquals("西北", chest.compass());
        assertEquals(4, chest.distance());
    }

    @Test
    void signBearingIsPinned() {
        // 东北偏北 3 格余：报右前方、东北、距离取整 4。
        scene.updateSigns(10, List.of(new NearbyBlocksSight.SignSighting(WorldPosition.here(2, 64, -3),
                "minecraft:oak_sign", null)));
        SceneSign sign = scene.signs().getFirst();
        assertEquals("右前方", sign.direction());
        assertEquals("东北", sign.compass());
        assertEquals(4, sign.distance());
    }

    @Test
    void modThingBearingIsPinned() {
        // 模组的东西按方块中心量：目标 (4, 64, -1) 的中心在 (4.5, 64.5, -0.5)，
        // 离角色 (0.5, 64, 0.5) 三维约 4.15 格：报右侧、东、距离取整 4。中心修正的口径在这里钉住。
        List<ModThing> things = List.of(
                new ModThing("elevator", ModThing.Identity.BLOCK, "", WorldPosition.here(4, 64, -1),
                        new JsonObject(), null));
        scene.updateModThings(10, things);
        SceneThing elevator = scene.things().getFirst();
        assertEquals("右侧", elevator.direction());
        assertEquals("东", elevator.compass());
        assertEquals(4, elevator.distance());
    }
}
