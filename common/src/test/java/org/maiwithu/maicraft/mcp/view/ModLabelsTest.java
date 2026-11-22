// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.perception.EntitySight;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.NearbyBlocksSight;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsLocalizedNames;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 视图尾部模组短词表（mod_labels）测试：全原版响应省略该字段、零额外开销；
 * 含非 minecraft: 模组项的各类视图去重输出在根对象尾部，正文保持纯英文 ID 不变。
 */
class ModLabelsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    private static final String OVERWORLD = "minecraft:overworld";

    private static final ReadsLocalizedNames MOD_NAMES = new ReadsLocalizedNames() {
        private final Map<String, String> all = Map.of(
                "touhou_little_maid:smart_slab_empty", "魂符",
                "touhou_little_maid:shrine", "神龛",
                "touhou_little_maid:maid", "女仆",
                "create:mechanical_mixer", "动力搅拌器",
                "ae2:crafting_terminal", "ME合成终端",
                "ae2:certus_quartz_crystal", "赛特斯石英水晶",
                "minecraft:chest", "箱子",
                "minecraft:oak_log", "橡木原木");

        @Override
        public Optional<String> labelOf(IdKind kind, String id) {
            return Optional.ofNullable(all.get(id));
        }

        @Override
        public List<NamedId> entries(IdKind kind) {
            return List.of();
        }
    };

    private static final RemembersSightings NO_MEMORY = new RemembersSightings() {
        @Override public void containerSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void workstationSeen(WorldPosition position, String blockType, Instant when) {}
        @Override public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {}
    };

    @Test
    void 全原版响应不输出模组词表字段(@TempDir Path temp) {
        Scene scene = freshScene("minecraft:oak_log");
        scene.updateEntities(10, List.of(new EntitySight.Observation(1, "minecraft:sheep", null, null,
                WorldPosition.here(4, 64, 0), true, false, false, null, Map.of())));
        scene.updateFacilities(10, NOW, List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(2, 64, 0), "minecraft:chest")));

        JsonObject selfView = SelfView.of(scene.self(), OVERWORLD, backpackOf(
                new BackpackStack("minecraft:oak_log", 16, 64, false, false, false, true)),
                null, null, true, false);
        SelfView.addModLabels(selfView, MOD_NAMES);
        assertFalse(selfView.has(ModLabels.FIELD), "全原版 SelfView 不得输出 mod_labels");

        JsonObject sceneView = SceneView.scene(scene, false, null, MOD_NAMES);
        assertFalse(sceneView.has(ModLabels.FIELD), "全原版 SceneView 不得输出 mod_labels");

        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("vanilla.sqlite")), "a".repeat(64));
        memory.rememberContainerOpened(new WorldPosition(2, 64, 0, OVERWORLD), "minecraft:chest",
                List.of("minecraft:oak_log"), NOW);
        JsonObject memoryView = WorldMemoryView.of(memory, scene.self(), OVERWORLD, null, null,
                WorldMemoryView.originFrom(scene.facilityKinds(), null, OVERWORLD), ignored -> {}, null,
                List.of(), MOD_NAMES);
        assertFalse(memoryView.has(ModLabels.FIELD), "全原版 WorldMemoryView 不得输出 mod_labels");

        String chestId = scene.facilities().getFirst().id();
        JsonObject detailView = DetailView.of(scene, memory, chestId, OVERWORLD, null, MOD_NAMES).orElseThrow();
        assertFalse(detailView.has(ModLabels.FIELD), "全原版 DetailView 不得输出 mod_labels");
    }

    @Test
    void 含模组项的视图在尾部去重输出词表且正文维持纯英文编号(@TempDir Path temp) {
        // SelfView：手持与背包重复出现同一模组物品，正文中英文 ID 原样不动，尾部 mod_labels 去重只出现一次。
        Scene scene = freshScene("touhou_little_maid:smart_slab_empty");
        JsonObject selfView = SelfView.of(scene.self(), OVERWORLD, backpackOf(
                new BackpackStack("touhou_little_maid:smart_slab_empty", 2, 1, false, false, false, false),
                new BackpackStack("create:mechanical_mixer", 1, 64, false, false, false, false),
                new BackpackStack("minecraft:oak_log", 8, 64, false, false, false, true)),
                null, null, true, false);
        SelfView.addModLabels(selfView, MOD_NAMES);

        assertEquals("touhou_little_maid:smart_slab_empty", selfView.get("held").getAsString());
        JsonObject selfLabels = selfView.getAsJsonObject(ModLabels.FIELD);
        assertEquals(2, selfLabels.size(), "只收非 minecraft: 且去重：" + selfLabels);
        assertEquals("魂符", selfLabels.get("touhou_little_maid:smart_slab_empty").getAsString());
        assertEquals("动力搅拌器", selfLabels.get("create:mechanical_mixer").getAsString());
        assertFalse(selfLabels.has("minecraft:oak_log"));
        assertEquals(ModLabels.FIELD, lastKeyOf(selfView), "mod_labels 必须位于根对象尾部");

        // SceneView：实体、远距实体与设施中的模组 ID 一并收录且正文不变。
        scene.updateEntities(10, List.of(
                new EntitySight.Observation(1, "touhou_little_maid:maid", null, null,
                        WorldPosition.here(3, 64, 0), true, false, false, null, Map.of()),
                new EntitySight.Observation(2, "touhou_little_maid:maid", null, null,
                        WorldPosition.here(50, 64, 0), true, false, false, null, Map.of())));
        scene.updateFacilities(10, NOW, List.of(
                new NearbyBlocksSight.BlockSighting(WorldPosition.here(2, 64, 0), "touhou_little_maid:shrine")));
        JsonObject sceneView = SceneView.scene(scene, false, null, MOD_NAMES);
        JsonArray listedEntities = sceneView.getAsJsonArray("entities");
        assertEquals("touhou_little_maid:maid", listedEntities.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject sceneLabels = sceneView.getAsJsonObject(ModLabels.FIELD);
        assertEquals(2, sceneLabels.size(), "同模组实体去重收录：" + sceneLabels);
        assertEquals("女仆", sceneLabels.get("touhou_little_maid:maid").getAsString());
        assertEquals("神龛", sceneLabels.get("touhou_little_maid:shrine").getAsString());
        assertEquals(ModLabels.FIELD, lastKeyOf(sceneView));

        // WorldMemoryView + NetworkStockView：容器记忆与 ME 网络存货、可合成样板中的模组 ID 统一收集在尾部。
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("modded.sqlite")), "b".repeat(64));
        memory.rememberContainerOpened(new WorldPosition(2, 64, 0, OVERWORLD), "minecraft:chest",
                List.of("touhou_little_maid:smart_slab_empty", "minecraft:oak_log"), NOW);
        NetworkStorage.RememberedNetwork network = NetworkStorage.RememberedNetwork.placed(
                "ae2:crafting_terminal", new WorldPosition(4, 64, 0, OVERWORLD), Direction.NORTH,
                true, true,
                Map.of("ae2:certus_quartz_crystal", 128L, "minecraft:oak_log", 64L),
                Set.of("create:mechanical_mixer"), NOW);
        JsonObject memoryView = WorldMemoryView.of(memory, scene.self(), OVERWORLD, null, null,
                WorldMemoryView.originFrom(scene.facilityKinds(), null, OVERWORLD), ignored -> {}, null,
                List.of(network), MOD_NAMES);
        JsonObject memoryLabels = memoryView.getAsJsonObject(ModLabels.FIELD);
        assertEquals("魂符", memoryLabels.get("touhou_little_maid:smart_slab_empty").getAsString());
        assertEquals("ME合成终端", memoryLabels.get("ae2:crafting_terminal").getAsString());
        assertEquals("赛特斯石英水晶", memoryLabels.get("ae2:certus_quartz_crystal").getAsString());
        assertEquals("动力搅拌器", memoryLabels.get("create:mechanical_mixer").getAsString());
        assertFalse(memoryLabels.has("minecraft:chest"));
        assertFalse(memoryLabels.has("minecraft:oak_log"));
        assertEquals(ModLabels.FIELD, lastKeyOf(memoryView));

        // DetailView：模组方块详情也在尾部附去重词表。
        String shrineId = scene.facilities().getFirst().id();
        JsonObject detailView = DetailView.of(scene, memory, shrineId, OVERWORLD, null, MOD_NAMES).orElseThrow();
        assertEquals("touhou_little_maid:shrine",
                detailView.getAsJsonObject("now").get("block").getAsString());
        assertEquals("神龛", detailView.getAsJsonObject(ModLabels.FIELD)
                .get("touhou_little_maid:shrine").getAsString());
        assertEquals(ModLabels.FIELD, lastKeyOf(detailView));
    }

    private static Scene freshScene(String heldItem) {
        Scene scene = new Scene(NO_MEMORY, new FacilityKinds(FakeBlockTags.vanilla()));
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 20, 300, 300, heldItem, List.of(), List.of(), true, false));
        return scene;
    }

    private static BackpackView backpackOf(BackpackStack... stacks) {
        List<BackpackStack> list = List.of(stacks);
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return list; }
            @Override public int usedSlots() { return list.size(); }
            @Override public int totalSlots() { return 36; }
        };
    }

    private static String lastKeyOf(JsonObject object) {
        List<String> keys = new ArrayList<>(object.keySet());
        return keys.getLast();
    }
}
