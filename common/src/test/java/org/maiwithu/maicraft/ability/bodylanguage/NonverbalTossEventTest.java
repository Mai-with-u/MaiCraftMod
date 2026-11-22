// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.drops.ItemsTossedNotice;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.EventFacts;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 朝玩家扔出物品的事实事件测试：确认扔出数量大于 0 时发 items_tossed 且字段与消息精确，
 * 仅未确认、投掷失败或朝非玩家实体扔出时不发事件，中途取消或卡住超时仍精准按已确认件数补发一次。
 */
class NonverbalTossEventTest {

    private static final UUID PLAYER_A_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID VILLAGER_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final NonverbalMoves.Partner PLAYER_A = new NonverbalMoves.Partner(11, PLAYER_A_UUID, "玩家甲", true);
    private static final NonverbalMoves.Partner VILLAGER = new NonverbalMoves.Partner(12, VILLAGER_UUID, "村民", false);
    private static final WorldPosition SPOT = new WorldPosition(12, 64, -8, "minecraft:overworld");

    @Test
    void 确认朝玩家扔出物品时发items_tossed事件且消息与载荷精确() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeNonverbalMoves moves = prepareMoves(PLAYER_A, SPOT, stubToss(3, 0, ActionStatus.done()));

        TaskResult result = runToss(moves, log, "minecraft:diamond", 3, new Target.Player("玩家甲"));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(1, events.size());

        JsonObject json = ResultJson.event(events.getFirst());
        assertEquals("items_tossed", json.get("kind").getAsString());
        assertEquals(88L, json.get("goal_id").getAsLong());
        assertEquals("maicraft:body_language", json.get("ability").getAsString());
        assertEquals("朝 玩家甲 扔出了 3 个 minecraft:diamond", json.get("message").getAsString());

        JsonObject facts = json.getAsJsonObject("items_tossed");
        assertEquals("玩家甲", facts.get("to").getAsString());
        assertEquals(PLAYER_A_UUID.toString(), facts.get("to_uuid").getAsString());
        JsonObject at = facts.getAsJsonObject("at");
        assertEquals(12, at.get("x").getAsInt());
        assertEquals(64, at.get("y").getAsInt());
        assertEquals(-8, at.get("z").getAsInt());
        assertEquals("minecraft:overworld", at.get("dimension").getAsString());
        JsonArray items = facts.getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals("minecraft:diamond", items.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(3, items.get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void 向非玩家实体扔出确认成功时不发items_tossed事件() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeNonverbalMoves moves = prepareMoves(VILLAGER, SPOT, stubToss(2, 0, ActionStatus.done()));

        TaskResult result = runToss(moves, log, "minecraft:bread", 2, new Target.Seen("e12"));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, result.changes().size(), "朝非玩家实体扔出仍应照常记下物品丢弃变化");
        assertEquals(Change.Kind.ITEM_DROPPED, result.changes().getFirst().kind());
        assertTrue(log.read(null, 0, OptionalLong.empty(), 10, 0).events().isEmpty(),
                "朝非玩家实体扔出绝不能发出面向玩家的 items_tossed 交付事件");
    }

    @Test
    void 仅未确认或扔出数量为零时不发items_tossed事件() throws InterruptedException {
        TaskEventLog unconfirmedLog = new TaskEventLog();
        FakeNonverbalMoves unconfirmedMoves = prepareMoves(PLAYER_A, SPOT,
                stubToss(0, 2, ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "投掷没等到确认", null))));

        TaskResult unconfirmedResult = runToss(unconfirmedMoves, unconfirmedLog, "minecraft:iron_ingot", 2,
                new Target.Player("玩家甲"));
        assertEquals(TaskResult.Status.FAILED, unconfirmedResult.status());
        assertEquals(1, unconfirmedResult.unconfirmed().size());
        assertTrue(unconfirmedLog.read(null, 0, OptionalLong.empty(), 10, 0).events().isEmpty(),
                "未确认的投掷绝不能冒充已扔出发 items_tossed");

        TaskEventLog refusedLog = new TaskEventLog();
        FakeNonverbalMoves refusedMoves = prepareMoves(PLAYER_A, SPOT,
                stubToss(0, 0, ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "游戏没有接受投掷", null))));

        TaskResult refusedResult = runToss(refusedMoves, refusedLog, "minecraft:iron_ingot", 1,
                new Target.Player("玩家甲"));
        assertEquals(TaskResult.Status.FAILED, refusedResult.status());
        assertTrue(refusedLog.read(null, 0, OptionalLong.empty(), 10, 0).events().isEmpty(),
                "确认扔出数量为 0 时不发 items_tossed");
    }

    @Test
    void 部分确认且部分未确认失败时只按确认数量发items_tossed事件() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeNonverbalMoves moves = prepareMoves(PLAYER_A, SPOT,
                stubToss(2, 1, ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "第三件没等到确认", null))));

        TaskResult result = runToss(moves, log, "minecraft:emerald", 3, new Target.Player("玩家甲"));

        assertEquals(TaskResult.Status.FAILED, result.status());
        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(1, events.size());
        JsonObject json = ResultJson.event(events.getFirst());
        assertEquals("朝 玩家甲 扔出了 2 个 minecraft:emerald", json.get("message").getAsString());
        JsonArray items = json.getAsJsonObject("items_tossed").getAsJsonArray("items");
        assertEquals(2, items.get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void 扔出时坐标为空仍发items_tossed事件且序列化省略at字段() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeNonverbalMoves moves = prepareMoves(PLAYER_A, null, stubToss(1, 0, ActionStatus.done()));

        TaskResult result = runToss(moves, log, "minecraft:bread", 1, new Target.Player("玩家甲"));

        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(1, events.size());
        JsonObject facts = ResultJson.event(events.getFirst()).getAsJsonObject("items_tossed");
        assertFalse(facts.has("at"), "位置读不到时省略 at 字段");
        assertEquals("玩家甲", facts.get("to").getAsString());
        assertEquals(PLAYER_A_UUID.toString(), facts.get("to_uuid").getAsString());
        assertEquals("minecraft:bread", facts.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, facts.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void 多件投掷已确认部分件数后中途取消仍精准补发一次items_tossed且不漏记变化() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        // 模拟多件投掷：已确认扔出 2 件、第 3 件还在进行中时被外部取消。
        FakeNonverbalMoves moves = prepareMoves(PLAYER_A, SPOT, stubToss(2, 0, ActionStatus.running()));
        NonverbalTask task = createTask(moves, log, "minecraft:diamond", 5, new Target.Player("玩家甲"));

        task.start(tick(0));
        // FIND → TAKE_OUT → DO，推至 DO 阶段至少执行一刻记录最近坐标与已确认件数。
        for (int i = 1; i <= 5; i++) {
            assertEquals(TickResult.RUNNING, task.tick(tick(i)));
        }
        TaskResult cancelled = task.close(CloseReason.CANCELLED);
        // 二次 close 也绝不重复发事件。
        task.close(CloseReason.CANCELLED);

        assertEquals(TaskResult.Status.CANCELLED, cancelled.status());
        assertEquals(1, cancelled.changes().size(), "取消结果里不能漏掉已经确认扔出的物品变化");
        assertEquals(Change.Kind.ITEM_DROPPED, cancelled.changes().getFirst().kind());
        assertEquals(2, cancelled.changes().getFirst().count());

        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(1, events.size(), "中途取消时对已确认部分应补发且仅补发 1 次 items_tossed");
        JsonObject json = ResultJson.event(events.getFirst());
        assertEquals("朝 玩家甲 扔出了 2 个 minecraft:diamond", json.get("message").getAsString());
        JsonObject facts = json.getAsJsonObject("items_tossed");
        assertEquals(12, facts.getAsJsonObject("at").get("x").getAsInt());
        assertEquals(2, facts.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void 多件投掷尚未确认任何件数就中途取消不发items_tossed事件() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeNonverbalMoves moves = prepareMoves(PLAYER_A, SPOT, stubToss(0, 1, ActionStatus.running()));
        NonverbalTask task = createTask(moves, log, "minecraft:diamond", 3, new Target.Player("玩家甲"));

        task.start(tick(0));
        for (int i = 1; i <= 5; i++) {
            assertEquals(TickResult.RUNNING, task.tick(tick(i)));
        }
        TaskResult cancelled = task.close(CloseReason.CANCELLED);

        assertEquals(TaskResult.Status.CANCELLED, cancelled.status());
        assertEquals(1, cancelled.unconfirmed().size());
        assertTrue(log.read(null, 0, OptionalLong.empty(), 10, 0).events().isEmpty(),
                "已确认数量为 0 时中途取消绝不发 items_tossed");
    }

    @Test
    void 多件投掷已确认部分件数后进度卡住超时仍精准补发一次items_tossed且不漏记变化() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        FakeNonverbalMoves moves = prepareMoves(PLAYER_A, SPOT, stubToss(2, 0, ActionStatus.running()));
        NonverbalTask task = createTask(moves, log, "minecraft:gold_ingot", 4, new Target.Player("玩家甲"));

        task.start(tick(0));
        TaskResult finished = null;
        for (int i = 1; i <= 150; i++) {
            if (task.tick(tick(i)) instanceof TickResult.Finished done) {
                finished = done.result();
                break;
            }
        }
        assertTrue(finished != null, "长时间无新进展应在进度窗口内按卡住收场");
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(1, finished.changes().size(), "进度卡住收场结果里不能漏掉已确认扔出的变化");
        assertEquals(2, finished.changes().getFirst().count());

        List<TaskEvent> events = log.read(null, 0, OptionalLong.empty(), 10, 0).events();
        assertEquals(1, events.size(), "进度卡住收场时对已确认部分应补发且仅补发 1 次 items_tossed");
        JsonObject json = ResultJson.event(events.getFirst());
        assertEquals("朝 玩家甲 扔出了 2 个 minecraft:gold_ingot", json.get("message").getAsString());
        assertEquals(2, json.getAsJsonObject("items_tossed").getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
    }

    private static FakeNonverbalMoves prepareMoves(NonverbalMoves.Partner partner, WorldPosition position,
            NonverbalMoves.Toss toss) {
        FakeNonverbalMoves moves = new FakeNonverbalMoves();
        moves.currentPartner = partner;
        moves.currentPosition = position;
        moves.takeOutAction = new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.done();
            }

            @Override public String describe() {
                return "换到主手";
            }
        };
        moves.tossFactory = (itemId, count) -> toss;
        return moves;
    }

    private static NonverbalMoves.Toss stubToss(int thrown, int unconfirmed, ActionStatus ending) {
        return new NonverbalMoves.Toss() {
            @Override public int thrown() {
                return thrown;
            }

            @Override public int unconfirmed() {
                return unconfirmed;
            }

            @Override public ActionStatus tick(TickContext context) {
                return ending;
            }

            @Override public String describe() {
                return "替身扔出";
            }
        };
    }

    private static NonverbalTask createTask(FakeNonverbalMoves moves, TaskEventLog log, String itemId, int count,
            Target target) {
        TaskEventSink sink = new TaskEventSink() {
            @Override public void publish(TaskEvent.Kind kind, String message) {
                log.publish(kind, message);
            }

            @Override public void publishInAction(TaskEvent.Kind kind, String message, EventFacts facts) {
                log.appendForGoal(kind, 88L, "maicraft:body_language", message, facts);
            }
        };
        NonverbalInput input = new NonverbalInput(NonverbalOperation.TOSS, count, itemId, 40, target);
        return new NonverbalTask(input, moves, new PokesGiven(), new ItemsTossedNotice(sink));
    }

    private static TaskResult runToss(FakeNonverbalMoves moves, TaskEventLog log, String itemId, int count,
            Target target) {
        NonverbalTask task = createTask(moves, log, itemId, count, target);
        task.start(tick(0));
        for (int i = 1; i < 40; i++) {
            if (task.tick(tick(i)) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("40 刻还没收场：" + task.describe());
    }

    private static TickContext tick(long now) {
        return new TickContext() {
            @Override public long gameTick() {
                return now;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }
}
