// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

/**
 * 列能力时的一行签名要带上参数能取的值：选项参数写出全部可选值，数值参数写出上下限。
 * 宿主只看得到这一行；不写清楚它，模型就只能猜，猜错的取值会被参数校验拒下。
 */
class LookupSignatureTest {

    @Test
    void 选项参数在签名里写出全部可选值() {
        assertEquals("operation: string (look / crouch / swing), count: integer (1..64) = 1", listingParameters());
    }

    @Test
    void 详情页的参数表照旧是完整的一份() {
        JsonObject query = new JsonObject();
        query.addProperty("id", "gesture");
        JsonObject data = lookup().call(query).getAsJsonObject("data");

        JsonObject operation = data.getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertEquals("operation", operation.get("name").getAsString());
        assertEquals("[\"look\",\"crouch\",\"swing\"]", operation.get("choices").toString());
    }

    /** 列能力时那一行签名里的参数写法。 */
    private static String listingParameters() {
        JsonObject data = lookup().call(new JsonObject()).getAsJsonObject("data");
        return data.getAsJsonArray("abilities").get(0).getAsJsonObject().get("parameters").getAsString();
    }

    private static LookupTool lookup() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories(), modId -> false);
        registry.register(ToolTestAbility.gesture());
        return new LookupTool(registry, KnowledgeLibrary.offline());
    }
}
