// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.game.MentionedEntity;
import org.maiwithu.maicraft.game.ReceivedChat;
import org.maiwithu.maicraft.game.world.SaveIdentity;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 四个接上行为的工具：lookup、execute、goal、events，以及调度时把异常换成错误种类。 */
class ToolsTest {

    /** 测试用的本刻上下文：只有刻号。 */
    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    private final ToolTestAbility use = ToolTestAbility.use();
    private final ToolTestAbility remember = ToolTestAbility.remember();
    private final ControlLoop loop = new ControlLoop(List.of());
    private final TaskEventLog events = new TaskEventLog();
    private final ChatEventLog chat = new ChatEventLog();
    private final List<String> remembered = new ArrayList<>();
    private long tick = 100;
    private boolean inWorld = true;
    /** 角色眼下在哪个世界：读事件每一页都带它；null 是不在世界里或还没认出世界。 */
    private volatile SaveIdentity world;
    private final ToolDispatcher tools;

    ToolsTest() {
        AbilityRegistry registry = new AbilityRegistry(new TaskFactories());
        registry.register(use);
        registry.register(ToolTestAbility.sleep());
        registry.register(remember);
        registry.register(ToolTestAbility.hidden());
        // 控制权交接给空实现：工具测试不接输入层。
        GoalRunTable table = new GoalRunTable(registry,
                new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), events),
                (name, position) -> remembered.add(name), loop, new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() {
                        return true;
                    }

                    @Override public void requestControl() {
                    }
                });
        ClientThread direct = new ClientThread() {
            @Override public <T> T call(Function<TickContext, T> work) {
                if (!inWorld) throw new NotInWorld();
                return work.apply(new Tick(tick));
            }
        };
        tools = new ToolDispatcher(List.of(new LookupTool(registry, KnowledgeLibrary.offline()), new ExecuteTool(registry, table, direct),
                new GoalTool(table, direct), new EventsTool(events, chat, table, direct, () -> world)));
    }

    private JsonObject call(String tool, String arguments) {
        return tools.call(tool, JsonParser.parseString(arguments).getAsJsonObject());
    }

    private static JsonObject data(JsonObject reply) {
        assertTrue(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("data");
    }

    private static String errorCode(JsonObject reply) {
        assertFalse(reply.get("ok").getAsBoolean(), reply::toString);
        return reply.getAsJsonObject("error").get("code").getAsString();
    }

    private void runTicks(int count) {
        for (int i = 0; i < count; i++) loop.tick(new Tick(tick++));
    }

    @Test
    void lookupListsSignaturesOfListedAbilitiesOnly() {
        JsonArray abilities = data(call("lookup", "{}")).getAsJsonArray("abilities");

        assertEquals(3, abilities.size());
        JsonObject first = abilities.get(0).getAsJsonObject();
        assertEquals("maicraft:use", first.get("ability").getAsString());
        assertEquals("item?: string, count: integer = 1", first.get("parameters").getAsString());
        assertEquals("[\"position\",\"seen\"]", first.get("targets").toString());
    }

    @Test
    void lookupByIdGivesTheParameterTableAndFindsHiddenAbilities() {
        JsonObject detail = data(call("lookup", "{\"id\": \"use\"}"));
        JsonObject hidden = data(call("lookup", "{\"id\": \"maicraft:debug_probe\"}"));

        assertEquals(2, detail.getAsJsonArray("parameters").size());
        assertEquals("read_only", hidden.get("mode").getAsString());
        assertEquals("unknown_ability", errorCode(call("lookup", "{\"id\": \"sleeep\"}")));
        assertEquals("invalid_parameter", errorCode(call("lookup", "{\"topic\": \"wiki\"}")));
    }

    @Test
    void lookupKnowledgeListsFindsAndReadsTheBundledMechanics() {
        String oreHeights = "maicraft://knowledge/game_mechanics/ore-heights";
        // 目录列出随包的常识，每篇有地址、标题和一句话说明；索引那一篇不列，目录本身就是索引。
        List<String> listed = new ArrayList<>();
        for (var row : data(call("lookup", "{\"topic\": \"knowledge\"}")).getAsJsonArray("knowledge")) {
            JsonObject entry = row.getAsJsonObject();
            assertFalse(entry.get("summary").getAsString().isBlank(), "每篇都有一句话说明");
            listed.add(entry.get("id").getAsString());
        }
        assertTrue(listed.contains(oreHeights));
        assertFalse(listed.contains("maicraft://knowledge/index"));

        // 不带空格的一句问话也能找到：条目的检索词出现在问句里就算命中，并建议读排在最前的那篇。
        JsonObject found = call("lookup", "{\"topic\": \"knowledge\", \"query\": \"钻石在哪一层\"}");
        assertEquals(oreHeights, data(found).getAsJsonArray("knowledge").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(oreHeights, found.getAsJsonObject("next").getAsJsonObject("arguments").get("id").getAsString());

        // 末段名就能读，按哪篇读的写进 notes；没有这篇时如实说编号不存在，不拿相近的顶替。
        JsonObject read = call("lookup", "{\"topic\": \"knowledge\", \"id\": \"food\"}");
        assertEquals("maicraft://knowledge/game_mechanics/food", data(read).get("id").getAsString());
        assertTrue(data(read).get("text").getAsString().contains("饥饿"));
        assertTrue(read.has("notes"));
        assertEquals("unknown_id", errorCode(call("lookup", "{\"topic\": \"knowledge\", \"id\": \"no-such-page\"}")));

        // 没找到不等于没有这条规则：给空列表，并建议列出完整目录。
        JsonObject none = call("lookup", "{\"topic\": \"knowledge\", \"query\": \"zzzz\"}");
        assertEquals(0, data(none).getAsJsonArray("knowledge").size());
        assertFalse(none.getAsJsonObject("next").getAsJsonObject("arguments").has("id"));
    }

    @Test
    void executeRefusesGoalsTooLargeToSaveAndSaysHowToShrinkThem() {
        // 一个目标存盘超过上限（这里拿超长的 purpose 顶替大图纸）：当场按参数问题拒，说清多大、先存成图纸再按 design_id 下达，
        // 不等下达后存盘失败只回一句程序错误。dry_run 也一样拒。
        String huge = "砌".repeat(30000);
        JsonObject refused = call("execute", "{\"goal\": {\"ability\": \"use\", \"purpose\": \"" + huge + "\"}}");
        assertEquals("invalid_parameter", errorCode(refused));
        String message = refused.getAsJsonObject("error").get("message").getAsString();
        assertTrue(message.contains("design_id"), message);
        assertEquals("invalid_parameter", errorCode(call("execute",
                "{\"goal\": {\"ability\": \"use\", \"purpose\": \"" + huge + "\"}, \"dry_run\": true}")));
        // 存得下的照常下达。
        data(call("execute", "{\"goal\": {\"ability\": \"use\", \"purpose\": \"砌墙\"}}"));
    }

    @Test
    void executeReportsEveryMistakeAndStartsGoodGoals() {
        assertEquals("invalid_parameter", errorCode(call("execute",
                "{\"goal\": {\"ability\": \"maicraft:use\", \"parameters\": {\"count\": 0}}}")));

        JsonObject reply = call("execute", "{\"goal\": {\"ability\": \"use\"}, \"request_key\": \"k1\"}");
        JsonObject started = data(reply);
        JsonObject again = data(call("execute", "{\"goal\": {\"ability\": \"use\"}, \"request_key\": \"k1\"}"));

        assertEquals(started.get("goal_id"), again.get("goal_id"));
        assertTrue(again.get("repeated").getAsBoolean());
        assertEquals("events", reply.getAsJsonObject("next").get("tool").getAsString());
    }

    @Test
    void dryRunGivesAPlanThatRunsOnce() {
        String planId = data(call("execute", "{\"goal\": {\"ability\": \"sleep\"}, \"dry_run\": true}"))
                .get("plan_id").getAsString();

        data(call("execute", "{\"plan_id\": \"" + planId + "\"}"));
        assertEquals("unknown_id", errorCode(call("execute", "{\"plan_id\": \"" + planId + "\"}")));
    }

    @Test
    void memoryOnlyGoalsFinishOnTheSpotWithoutReplacingTheMainTask() {
        data(call("execute", "{\"goal\": {\"ability\": \"use\"}}"));
        Object mainTask = loop.currentTask();
        remember.decision = new StepDecision.Finish(TaskResult.done("记住了"));

        JsonObject result = data(call("execute",
                "{\"goal\": {\"ability\": \"remember\", \"parameters\": {\"name\": \"家\"}}}"));

        assertEquals("finished", result.get("state").getAsString());
        assertEquals("done", result.getAsJsonObject("result").get("status").getAsString());
        assertEquals(mainTask, loop.currentTask());
    }

    @Test
    void taskAnswersMustPickAnOfferedOption() {
        use.decision = new StepDecision.Ask(new Question(Question.Reason.CHOOSE_ONE, "用哪个？",
                List.of(new Question.Option("b5", "门口的"), new Question.Option("b6", "屋里的"))));
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("goal_id").getAsLong();
        runTicks(2);

        JsonObject view = data(call("goal", "{\"operation\": \"get\", \"goal_id\": " + id + "}"));
        assertEquals("awaiting_answer", view.get("state").getAsString());
        assertEquals("b5", view.getAsJsonObject("question").getAsJsonArray("options").get(0)
                .getAsJsonObject().get("id").getAsString());
        assertEquals("invalid_parameter", errorCode(call("goal",
                "{\"operation\": \"answer\", \"goal_id\": " + id + ", \"answer\": \"b9\"}")));
        assertEquals("running", data(call("goal",
                "{\"operation\": \"answer\", \"goal_id\": " + id + ", \"answer\": \"b6\"}")).get("state").getAsString());
    }

    @Test
    void taskPausesResumesCancelsAndReportsUnknownIds() {
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("goal_id").getAsLong();

        assertEquals("paused", data(call("goal", "{\"operation\": \"pause\", \"goal_id\": " + id + "}"))
                .get("state").getAsString());
        assertEquals("running", data(call("goal", "{\"operation\": \"resume\", \"goal_id\": \"" + id + "\"}"))
                .get("state").getAsString());
        JsonObject cancelled = data(call("goal", "{\"operation\": \"cancel\", \"goal_id\": " + id + "}"));
        assertEquals("cancelled", cancelled.getAsJsonObject("result").get("status").getAsString());
        assertEquals(1, data(call("goal", "{\"operation\": \"list\"}")).getAsJsonArray("goals").size());
        assertEquals("unknown_id", errorCode(call("goal", "{\"operation\": \"get\", \"goal_id\": 999}")));
    }

    @Test
    void finishedGoalsFoldBigEvidenceAndReadItBackByPath() {
        // 结束了的目标默认只给结论与问题，超过一折的试过的办法折起来写明路径与几条；按路径读回原样，事件附的目标也是折起来的。
        TaskResult.Builder failed = TaskResult.builder(TaskResult.Status.FAILED, "没拿到圆石")
                .problem(Problem.of(Problem.Kind.NEED_ITEM, "附近箱子里都没有圆石"));
        for (int i = 0; i < 150; i++) {
            failed.attempt(new Attempt("开第 " + i + " 个箱子", "没有圆石"));
        }
        use.decision = new StepDecision.Finish(failed.build());
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("goal_id").getAsLong();
        assertEquals("invalid_parameter", errorCode(call("goal",
                "{\"operation\": \"get\", \"goal_id\": " + id + ", \"path\": \"/attempts\"}")), "还没结束没有结果可读");
        runTicks(2);

        JsonObject result = data(call("goal", "{\"operation\": \"get\", \"goal_id\": " + id + "}")).getAsJsonObject("result");
        assertEquals("NEED_ITEM", result.getAsJsonObject("problem").get("kind").getAsString());
        assertFalse(result.has("attempts"));
        JsonObject folded = result.getAsJsonArray("folded").get(0).getAsJsonObject();
        assertEquals("/attempts", folded.get("path").getAsString());
        assertEquals(150, folded.get("items").getAsInt());
        assertTrue(data(call("events", "{\"goal_id\": " + id + "}")).getAsJsonObject("goal")
                .getAsJsonObject("result").has("folded"), "事件附的目标也折起来");

        JsonObject page = data(call("goal", "{\"operation\": \"get\", \"goal_id\": " + id + ", \"path\": \"/attempts\"}"));
        assertEquals(150, page.get("total").getAsInt());
        assertEquals("开第 1 个箱子", page.getAsJsonArray("value").get(1).getAsJsonObject().get("tried").getAsString());
        JsonObject second = call("goal", "{\"operation\": \"get\", \"goal_id\": " + id
                + ", \"path\": \"/attempts\", \"limit\": 1}");
        assertEquals(1, second.getAsJsonObject("next").getAsJsonObject("arguments").get("offset").getAsInt(),
                "还有下一页时 next 照抄就是下一页");
        assertEquals("invalid_parameter", errorCode(call("goal",
                "{\"operation\": \"cancel\", \"goal_id\": " + id + ", \"path\": \"/attempts\"}")));
        assertEquals("invalid_parameter", errorCode(call("goal",
                "{\"operation\": \"get\", \"goal_id\": " + id + ", \"offset\": 1}")));
        assertEquals("invalid_parameter", errorCode(call("goal",
                "{\"operation\": \"get\", \"goal_id\": " + id + ", \"path\": \"/details/cells\"}")));
    }

    @Test
    void shortAttemptsStayInTheFinishedResult() {
        // 两三条短的试过的办法不折：没做成时一眼看到为什么，不用再按路径读一轮。
        use.decision = new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "没拿到圆石")
                .problem(Problem.of(Problem.Kind.NEED_ITEM, "附近箱子里都没有圆石"))
                .attempt(new Attempt("开门口的箱子", "没有圆石"))
                .attempt(new Attempt("开屋里的箱子", "没有圆石"))
                .build());
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("goal_id").getAsLong();
        runTicks(2);

        JsonObject result = data(call("goal", "{\"operation\": \"get\", \"goal_id\": " + id + "}")).getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("attempts").size());
        assertEquals("开屋里的箱子", result.getAsJsonArray("attempts").get(1).getAsJsonObject().get("tried").getAsString());
        assertFalse(result.has("folded"), "没有要折的就不出现 folded");
    }

    @Test
    void eventsFollowAGoalAndTellTheCallerHowToContinue() {
        long id = data(call("execute", "{\"goal\": {\"ability\": \"use\"}}")).get("goal_id").getAsLong();
        runTicks(2);

        JsonObject reply = call("events", "{\"goal_id\": " + id + "}");
        JsonObject page = data(reply);
        List<String> kinds = new ArrayList<>();
        page.getAsJsonArray("events").forEach(event -> kinds.add(event.getAsJsonObject().get("kind").getAsString()));

        assertEquals(List.of("started", "finished"), kinds);
        assertEquals("done", page.getAsJsonObject("goal").getAsJsonObject("result").get("status").getAsString());
        JsonObject next = reply.getAsJsonObject("next").getAsJsonObject("arguments");
        assertEquals(page.get("cursor"), next.get("after_cursor"));
        assertEquals(page.get("stream_id"), next.get("stream_id"));
    }

    @Test
    void chatIsItsOwnStreamAndKeepsTheSenderAndPrivateMessages() {
        UUID steve = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
        ReceivedChat.fromPlayer("麦麦过来", steve, "Steve", true, false, null).ifPresent(chat::append);
        ReceivedChat.fromPlayer("悄悄跟你说", steve, "Steve", true, true, null).ifPresent(chat::append);
        ReceivedChat.fromServer("Alex 加入了游戏", false).ifPresent(chat::append);
        ReceivedChat.fromPlayer("服务器十分钟后重启", null, "Server", false, false, null).ifPresent(chat::append);

        JsonObject reply = call("events", "{\"topic\": \"chat\"}");
        JsonArray read = data(reply).getAsJsonArray("events");

        // 玩家说的话带名字与 UUID，并写明发言人在不在玩家列表里；私聊才写 private；系统消息没有发言人。
        assertEquals(4, read.size());
        JsonObject said = read.get(0).getAsJsonObject();
        assertEquals("player", said.get("kind").getAsString());
        assertEquals("Steve", said.get("sender").getAsString());
        assertEquals(steve.toString(), said.get("sender_id").getAsString());
        assertTrue(said.get("sender_online").getAsBoolean());
        assertEquals("麦麦过来", said.get("text").getAsString());
        assertFalse(said.has("private"));
        assertTrue(read.get(1).getAsJsonObject().get("private").getAsBoolean());
        assertEquals("system", read.get(2).getAsJsonObject().get("kind").getAsString());
        assertFalse(read.get(2).getAsJsonObject().has("sender"));
        assertFalse(read.get(2).getAsJsonObject().has("sender_online"), "系统消息没有发言人，不写");
        // 控制台的 /say 借了个名字：照旧是玩家说的话，写明不是在线玩家。
        assertFalse(read.get(3).getAsJsonObject().get("sender_online").getAsBoolean());
        // 照抄 next 接着读的还是聊天；角色身上的事件流里没有聊天；读聊天不带 goal_id。
        assertEquals("chat", reply.getAsJsonObject("next").getAsJsonObject("arguments").get("topic").getAsString());
        assertEquals(0, data(call("events", "{}")).getAsJsonArray("events").size());
        assertEquals("invalid_parameter", errorCode(call("events", "{\"topic\": \"chat\", \"goal_id\": 1}")));
    }

    @Test
    void everyEventsPageSaysWhichWorldTheCharacterIsIn() {
        // 不在世界里、还没认出世界：world 照样在，是 null，宿主不用分"没带"和"没认出"。
        assertTrue(data(call("events", "{}")).get("world").isJsonNull());
        assertTrue(data(call("events", "{\"topic\": \"chat\"}")).get("world").isJsonNull());

        // 认出了世界：两条流的每一页都带同一个 world，编号、存档名与编号从哪来。
        world = new SaveIdentity("ab".repeat(32), SaveIdentity.Source.WORLD_ID_FILE, "新的世界", "cd".repeat(32),
                Path.of("state"));
        for (String arguments : List.of("{}", "{\"topic\": \"chat\"}")) {
            JsonObject shown = data(call("events", arguments)).getAsJsonObject("world");
            assertEquals("ab".repeat(32), shown.get("id").getAsString());
            assertEquals("新的世界", shown.get("name").getAsString());
            assertEquals("world_id_file", shown.get("source").getAsString());
            // 旧版本那串按目录算的编号只用来搬 Mod 自己的旧数据，不报给宿主。
            assertEquals(3, shown.size());
        }
    }

    @Test
    void aWaitingEventsReadReturnsAtOnceWhenTheWorldChanges() throws Exception {
        // 宿主挂着长轮询等事件：角色退回主菜单时被叫醒，当场带着 world=null 返回，不用等满 30 秒。
        world = new SaveIdentity("ab".repeat(32), SaveIdentity.Source.WORLD_ID_FILE, "新的世界", null, Path.of("state"));
        JsonObject first = data(call("events", "{}"));
        String arguments = "{\"stream_id\": \"" + first.get("stream_id").getAsString() + "\", \"after_cursor\": "
                + first.get("cursor").getAsLong() + ", \"wait_ms\": 60000}";
        CompletableFuture<JsonObject> waiting = CompletableFuture.supplyAsync(() -> call("events", arguments));
        world = null;
        // 读的一方什么时候挂上等不确定：每 100 毫秒叫一次，挂上之后的那一次会叫醒它；不叫醒它要等满 60 秒。
        JsonObject reply = null;
        for (int attempt = 0; attempt < 50 && reply == null; attempt++) {
            events.wakeReaders();
            try {
                reply = waiting.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillWaiting) {
                // 还在等：再叫一次。
            }
        }
        assertNotNull(reply, "世界变了，挂着等的读取没有被叫醒");
        JsonObject page = data(reply);
        assertEquals(0, page.getAsJsonArray("events").size());
        assertEquals("valid", page.get("cursor_status").getAsString());
        assertTrue(page.get("world").isJsonNull());
    }

    @Test
    void chatEventsSayWhoAMessageNamesAndWhoseNameItBorrowed() {
        UUID momo = UUID.fromString("5f2b9c1e-0d4a-4c1b-9e7f-1a2b3c4d5e6f");
        UUID creeper = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
        ReceivedChat.fromServer("momo 被 苦力怕 炸死了", false).map(death -> death.with(List.of(
                new MentionedEntity("momo", "touhou_little_maid:maid", momo),
                new MentionedEntity("苦力怕", "minecraft:creeper", creeper)), "death.attack.explosion.player"))
                .ifPresent(chat::append);
        ReceivedChat.underOwnName("<Mai> [name:\"winefox_momo\", x:12, y:64, z:-3]", "Mai").ifPresent(chat::append);

        JsonArray read = data(call("events", "{\"topic\": \"chat\"}")).getAsJsonArray("events");

        // 死亡播报：按出现先后给点到的实体（名字、类型、UUID）和翻译键。
        JsonObject death = read.get(0).getAsJsonObject();
        assertEquals("death.attack.explosion.player", death.get("translation_key").getAsString());
        JsonObject victim = death.getAsJsonArray("mentions").get(0).getAsJsonObject();
        assertEquals("momo", victim.get("name").getAsString());
        assertEquals("touhou_little_maid:maid", victim.get("type").getAsString());
        assertEquals(momo.toString(), victim.get("uuid").getAsString());
        assertEquals(creeper.toString(),
                death.getAsJsonArray("mentions").get(1).getAsJsonObject().get("uuid").getAsString());
        // 借角色名字代发的：系统消息、写明借的名字、没有发言人；没点到实体时不写 mentions。
        JsonObject borrowed = read.get(1).getAsJsonObject();
        assertEquals("system", borrowed.get("kind").getAsString());
        assertEquals("Mai", borrowed.get("shown_as").getAsString());
        assertEquals("[name:\"winefox_momo\", x:12, y:64, z:-3]", borrowed.get("text").getAsString());
        assertFalse(borrowed.has("sender") || borrowed.has("mentions") || borrowed.has("translation_key"));
    }

    @Test
    void outsideTheWorldToolsSaySoInsteadOfFailingBlindly() {
        inWorld = false;

        assertEquals("not_in_world", errorCode(call("execute", "{\"goal\": {\"ability\": \"use\"}}")));
        assertTrue(data(call("lookup", "{}")).has("abilities"));
    }
}
