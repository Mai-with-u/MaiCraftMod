// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.mcp.view.DetailView;
import org.maiwithu.maicraft.mcp.view.SceneView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 营地地面坑识别与观察视图测试：覆盖竖坑、苦力怕碗状弹坑、天然斜坡、水坑河岸、营地边界与观察去重。
 */
class CampPitsTest {

    @Test
    void singleVerticalDugPitInCampIsDetectedWithBottomUpFillOrder() {
        FlatGrid grid = new FlatGrid(64);
        grid.setTop(3, 0, 61);

        List<CampPits.Pit> pits = CampPits.find(new BlockPos(0, 65, 0), 10, true, grid);

        assertEquals(1, pits.size());
        CampPits.Pit pit = pits.getFirst();
        assertEquals(new BlockPos(3, 64, 0), pit.center());
        assertEquals(64, pit.rimY());
        assertEquals(3, pit.depth());
        assertEquals(1, pit.cells());
        assertEquals(List.of(
                new BlockPos(3, 62, 0),
                new BlockPos(3, 63, 0),
                new BlockPos(3, 64, 0)), pit.fillCells());
    }

    @Test
    void creeperBowlCraterMergesRimStepsAndComputesVolume() {
        FlatGrid grid = new FlatGrid(64);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                grid.setTop(4 + dx, 2 + dz, dx == 0 && dz == 0 ? 61 : 63);
            }
        }

        List<CampPits.Pit> pits = CampPits.find(new BlockPos(0, 65, 0), 10, true, grid);

        assertEquals(1, pits.size());
        CampPits.Pit crater = pits.getFirst();
        assertEquals(new BlockPos(4, 64, 2), crater.center());
        assertEquals(64, crater.rimY());
        assertEquals(3, crater.depth());
        assertEquals(9, crater.cells());
        // 8 个边格各差 1 格 + 中心差 3 格 = 11 格，且中心 62、63 先填，最后封满 64 顶层。
        assertEquals(11, crater.fillCells().size());
        assertEquals(new BlockPos(4, 62, 2), crater.fillCells().get(0));
        assertEquals(new BlockPos(4, 63, 2), crater.fillCells().get(1));
        assertEquals(new BlockPos(4, 64, 2), crater.fillCells().getLast());
    }

    @Test
    void naturalSlopeDrainsOffBoundaryAndIsNotReportedAsPit() {
        CampPits.Ground slope = new CampPits.Ground() {
            @Override public boolean anyCampNearby(int cx, int cy, int cz, int r) { return true; }
            @Override public boolean inCamp(int x, int y, int z) { return true; }
            @Override public boolean insideStructure(int x, int y, int z) { return false; }
            @Override public CampPits.Column columnAt(int x, int z, int refY) {
                return CampPits.Column.naturalGround(68 - z);
            }
        };

        List<CampPits.Pit> pits = CampPits.find(new BlockPos(0, 68, 0), 8, true, slope);
        assertTrue(pits.isEmpty());
    }

    @Test
    void waterPondAndAdjacentBankDepressionAreIgnored() {
        FlatGrid grid = new FlatGrid(64);
        grid.setDrain(2, 0);
        grid.setTop(3, 0, 61);

        List<CampPits.Pit> pits = CampPits.find(new BlockPos(0, 65, 0), 8, true, grid);
        assertTrue(pits.isEmpty());
    }

    @Test
    void pitOutsideCampgroundIsSkippedUntilTargetedDirectly() {
        FlatGrid grid = new FlatGrid(64);
        grid.campOnlyNearOrigin = true;
        grid.setTop(15, 0, 61);

        assertTrue(CampPits.find(new BlockPos(0, 65, 0), 20, true, grid).isEmpty());
        assertEquals(1, CampPits.find(new BlockPos(15, 65, 0), 6, false, grid).size());
    }

    @Test
    void sceneViewExposesCampPitsAndSuppressesDuplicateGridDrop(@TempDir Path tempDir) {
        WorldMemory memory = new WorldMemory(new DocumentStore(tempDir.resolve("state.sqlite")), "test-world");
        FacilityKinds kinds = new FacilityKinds(FakeBlockTags.vanilla());
        Scene scene = new Scene(memory, kinds);
        scene.updateSelf(new SelfSight.Facts(0.5, 65.0, 0.5, 0f, 20f, 20, 300, 300,
                null, List.of(), List.of(), true, false));
        // 俯视网格在南侧 (0, 3..4) 画两格落差 'v'（触发 FeatureSpotter 聚出一处落差）。
        char[][] cells = new char[11][11];
        for (int r = 0; r < 11; r++) {
            for (int c = 0; c < 11; c++) cells[r][c] = '.';
        }
        cells[5][5] = '@';
        cells[8][5] = 'v';
        cells[9][5] = 'v';
        scene.updateGrid(new OverheadGrid.View(new BlockPos(0, 65, 0), 5, cells), 10L, Instant.now());
        assertEquals(1, scene.features().size());

        // 同一位置扫出营地地面坑后，落差不再重复出现在 features 里，改为 camp_pits 单列并可按 f# 查详情。
        CampPits.Pit pit = new CampPits.Pit(new BlockPos(0, 64, 3), 64, 3, 2,
                List.of(new BlockPos(0, 63, 3), new BlockPos(0, 64, 3)));
        scene.updateCampPits(10L, List.of(pit));

        assertTrue(scene.features().isEmpty());
        assertEquals(1, scene.campPits().size());
        JsonObject json = SceneView.scene(scene, false);
        assertTrue(json.has("camp_pits"));
        JsonObject first = json.getAsJsonArray("camp_pits").get(0).getAsJsonObject();
        String pitId = first.get("id").getAsString();
        assertTrue(pitId.startsWith("f"));
        assertEquals(3, first.get("depth").getAsInt());
        assertEquals(2, first.get("cells").getAsInt());
        assertTrue(scene.summary().contains("营地地面坑 1 处"));

        JsonObject detail = DetailView.of(scene, memory, pitId, pos -> null).orElseThrow();
        assertTrue(detail.has("now"));
        assertEquals(3, detail.getAsJsonObject("now").get("depth").getAsInt());
    }

    private static final class FlatGrid implements CampPits.Ground {
        private final int defaultTop;
        private final Map<Long, Integer> customTop = new HashMap<>();
        private final Set<Long> drains = new HashSet<>();
        boolean campOnlyNearOrigin;

        FlatGrid(int defaultTop) { this.defaultTop = defaultTop; }
        void setTop(int x, int z, int topY) { customTop.put(key(x, z), topY); }
        void setDrain(int x, int z) { drains.add(key(x, z)); }

        @Override public boolean anyCampNearby(int cx, int cy, int cz, int r) { return true; }
        @Override public boolean inCamp(int x, int y, int z) { return !campOnlyNearOrigin || Math.hypot(x, z) <= 10.0; }
        @Override public boolean insideStructure(int x, int y, int z) { return false; }

        @Override
        public CampPits.Column columnAt(int x, int z, int refY) {
            long k = key(x, z);
            return drains.contains(k) ? CampPits.Column.drain()
                    : CampPits.Column.naturalGround(customTop.getOrDefault(k, defaultTop));
        }

        private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    }
}
