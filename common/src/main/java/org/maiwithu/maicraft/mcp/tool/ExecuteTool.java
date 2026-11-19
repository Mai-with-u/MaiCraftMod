// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.perception.looking.LooksAfterReading;
import org.maiwithu.maicraft.behavior.perception.looking.PointsAt;
import org.maiwithu.maicraft.behavior.perception.looking.WhatWasRead;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.GoalEstimate;
import org.maiwithu.maicraft.kernel.ability.GoalEstimates;
import org.maiwithu.maicraft.kernel.ability.GoalEstimates.StepEstimate;
import org.maiwithu.maicraft.kernel.goal.DocumentGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.result.TaskResult;

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
    /** 跨真实游戏刻执行的只读旁路目标（如机器查看等 ME 网汇总）最多等待多久（毫秒）。 */
    static final long ASIDE_WAIT_MILLIS = 8_000;
    /** 在 MCP 工作线程上轮询异步旁路目标终态的间隔（约 1 游戏刻，避免空转占满客户端线程）。 */
    static final long ASIDE_POLL_MILLIS = 50;
    /** 旁路目标在客户端线程上的一次状态采样：未落定时 finishedJson 为 null，已落定时已在客户端线程序列化完毕。 */
    private record AsidePoll(long runId, JsonObject finishedJson) {}

    private final AbilityRegistry registry;
    private final GoalReader reader;
    private final GoalRunTable table;
    private final ClientThread clientThread;
    private final GoalEstimates estimates;
    /** 只读能力出完报告告诉闲时看：报告指着世界里的地方（看的那片机器、读的那一栋），角色闲着时顺着看过去。 */
    private final LooksAfterReading looks;
    private final long asideWaitMillis;
    private final long asidePollMillis;
    private final Map<String, Goal> plans = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Goal> eldest) {
            return size() > KEPT_PLANS;
        }
    };

    public ExecuteTool(AbilityRegistry registry, GoalRunTable table, ClientThread clientThread) {
        this(registry, table, clientThread, LooksAfterReading.NONE);
    }

    /** @param looks 只读能力出完报告告诉闲时看；不演的给 {@link LooksAfterReading#NONE} */
    public ExecuteTool(AbilityRegistry registry, GoalRunTable table, ClientThread clientThread, LooksAfterReading looks) {
        this(registry, table, clientThread, looks, ASIDE_WAIT_MILLIS, ASIDE_POLL_MILLIS);
    }

    ExecuteTool(AbilityRegistry registry, GoalRunTable table, ClientThread clientThread, LooksAfterReading looks,
            long asideWaitMillis, long asidePollMillis) {
        this.looks = Objects.requireNonNull(looks, "looks");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reader = new GoalReader(registry);
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
        this.estimates = new GoalEstimates(registry);
        this.asideWaitMillis = Math.max(1L, asideWaitMillis);
        this.asidePollMillis = Math.max(1L, asidePollMillis);
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

    // 控制角色的目标：成为主目标的，和插一下做完就让回去的。按这一次的目标问能力，和下达时内核的判断一致。
    private boolean controlsPlayer(Goal goal) {
        ExecutionMode mode = registry.find(goal.ability()).orElseThrow().modeFor(goal);
        return mode == ExecutionMode.CONTROLS_PLAYER || mode == ExecutionMode.CUTS_IN;
    }

    private JsonObject start(Goal goal, String requestKey, List<String> notes) {
        if (!controlsPlayer(goal)) {
            // 只读分析、只改记忆：先当场起跑；立即可结的直接返回，需跨刻等网回包的在工作线程等真实游戏刻跑完再返回，
            // 全程只在客户端线程读取和序列化 GoalRun，避免跨线程数据竞争。
            return ToolReply.ok(runAsideUntilSettled(goal), notes, null);
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

    // 启动旁路只读目标并在需要跨真实游戏刻等网回包时于 MCP 工作线程轮询至落定：
    // GoalRun 仅在客户端主线程读写与序列化；轮询期容忍瞬时 ClientThread.Busy；超时或线程被中断时在客户端线程
    // 显式取消并移出旁路运行表后再取终态 JSON，杜绝残留孤儿任务或返回未完结半成品。
    private JsonObject runAsideUntilSettled(Goal goal) {
        AsidePoll first = clientThread.call(context -> snapshotAside(table.runAside(goal, context)));
        if (first.finishedJson() != null) return first.finishedJson();
        long runId = first.runId();
        long deadline = System.currentTimeMillis() + asideWaitMillis;
        boolean interrupted = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(asidePollMillis);
            } catch (InterruptedException caught) {
                interrupted = true;
                break;
            }
            try {
                AsidePoll polled = clientThread.call(context -> table.find(runId).map(this::snapshotAside).orElse(null));
                if (polled == null) break;
                if (polled.finishedJson() != null) return polled.finishedJson();
            } catch (ClientThread.Busy transientBusy) {
                // 客户端主线程瞬时繁忙：在截止时间前继续下一轮采样，不误中断正在等回包的旁路目标。
            }
        }
        // 中断退出时先在清除中断位状态下把取消请求送到客户端线程，收尾完毕后再恢复当前线程中断标记。
        try {
            return cancelAsideOnClient(runId, goal.ability(),
                    interrupted ? "等待只读目标结果时被中断" : "等待只读目标结果超时");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    // 在客户端主线程安全采样旁路目标：未完成时只带出编号，已完成时同步通知闲时看并当场序列化成不可变 JSON。
    private AsidePoll snapshotAside(GoalRun run) {
        if (run.unfinished()) return new AsidePoll(run.id(), null);
        notifyLookIfFinished(run);
        return new AsidePoll(run.id(), ResultJson.goalRun(run, null, null));
    }

    // 超时或中断后的客户端收尾：取消并移出仍在运行的旁路目标后序列化终态；若角色已离世界或短暂繁忙则兜底返回终态 JSON。
    private JsonObject cancelAsideOnClient(long runId, String ability, String fallbackSummary) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JsonObject settled = clientThread.call(context -> table.cancelAside(runId)
                        .map(run -> {
                            notifyLookIfFinished(run);
                            return ResultJson.goalRun(run, null, null);
                        })
                        .orElse(null));
                if (settled != null) return settled;
                return cancelledFallbackJson(runId, ability, fallbackSummary);
            } catch (ClientThread.Busy transientBusy) {
                if (attempt == 2) return cancelledFallbackJson(runId, ability, fallbackSummary);
            } catch (ClientThread.NotInWorld leftWorld) {
                // 角色已离开世界，leaveWorld() 已统一取消并清空旁路任务表。
                return cancelledFallbackJson(runId, ability, fallbackSummary);
            }
        }
        return cancelledFallbackJson(runId, ability, fallbackSummary);
    }

    private static JsonObject cancelledFallbackJson(long runId, String ability, String summary) {
        JsonObject json = new JsonObject();
        json.addProperty("goal_id", runId);
        json.addProperty("ability", ability);
        json.addProperty("state", "finished");
        json.add("result", ResultJson.result(TaskResult.cancelled(summary)));
        return json;
    }

    // 报告指着世界里的地方：角色闲着时从左到右扫过那片机器、那一栋，报告照旧当场返回。
    private void notifyLookIfFinished(GoalRun run) {
        if (!run.unfinished() && run.result() != null && run.result().details() instanceof PointsAt points) {
            WhatWasRead pointed = WhatWasRead.pointedAt(points);
            if (pointed != null) looks.justRead(pointed);
        }
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
