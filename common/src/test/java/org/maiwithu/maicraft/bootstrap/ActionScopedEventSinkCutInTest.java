// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.drops.ItemsTossedNotice;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilityRegistry;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.event.EventPublishingGoalRunStore;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventLog;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.goal.InMemoryGoalRunStore;
import org.maiwithu.maicraft.kernel.goal.PlayerControlHandover;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoop;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 插一下（CUTS_IN）模式下的 items_tossed 目标归属集成测试：
 * 主目标正在推进时插一下扔出物品，经真实 GoalRunTable、ControlLoop 与 ActionScopedEventSink 发出的事件
 * 必须挂在插一下目标自身的 goal_id 与 ability 上，不串到被压住的主目标。
 */
class ActionScopedEventSinkCutInTest {

    private static final String PLAYER_A_UUID = "00000000-0000-0000-0000-00000000000a";
    private static final WorldPosition SPOT = new WorldPosition(6, 64, -3, "minecraft:overworld");

    private record MainInput() implements TaskInput {
        @Override public String describe() { return "主目标干活"; }
    }

    private record CutInTossInput() implements TaskInput {
        @Override public String describe() { return "插一下扔东西"; }
    }

    @Test
    void 主目标运行中插一下扔出物品时事件挂在插一下目标的goal_id与ability上() throws InterruptedException {
        TaskEventLog log = new TaskEventLog();
        AtomicReference<GoalRunTable> tableRef = new AtomicReference<>();
        TaskEventSink scopedSink = ActionScopedEventSink.of(log, tableRef::get);
        ItemsTossedNotice notice = new ItemsTossedNotice(scopedSink);

        TaskFactories factories = new TaskFactories();
        AbilityRegistry registry = new AbilityRegistry(factories);
        registry.register(mainAbility());
        registry.register(cutInTossAbility(notice));

        ControlLoop loop = new ControlLoop(List.of());
        GoalRunTable table = new GoalRunTable(registry,
                new EventPublishingGoalRunStore(new InMemoryGoalRunStore(), log),
                (name, position) -> { }, loop, new PlayerControlHandover() {
                    @Override public boolean automationOwnsControls() { return true; }
                    @Override public void requestControl() { }
                });
        tableRef.set(table);

        // 先下达持续运行的主目标并推进一步，使其处于 RUNNING。
        long mainGoalId = table.launch(Goal.of("maicraft:test_main", new Target.Here(), ParamValues.EMPTY), null)
                .runner().run().id();
        loop.tick(tick(1));

        // 再下达 CUTS_IN 插一下目标，压在主目标之上推进并触发 ItemsTossedNotice。
        long cutInGoalId = table.launch(Goal.of("maicraft:body_language", new Target.Player("玩家甲"), ParamValues.EMPTY), null)
                .runner().run().id();
        assertNotEquals(mainGoalId, cutInGoalId);
        for (int t = 2; t <= 6; t++) {
            loop.tick(tick(t));
        }

        List<TaskEvent> tossedEvents = log.read(null, 0, OptionalLong.empty(), 20, 0).events().stream()
                .filter(event -> event.kind() == TaskEvent.Kind.ITEMS_TOSSED)
                .toList();
        assertEquals(1, tossedEvents.size(), "插一下扔出确认后应发出 1 条 items_tossed 事件");

        JsonObject json = ResultJson.event(tossedEvents.getFirst());
        assertEquals("items_tossed", json.get("kind").getAsString());
        assertEquals(cutInGoalId, json.get("goal_id").getAsLong(), "应挂在插一下目标自身 goal_id，不能串到主目标");
        assertEquals("maicraft:body_language", json.get("ability").getAsString());
        assertEquals("朝 玩家甲 扔出了 3 个 minecraft:diamond", json.get("message").getAsString());
        JsonObject facts = json.getAsJsonObject("items_tossed");
        assertEquals("玩家甲", facts.get("to").getAsString());
        assertEquals(PLAYER_A_UUID, facts.get("to_uuid").getAsString());
        assertTrue(facts.has("at"));
        assertEquals(3, facts.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
    }

    private static AbilityModule mainAbility() {
        return new AbilityModule() {
            @Override public AbilitySpec spec() {
                return new AbilitySpec("maicraft:test_main", "测试主目标", AbilityDoc.forAbility("body_language"),
                        ParamSpecs.EMPTY, Set.of(TargetKind.HERE), ExecutionMode.CONTROLS_PLAYER,
                        Set.of(), List.of(), Listing.HIDDEN);
            }

            @Override public StepDecision decide(StepContext step) {
                return new StepDecision.Run(new MainInput());
            }

            @Override public void registerTasks(TaskFactories factories) {
                factories.register(MainInput.class, input -> new Task() {
                    @Override public void start(TickContext context) { }
                    @Override public TickResult tick(TickContext context) { return TickResult.RUNNING; }
                    @Override public void pause() { }
                    @Override public TaskResult close(CloseReason reason) { return TaskResult.cancelled("主目标停手"); }
                    @Override public String describe() { return "主目标干活"; }
                });
            }
        };
    }

    private static AbilityModule cutInTossAbility(ItemsTossedNotice notice) {
        return new AbilityModule() {
            @Override public AbilitySpec spec() {
                return new AbilitySpec("maicraft:body_language", "插一下扔东西测试", AbilityDoc.forAbility("body_language"),
                        ParamSpecs.EMPTY, Set.of(TargetKind.PLAYER), ExecutionMode.CUTS_IN,
                        Set.of(), List.of(), Listing.HIDDEN);
            }

            @Override public StepDecision decide(StepContext step) {
                return new StepDecision.Run(new CutInTossInput());
            }

            @Override public void registerTasks(TaskFactories factories) {
                factories.register(CutInTossInput.class, input -> new Task() {
                    @Override public void start(TickContext context) { }

                    @Override public TickResult tick(TickContext context) {
                        notice.tossed("玩家甲", PLAYER_A_UUID, SPOT, "minecraft:diamond", 3);
                        return TickResult.finished(TaskResult.done("对着 玩家甲：扔出 3 件 minecraft:diamond"));
                    }

                    @Override public void pause() { }
                    @Override public TaskResult close(CloseReason reason) { return TaskResult.cancelled("插一下停手"); }
                    @Override public String describe() { return "插一下扔东西"; }
                });
            }
        };
    }

    private static TickContext tick(long now) {
        return new TickContext() {
            @Override public long gameTick() { return now; }
            @Override public PlayerContext player() { return null; }
        };
    }
}
