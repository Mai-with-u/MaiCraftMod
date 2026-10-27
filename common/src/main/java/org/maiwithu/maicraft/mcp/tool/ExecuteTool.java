// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimates;
import org.maiwithu.maicraft.kernel.ability.GoalEstimates.StepEstimate;
import org.maiwithu.maicraft.kernel.goal.DocumentGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * execute：开始做一件事。不是 Minecraft 的 /execute 命令。
 *
 * <p>控制角色的目标交给目标运行表，成为角色的主任务，原来的主任务被替换；马上返回目标编号，
 * 进展用 events 等、用 goal 查。只读分析、只改记忆的目标当场做完，结果直接返回，不打断手上的活。
 *
 * <p>{@code dry_run=true} 只检查目标、不动手，返回一个计划编号和一份估算（此刻要花多大功夫）；之后用 {@code plan_id}
 * 执行同一个目标。下达 sequence 时回复也带一份逐步的估算，不等附近扫完，LLM 看到后面某步便宜得多可以取消重排。
 */
public final class ExecuteTool implements McpTool {
    /** 检查过的计划最多留几份；LLM 一般检查完马上执行，留太多没有意义。 */
    static final int KEPT_PLANS = 32;
    /** dry_run 估算时附近还没扫完最多等多久：和拿东西动手前等扫描的五秒一样。 */
    static final long ESTIMATE_WAIT_MILLIS = 5_000;
    /** 等扫描时隔多久再估一次。 */
    static final long ESTIMATE_RETRY_MILLIS = 250;

    private final AbilityRegistry registry;
    private final GoalReader reader;
    private final GoalRunTable table;
    private final ClientThread clientThread;
    private final GoalEstimates estimates;
    private final Map<String, Goal> plans = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Goal> eldest) {
            return size() > KEPT_PLANS;
        }
    };

    public ExecuteTool(AbilityRegistry registry, GoalRunTable table, ClientThread clientThread) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reader = new GoalReader(registry);
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
        this.estimates = new GoalEstimates(registry);
    }

    @Override public String name() {
        return ToolCatalog.EXECUTE;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("goal", "dry_run", "plan_id", "request_key"));
        Boolean dryRun = check.bool(arguments, "dry_run", "dry_run");
        String planId = check.text(arguments, "plan_id", "plan_id", false);
        String requestKey = check.text(arguments, "request_key", "request_key", false);
        boolean hasGoal = arguments.has("goal") && !arguments.get("goal").isJsonNull();
        if (hasGoal == (planId != null)) {
            check.error(hasGoal ? "plan_id" : "goal", "goal 和 plan_id 要给一个，且只给一个", "新目标给 goal；执行检查过的计划给 plan_id");
        }
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        if (planId != null) {
            Goal planned = takePlan(planId);
            if (planned == null) {
                return ToolReply.error(ErrorCode.UNKNOWN_ID, "没有编号为 " + planId + " 的计划，或者已经执行过；重新 dry_run 一次");
            }
            return start(planned, requestKey, check.notes());
        }
        GoalReader.Reading reading = reader.read(arguments.get("goal"));
        List<String> notes = concat(check.notes(), reading.notes());
        if (!reading.ok()) {
            return ToolReply.error(reading.code(), "目标有 " + reading.errors().size() + " 处问题", reading.errors(), null);
        }
        // 控制角色的目标要存盘才能暂停、提问和重启后恢复：存不下的大图纸当场按参数问题拒，说清怎么改，
        // 不等下达后存盘失败只回一句程序错误。只读分析、只改记忆的（design 本身）当场做完，不受这条限制。
        if (controlsPlayer(reading.goal())) {
            Optional<String> tooLarge = DocumentGoalRunStore.tooLargeToSave(reading.goal());
            if (tooLarge.isPresent()) return ToolReply.error(ErrorCode.INVALID_PARAMETER, tooLarge.get());
        }
        if (Boolean.TRUE.equals(dryRun)) {
            JsonObject data = new JsonObject();
            data.addProperty("plan_id", keepPlan(reading.goal()));
            data.addProperty("ability", reading.goal().ability());
            data.addProperty("checked", "目标的写法、参数、目标对象与许可都没有问题");
            data.add("estimate", estimate(reading.goal(), true));
            return ToolReply.ok(data, notes, null);
        }
        return start(reading.goal(), requestKey, notes);
    }

    private boolean controlsPlayer(Goal goal) {
        return registry.find(goal.ability()).orElseThrow().spec().mode() == ExecutionMode.CONTROLS_PLAYER;
    }

    private JsonObject start(Goal goal, String requestKey, List<String> notes) {
        if (!controlsPlayer(goal)) {
            // 只读分析、只改记忆：当场做完，不交给控制循环，手上的主任务照常进行。
            GoalRun run = clientThread.call(context -> table.runAside(goal, context));
            return ToolReply.ok(ResultJson.goalRun(run, null, null), notes, null);
        }
        String key = requestKey == null ? UUID.randomUUID().toString() : requestKey;
        GoalRunTable.Launch launch = clientThread.call(context -> table.launch(goal, key));
        GoalRun run = launch.runner().run();
        JsonObject data = new JsonObject();
        data.addProperty("goal_id", run.id());
        data.addProperty("ability", goal.ability());
        data.addProperty("state", ResultJson.lower(run.state()));
        if (launch.repeated()) {
            data.addProperty("repeated", true);
        }
        // 下达 sequence 也附一份逐步估算：小模型不一定先 dry_run，看到后面某步便宜得多可以取消重排。不等扫描。
        if (!goal.steps().isEmpty()) {
            data.add("estimate", estimate(goal, false));
        }
        JsonObject wait = new JsonObject();
        wait.addProperty("goal_id", run.id());
        wait.addProperty("wait_ms", EventsTool.DEFAULT_WAIT_MS);
        return ToolReply.ok(data, notes, ToolReply.next(ToolCatalog.EVENTS, wait));
    }

    // 估算在客户端线程上问价；dry_run 时附近还没扫完就隔一会儿再估，最多等 5 秒（在 MCP 请求线程上等，不卡游戏）。
    // 角色不在世界里、客户端忙时如实说估不了，不影响检查与下达。
    private JsonObject estimate(Goal goal, boolean waitForScans) {
        long deadline = System.currentTimeMillis() + ESTIMATE_WAIT_MILLIS;
        while (true) {
            JsonObject json;
            boolean stillLooking;
            try {
                if (goal.steps().isEmpty()) {
                    GoalEstimate single = clientThread.call(context -> estimates.single(goal, context));
                    json = EstimateJson.of(single);
                    stillLooking = single.stillLooking();
                } else {
                    List<StepEstimate> steps = clientThread.call(context -> estimates.steps(goal, 0, context));
                    json = EstimateJson.steps(steps);
                    stillLooking = steps.stream().anyMatch(StepEstimate::stillLooking);
                }
            } catch (ClientThread.NotInWorld | ClientThread.Busy unavailable) {
                return EstimateJson.of(GoalEstimate.unknown(goal.ability(), "此刻估不了：" + unavailable.getMessage()));
            }
            if (!waitForScans || !stillLooking || System.currentTimeMillis() >= deadline) return json;
            try {
                Thread.sleep(ESTIMATE_RETRY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return json;
            }
        }
    }

    private synchronized String keepPlan(Goal goal) {
        String id = "p" + UUID.randomUUID().toString().substring(0, 8);
        plans.put(id, goal);
        return id;
    }

    /** 计划只能执行一次：执行后从表里拿掉，重复执行要重新检查。 */
    private synchronized Goal takePlan(String id) {
        return plans.remove(id);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        return Stream.concat(first.stream(), second.stream()).toList();
    }
}
