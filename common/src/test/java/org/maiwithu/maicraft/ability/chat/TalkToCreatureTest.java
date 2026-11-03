// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.chat.spi.TalkTo;
import org.maiwithu.maicraft.game.interaction.ChatDraftScreen;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 对一只生物说话：不会听的按做不到，模组装了没登记按不支持，话太长让改短，它在想上一句先等，
 * 说完就走、等到回话、没等到回话、没看到反应、它改了自己的状态，各自如实交代。
 */
class TalkToCreatureTest {

    private static final ChatDraftScreen NO_BOX = new ChatDraftScreen() {
        @Override public boolean show(String draft) { return false; }
        @Override public boolean showing() { return false; }
        @Override public void close() { }
    };

    private static final ReadsChatEcho SILENT_ECHO = new ReadsChatEcho() {
        @Override public boolean appearsInChat(String message) { return false; }
        @Override public long mark() { return 0; }
        @Override public List<String> shownSince(long mark) { return List.of(); }
    };

    /** 刻号由测试推进的本刻上下文。 */
    private static final class TestTick implements TickContext {
        private long gameTick = 1000;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 一只会听的生物替身：在想几刻、对话框收几个字、它的结局都由测试摆。 */
    private static final class FakeListener implements TalkTo.Listener {
        int thinkingTicks;
        int maxLength = 128;
        Problem refusal;
        TalkTo.Heard heard = new TalkTo.Heard(true, "好的主人", false, false, List.of(), List.of(), null, null);
        Problem fails;
        final List<String> said = new ArrayList<>();
        final List<Integer> waits = new ArrayList<>();
        /** 说话动作被推进了几刻。 */
        int talkTicks;

        @Override public String name() {
            return "小雪";
        }

        @Override public int maxLength() {
            return maxLength;
        }

        @Override public Optional<Problem> refusal() {
            return Optional.ofNullable(refusal);
        }

        @Override public boolean thinking() {
            return thinkingTicks-- > 0;
        }

        @Override public TalkTo.Talk talk(String message, int maxSeconds, boolean typewriter, Permissions permissions) {
            said.add(message);
            waits.add(maxSeconds);
            return new TalkTo.Talk() {
                @Override public ActionStatus tick(TickContext context) {
                    if (++talkTicks < 3) return ActionStatus.running();
                    return fails == null ? ActionStatus.done() : ActionStatus.failed(fails);
                }

                @Override public String describe() {
                    return "对小雪说话";
                }

                @Override public TalkTo.Heard heard() {
                    return talkTicks < 3 ? TalkTo.Heard.nothing() : heard;
                }
            };
        }
    }

    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    private static Goal goal(ChatAbility chat, String params, Target target) {
        ParseResult parsed = chat.spec().paramSpecs().parse(JsonParser.parseString(params).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        return new Goal("maicraft:chat", null, target, parsed.params(), Permissions.DEFAULT, List.of(), null);
    }

    private static ChatAbility chat(FindsListener listeners) {
        return new ChatAbility(message -> true, SILENT_ECHO, NO_BOX, false, false, KnowsAdminCommands.NONE, listeners);
    }

    /** 照真实路径开一条任务：决定阶段出输入，任务工厂造任务，推进到出结果。 */
    private static TaskResult run(FindsListener listeners, String params, Target target) {
        ChatAbility chat = chat(listeners);
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, chat.decide(step(goal(chat, params, target))));
        TalkInput input = assertInstanceOf(TalkInput.class, run.input());
        TalkTask task = new TalkTask(input, listeners, false);
        TestTick tick = new TestTick();
        task.start(tick);
        for (int i = 0; i < 3000; i++) {
            if (task.tick(tick) instanceof TickResult.Finished finished) return finished.result();
            tick.gameTick++;
        }
        throw new AssertionError("说话任务没有按时收场");
    }

    private static FindsListener listening(FakeListener listener) {
        return seenId -> new FindsListener.Found.Listening(listener);
    }

    @Test
    void 给了生物目标_不进公屏也不当命令() {
        FakeListener listener = new FakeListener();
        TaskResult result = run(listening(listener), "{\"message\":\"/坐下\"}", new Target.Seen("e3"));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("/坐下"), listener.said, "/ 开头的照原话说给它听");
        assertEquals(List.of(30), listener.waits, "等回话默认 30 秒");
        TalkTask.TalkDetails details = assertInstanceOf(TalkTask.TalkDetails.class, result.details());
        assertEquals("好的主人", details.reply());
        assertEquals("e3", details.to().id());
        assertEquals("小雪", details.to().name());
        assertTrue(result.summary().contains("好的主人"));
    }

    @Test
    void 它不会听人说话_按做不到() {
        TaskResult result = run(id -> new FindsListener.Found.Deaf("minecraft:cow", "minecraft", null),
                "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
    }

    @Test
    void 模组装了联动没登记_按不支持并说看日志() {
        TaskResult result = run(id -> new FindsListener.Found.Deaf("touhou_little_maid:maid", "touhou_little_maid",
                "装的是 1.6.0，验证过的范围是 1.5.3 到 1.5.4，不登记"), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertTrue(result.problem().message().contains("联动没登记"));
    }

    @Test
    void 它不在了_按目标没了() {
        TaskResult result = run(id -> new FindsListener.Found.Gone(), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertEquals(Problem.Kind.TARGET_GONE, result.problem().kind());
    }

    @Test
    void 话超过它的对话框收的字数_让改短不拆开说() {
        FakeListener listener = new FakeListener();
        listener.maxLength = 4;
        TaskResult result = run(listening(listener), "{\"message\":\"你好呀主人\"}", new Target.Seen("e3"));
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(listener.said.isEmpty(), "超长不说出去");
    }

    @Test
    void 不是你的_听法的拒绝原样交回() {
        FakeListener listener = new FakeListener();
        listener.refusal = Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "她不是你的女仆", null);
        TaskResult result = run(listening(listener), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(listener.said.isEmpty());
    }

    @Test
    void 它还在想上一句_先等它想完再说() {
        FakeListener listener = new FakeListener();
        listener.thinkingTicks = 40;
        TaskResult result = run(listening(listener), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of("你好"), listener.said);
        assertTrue(listener.thinkingTicks < 0, "等它想完才开口");
    }

    @Test
    void 它一直在想_等满就不说() {
        FakeListener listener = new FakeListener();
        listener.thinkingTicks = 10_000;
        TaskResult result = run(listening(listener), "{\"message\":\"你好\",\"max_seconds\":2}", new Target.Seen("e3"));
        assertEquals(Problem.Kind.WRONG_TIME, result.problem().kind());
        assertTrue(listener.said.isEmpty(), "不叠着说");
    }

    @Test
    void 说完就走_不等回话() {
        FakeListener listener = new FakeListener();
        listener.heard = new TalkTo.Heard(true, null, true, false, List.of(), List.of(), null, null);
        TaskResult result = run(listening(listener), "{\"message\":\"你好\",\"max_seconds\":0}", new Target.Seen("e3"));
        assertEquals(List.of(0), listener.waits);
        assertTrue(result.summary().contains("没等回话"));
        TalkTask.TalkDetails details = assertInstanceOf(TalkTask.TalkDetails.class, result.details());
        assertTrue(details.replyPending());
        assertNull(details.reply());
    }

    @Test
    void 没等到回话_说回话之后会进事件() {
        FakeListener listener = new FakeListener();
        listener.heard = new TalkTo.Heard(true, null, true, false, List.of("服务器公告"), List.of(), null, null);
        TaskResult result = run(listening(listener), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertTrue(result.summary().contains("events(topic=chat)"));
        TalkTask.TalkDetails details = assertInstanceOf(TalkTask.TalkDetails.class, result.details());
        assertEquals(List.of("服务器公告"), details.notices());
    }

    @Test
    void 只生成了人设_写明要再说一遍() {
        FakeListener listener = new FakeListener();
        listener.heard = new TalkTo.Heard(true, null, false, true, List.of(), List.of(), null, null);
        TaskResult result = run(listening(listener), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertTrue(result.summary().contains("人设"));
        TalkTask.TalkDetails details = assertInstanceOf(TalkTask.TalkDetails.class, result.details());
        assertEquals(Boolean.TRUE, details.personaGenerated());
    }

    @Test
    void 交出去没看到反应_记进没能确认不重说() {
        FakeListener listener = new FakeListener();
        listener.heard = new TalkTo.Heard(false, null, false, false, List.of(), List.of(), "对话框关了，她没冒出在想的气泡", null);
        TaskResult result = run(listening(listener), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertEquals(1, result.unconfirmed().size());
        assertEquals(1, listener.said.size());
        assertFalse(assertInstanceOf(TalkTask.TalkDetails.class, result.details()).delivered());
    }

    @Test
    void 她自己改了状态_按生物受影响列出() {
        FakeListener listener = new FakeListener();
        Change sat = new Change(Change.Kind.ENTITY_AFFECTED, "小雪", 1, "她自己坐下了");
        listener.heard = new TalkTo.Heard(true, "我坐下啦", false, false, List.of(), List.of(sat), null, null);
        TaskResult result = run(listening(listener), "{\"message\":\"坐下\"}", new Target.Seen("e3"));
        assertEquals(List.of(sat), result.changes());
    }

    @Test
    void 她那边回了提示_失败原样交回() {
        FakeListener listener = new FakeListener();
        listener.fails = Problem.of(Problem.Kind.REFUSED_BY_GAME, "她那边回了提示：对话功能已被禁用", null);
        listener.heard = new TalkTo.Heard(true, null, false, false, List.of("对话功能已被禁用"), List.of(), null, null);
        TaskResult result = run(listening(listener), "{\"message\":\"你好\"}", new Target.Seen("e3"));
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
        assertEquals(List.of("对话功能已被禁用"),
                assertInstanceOf(TalkTask.TalkDetails.class, result.details()).notices());
    }

    @Test
    void 目标不是实体编号_参数不对() {
        ChatAbility chat = chat(FindsListener.NONE);
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                chat.decide(step(goal(chat, "{\"message\":\"你好\"}", new Target.Seen("b3")))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
    }

    @Test
    void 对全体玩家说却给了等回话秒数_参数不对() {
        ChatAbility chat = chat(FindsListener.NONE);
        StepDecision.Finish finish = assertInstanceOf(StepDecision.Finish.class,
                chat.decide(step(goal(chat, "{\"message\":\"你好\",\"max_seconds\":5}", null))));
        assertEquals(Problem.Kind.INVALID_PARAMETER, finish.result().problem().kind());
    }
}
