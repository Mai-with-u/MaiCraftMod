// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/** 结果交给 LLM 的样子：动了预留的那条变化写 from_reserved，没动的不写。 */
class ResultJsonTest {

    @Test
    void 动了预留的变化写上几件是预留的() {
        TaskResult result = TaskResult.builder(TaskResult.Status.DONE, "盖好了")
                .change(new Change(Change.Kind.BLOCK_PLACED, "minecraft:oak_planks", 30, null, 25))
                .change(Change.of(Change.Kind.ITEM_CONSUMED, "minecraft:torch", 2))
                .build();

        JsonObject json = ResultJson.result(result);

        JsonObject placed = json.getAsJsonArray("changes").get(0).getAsJsonObject();
        assertEquals(25, placed.get("from_reserved").getAsInt());
        assertFalse(json.getAsJsonArray("changes").get(1).getAsJsonObject().has("from_reserved"));
    }
}
