// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.nio.file.Path;
import java.util.List;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FakeBlockTags;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 地标详情的金样：记住的地点报出的方位、罗盘与距离逐字钉住；目标按方块中心量。 */
class LandmarkDetailViewTest {

    @TempDir
    Path temp;

    @Test
    void landmarkBearingIsPinned() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "golden");
        memory.remember("家", WorldPosition.here(2, 64, 5));
        Scene scene = new Scene(memory, new FacilityKinds(FakeBlockTags.vanilla()));
        // 角色站在原点，脸朝北（视角角 180）。
        scene.updateSelf(new SelfSight.Facts(0.5, 64, 0.5, 180f,
                20, 20, 300, 300, null, List.of(), List.of(), true, false));
        JsonObject json = DetailView.of(scene, memory, "家").orElseThrow();
        assertEquals("家", json.get("landmark").getAsString());
        // 目标按方块中心量：(2.5, 5.5) 离 (0.5, 0.5) 水平约 5.39 格：报后方、南、距离取整 5。
        assertEquals("后方", json.get("direction").getAsString());
        assertEquals("南", json.get("compass").getAsString());
        assertEquals(5, json.get("distance").getAsInt());
        assertTrue(json.has("position"));
    }
}
