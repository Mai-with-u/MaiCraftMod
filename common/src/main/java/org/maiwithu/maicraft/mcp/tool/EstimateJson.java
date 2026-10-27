// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimates.StepEstimate;

/**
 * 估算写给 LLM 看的样子：档位 effort、估的是什么 what、怎么弄 how，算得出来时带最远多远 farthest_blocks
 * 与粗估秒数 rough_seconds，一次要几样时逐样 items；sequence 是逐步的 steps。
 */
final class EstimateJson {

    private EstimateJson() {}

    static JsonObject of(GoalEstimate estimate) {
        JsonObject json = new JsonObject();
        write(estimate, json);
        return json;
    }

    /** sequence：每一步一条，第几步从 1 数；步骤本身是 sequence 的里面再套 steps。 */
    static JsonObject steps(List<StepEstimate> steps) {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        for (StepEstimate step : steps) array.add(step(step));
        json.add("steps", array);
        return json;
    }

    private static JsonObject step(StepEstimate step) {
        JsonObject json = new JsonObject();
        json.addProperty("step", step.index() + 1);
        json.addProperty("ability", step.goal().ability());
        if (step.goal().purpose() != null && !step.goal().purpose().isBlank()) {
            json.addProperty("purpose", step.goal().purpose());
        }
        if (step.estimate() != null) {
            write(step.estimate(), json);
        } else {
            json.add("steps", steps(step.steps()).get("steps"));
        }
        return json;
    }

    private static void write(GoalEstimate estimate, JsonObject json) {
        json.addProperty("effort", estimate.effort().wireName());
        json.addProperty("what", estimate.what());
        json.addProperty("how", estimate.how());
        // 距离说整数格就够：估算只说个大概。
        if (estimate.farthestBlocks() != null) json.addProperty("farthest_blocks", Math.round(estimate.farthestBlocks()));
        if (estimate.roughSeconds() != null) json.addProperty("rough_seconds", estimate.roughSeconds());
        if (!estimate.items().isEmpty()) {
            JsonArray items = new JsonArray();
            for (GoalEstimate item : estimate.items()) items.add(of(item));
            json.add("items", items);
        }
    }
}
