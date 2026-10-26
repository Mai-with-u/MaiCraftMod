// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.NearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.OverheadGrid;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneEnvironment;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.perception.SignLines;
import org.maiwithu.maicraft.behavior.perception.SubtitleEar;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** observe(scene) 视图的金样：固定一块场景，交出的 JSON 字段名与形状逐个钉住，字段名改动在这里显形。 */
class SceneViewTest {

    private static final Instant NOW = Instant.ofEpochMilli(1000);

    private Scene scene;

    /** 感知写看见记录的替身：视图测试不关心写进世界记忆的内容，收着不丢就行。 */
    private static final class NoMemory implements RemembersSightings {
        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {}
    }

    @BeforeEach
    void freshScene() {
        scene = new Scene(new NoMemory(), new FacilityKinds(FakeBlockTags.vanilla()));
        // 角色站在原点，脸朝北（视角角 180）。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 20, 300, 300, null, List.of(), List.of(), true, false));
    }

    @Test
    void sceneJsonShapeIsPinned() {
        scene.updateEnvironment(new SceneEnvironment("黄昏", "晴", 7, "minecraft:plains"));
        // 近处一只盯着角色的僵尸、一只羊；远处三只不相干的鱿鱼只计数。
        scene.updateEntities(10, List.of(
                new EntitySight.Observation(1, "minecraft:zombie", null, null, WorldPosition.here(0, 65, -12),
                        true, true, true, new EntitySight.Health(14, 20), Map.of()),
                new EntitySight.Observation(2, "minecraft:sheep", null, null, WorldPosition.here(9, 64, 0),
                        true, false, false, null, Map.of()),
                new EntitySight.Observation(11, "minecraft:squid", null, null, WorldPosition.here(0, 50, -60),
                        false, false, false, null, Map.of()),
                new EntitySight.Observation(12, "minecraft:squid", null, null, WorldPosition.here(0, 50, -70),
                        false, false, false, null, Map.of()),
                new EntitySight.Observation(13, "minecraft:squid", null, null, WorldPosition.here(0, 50, -80),
                        false, false, false, null, Map.of())));
        scene.updateSounds(10, List.of(new SubtitleEar.Event("苦力怕嘶嘶声", WorldPosition.here(2, 64, 2))));
        // 网格顶行三格树：聚成一片树林。
        scene.updateGrid(new OverheadGrid.View(new BlockPos(0, 64, -4), 1,
                new char[][] {{'T', 'T', 'T'}, {'.', '.', '.'}, {'.', '.', '.'}}), 10, NOW);
        scene.updateFacilities(10, NOW, List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(-4, 64, 0), "minecraft:chest")));
        scene.updateSigns(10, List.of(new NearbyBlocksSight.SignSighting(WorldPosition.here(0, 64, -3),
                "minecraft:oak_sign", new SignLines(List.of("", "家 往北", " 一百格 ", ""), List.of("", "", "", "")))));
        JsonObject floor = new JsonObject();
        floor.addProperty("current_floor", "1F");
        scene.updateModThings(10, List.of(
                new ModThing("elevator", ModThing.Identity.BLOCK, "", WorldPosition.here(4, 64, 0), floor, null)));

        JsonObject json = SceneView.scene(scene, false);

        // 顶层字段：环境四项、朝向、速写、五路列表与远处计数。
        assertEquals(List.of("黄昏", "晴", "7", "minecraft:plains"), List.of(
                json.get("time").getAsString(), json.get("weather").getAsString(),
                json.get("light").getAsString(), json.get("biome").getAsString()));
        assertEquals("北", json.get("facing").getAsString());
        assertTrue(json.get("summary").getAsString().contains("附近列出 2 个实体"));

        var entities = json.getAsJsonArray("entities");
        assertEquals(2, entities.size(), "远处鱿鱼只计数不逐只列");
        JsonObject zombie = entities.get(0).getAsJsonObject();
        // 实体条目的字段与取值逐个钉住：血量快照是活体才有。
        assertEquals("e1", zombie.get("id").getAsString());
        assertEquals("minecraft:zombie", zombie.get("type").getAsString());
        assertEquals("前方", zombie.get("direction").getAsString());
        assertEquals("北", zombie.get("compass").getAsString());
        assertEquals(12, zombie.get("distance").getAsInt());
        assertEquals(1, zombie.get("dy").getAsInt());
        assertTrue(zombie.get("visible").getAsBoolean());
        assertTrue(zombie.get("hostile").getAsBoolean());
        assertTrue(zombie.get("targeting_me").getAsBoolean());
        assertEquals(14.0, zombie.get("health").getAsDouble());
        assertEquals(20.0, zombie.get("max_health").getAsDouble());
        var sheep = entities.get(1).getAsJsonObject();
        assertFalse(sheep.has("health"), "没有血量快照的实体不写 health");
        assertFalse(sheep.has("name"), "没有名字的实体不写 name");

        // 远处的按种类计数：种类 → 只数。
        assertEquals(3, json.getAsJsonObject("distant_entities").get("minecraft:squid").getAsInt());

        var heard = json.getAsJsonArray("heard");
        JsonObject sound = heard.get(0).getAsJsonObject();
        assertEquals("苦力怕嘶嘶声", sound.get("sound").getAsString());
        assertTrue(sound.has("direction") && sound.has("distance"), "声音只有方位与远近，不给坐标");
        assertFalse(sound.has("position"));

        var features = json.getAsJsonArray("features");
        JsonObject forest = features.get(0).getAsJsonObject();
        assertEquals("树林", forest.get("kind").getAsString());
        assertEquals("f1", forest.get("id").getAsString());
        assertEquals("前方", forest.get("direction").getAsString());
        assertTrue(forest.has("size") && forest.has("position") && forest.has("compass") && forest.has("distance"));

        var facilities = json.getAsJsonArray("facilities");
        JsonObject chest = facilities.get(0).getAsJsonObject();
        assertEquals("b1", chest.get("id").getAsString());
        assertEquals("minecraft:chest", chest.get("block").getAsString());
        assertEquals("左侧", chest.get("direction").getAsString());
        assertTrue(chest.has("compass") && chest.has("distance") && chest.has("position"));

        var signs = json.getAsJsonArray("signs");
        JsonObject sign = signs.get(0).getAsJsonObject();
        assertEquals("b2", sign.get("id").getAsString(), "告示牌与设施共用 b# 号段");
        assertEquals("minecraft:oak_sign", sign.get("block").getAsString());
        assertEquals("家 往北 一百格", sign.get("text").getAsString(), "正面几行连成一句");
        JsonObject signPosition = sign.getAsJsonObject("position");
        assertEquals(0, signPosition.get("x").getAsInt());
        assertEquals(64, signPosition.get("y").getAsInt());
        assertEquals(-3, signPosition.get("z").getAsInt());

        // 模组的东西按种类分组，场景那几项原样带着。
        var elevators = json.getAsJsonObject("mod_things").getAsJsonArray("elevator");
        assertEquals(1, elevators.size());
        JsonObject elevator = elevators.get(0).getAsJsonObject();
        assertEquals("b3", elevator.get("id").getAsString());
        assertEquals("1F", elevator.get("current_floor").getAsString(), "场景给的几项原样写进条目");
        assertTrue(elevator.has("direction") && elevator.has("compass") && elevator.has("distance")
                && elevator.has("position"));
        assertFalse(json.has("mod_things_farther"), "没有没列出来的就不写远处计数");
        assertFalse(json.has("grid"), "withGrid 关着就不带俯视网格");
    }
}
