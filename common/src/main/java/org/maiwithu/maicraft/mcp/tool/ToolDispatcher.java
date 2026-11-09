// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按工具名把调用交给对应的工具，并把工具抛出的已知异常换成统一的错误种类。
 *
 * <p>工具调用永远得到一个统一格式的结果：程序出错也只是 {@code internal_error}，
 * 不会让 MCP 连接收到一个没有说明的失败。成功的回包大到超过单次上限时整体退回
 * {@code too_large}，不让一笔查询把 LLM 的上下文撑爆。
 */
public final class ToolDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ToolDispatcher.class);

    /** 单条工具回包序列化后的字符上限：超过就整体退回，换成 too_large。 */
    static final int MAX_REPLY_CHARS = 50_000;

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final RecentToolCalls recentCalls;

    public ToolDispatcher(List<McpTool> list) {
        this(list, new RecentToolCalls());
    }

    /** @param recentCalls 每次调用进来、结束都记在这里，调试面板读它看 LLM 发了什么 */
    public ToolDispatcher(List<McpTool> list, RecentToolCalls recentCalls) {
        this.recentCalls = Objects.requireNonNull(recentCalls, "recentCalls");
        for (McpTool tool : list) {
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("工具 " + tool.name() + " 重复登记");
            }
        }
    }

    /** 这个工具已经有行为（没接上的工具由传输层回答"还没接入"）。 */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** 调用一个已接上的工具；进来和结束都记进最近的调用，面板上看得到 LLM 发了什么、成没成。 */
    public JsonObject call(String name, JsonObject arguments) {
        return answer(name, arguments).reply();
    }

    /** 同 {@link #call}，连同工具另带的图一起交回；最近的调用只记 JSON，图的字节不进面板。 */
    public ToolReply.WithImages answer(String name, JsonObject arguments) {
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("工具 " + name + " 没有接上");
        }
        long callId = recentCalls.started(name, arguments);
        long startedAtNanos = System.nanoTime();
        ToolReply.WithImages answer = null;
        try {
            answer = tooLargeGuard(name, dispatch(name, tool, arguments));
            return answer;
        } finally {
            // 连 Error 这类没接住的情况也要结账：不然这次调用会一直挂成"处理中"，面板就误以为宿主还在等。
            recentCalls.ended(callId, answer == null ? null : answer.reply());
            logCall(name, callId, System.nanoTime() - startedAtNanos);
        }
    }

    // 成功的回包序列化后超过上限就整体退回，换成 too_large：LLM 收到收窄建议，而不是一笔塞爆上下文的巨包。
    // 在分发之后、结账之前量与换，调试面板和文件日志记下的就是宿主实际收到的那份。
    private static ToolReply.WithImages tooLargeGuard(String name, ToolReply.WithImages answer) {
        JsonObject reply = answer.reply();
        if (!reply.get("ok").getAsBoolean()) return answer;
        int size = ToolCatalog.GSON.toJson(reply).length();
        if (size <= MAX_REPLY_CHARS) return answer;
        return ToolReply.WithImages.of(ToolReply.error(ErrorCode.TOO_LARGE, "工具 " + name + " 的回答约 " + size
                + " 字符，超过单次上限 " + MAX_REPLY_CHARS + "，这次不返回数据；把问题收窄（更具体的关键词或对象、分次问）再试"));
    }

    /**
     * 每次调用在文件日志里留一行：工具名、用了多久、成没成。
     *
     * <p>调试面板上的记录进程一退就没了，宿主事后查"对端为什么这么答"只剩这一行。
     * 成没成、错误码与说明都取自这次调用在 {@link RecentToolCalls} 里的记录，不在这里另解一遍回话；
     * 出错时把参数一并记下——宿主那边只留着发出去的一份，对端收到后为什么这么答就靠这一行对得上。
     */
    private void logCall(String name, long callId, long elapsedNanos) {
        RecentToolCalls.Call call = recentCalls.recent().stream()
                .filter(entry -> entry.id() == callId)
                .findFirst()
                .orElse(null);
        long elapsedMillis = elapsedNanos / 1_000_000;
        if (call == null) {
            LOG.info("MCP 工具 {} 用时 {}ms，这一条没记上", name, elapsedMillis);
        } else if (call.errorCode() == null) {
            LOG.info("MCP 工具 {} 用时 {}ms，完成", name, elapsedMillis);
        } else {
            LOG.warn("MCP 工具 {} 用时 {}ms，失败：{} {}；参数 {}", name, elapsedMillis,
                    call.errorCode(), call.errorMessage(), call.arguments());
        }
    }

    private ToolReply.WithImages dispatch(String name, McpTool tool, JsonObject arguments) {
        try {
            return tool.answer(arguments == null ? new JsonObject() : arguments);
        } catch (ClientThread.NotInWorld exception) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.NOT_IN_WORLD, "角色不在世界里：进入世界后再试"));
        } catch (ClientThread.Busy exception) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.BUSY, exception.getMessage()));
        } catch (GoalRunTable.UnknownGoalRun exception) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.UNKNOWN_ID, exception.getMessage()));
        } catch (GoalRunTable.WrongGoalRunState exception) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.INVALID_PARAMETER, exception.getMessage()));
        } catch (RuntimeException exception) {
            LOG.warn("MCP 工具 {} 出错", name, exception);
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.INTERNAL_ERROR, "工具 " + name + " 出错：" + exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : "：" + exception.getMessage())));
        }
    }
}
