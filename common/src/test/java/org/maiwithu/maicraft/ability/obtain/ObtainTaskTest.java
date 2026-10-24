// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonArray;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.engine.StartsAcquisition;
import org.maiwithu.maicraft.behavior.acquire.engine.StepwiseActions;
import org.maiwithu.maicraft.behavior.acquire.engine.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * 拿东西的任务：count 是再多拿几件，身上原有的不算；引擎做完按实际入包结算，拿到一部分是 partial 加还差几件；
 * 实际拿到东西的途径进结果细节。引擎与背包都是替身，不碰游戏。
 */
class ObtainTaskTest {

    /** 替身：背包就是一张可以改的格子表。 */
    static final class FakeBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }

        void add(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
        }
    }

    /** 替身：发起拿东西时记下请求与限定，推进时按剧本收场。 */
    static final class ScriptedAcquisition implements StartsAcquisition {
        ItemRequest lastRequest;
        ItemAcquisition.Scope lastScope;
        Consumer<String> lastDelivered;
        /** 发起任务交给引擎的记账口：测试用它模拟来源点出去没等到结果的那几下。 */
        TaskRecords lastRecords;
        /** 每推进一步做的事：往背包放几件货、报哪条途径；null 表示以问题失败。 */
        Integer deliverPerTick;
        String deliveredVia;
        Problem failure;
        /** 设了就一直推进不收场：对应来源动作卡在半路（走去箱子的路长期无进展）。 */
        boolean neverFinishes;
        /** triedSoFar 交回的试过清单；null 表示不接。 */
        String tried;

        @Override public Action need(ItemRequest request, Permissions permissions,
                ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records) {
            lastRequest = request;
            lastScope = scope;
            lastDelivered = onDelivered;
            lastRecords = records;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    // 先把这次动作能拿到的东西放进背包（真实引擎里来源做完才重新清点），
                    // 剧本设了失败就接着以问题收场——对应"做了一个来源之后还是不够"。
                    if (deliverPerTick != null) {
                        ((FakeBackpack) backpack).add(deliveredItem(), deliverPerTick);
                        if (lastDelivered != null) {
                            lastDelivered.accept(deliveredVia);
                        }
                    }
                    if (neverFinishes) {
                        return ActionStatus.running();
                    }
                    return failure != null ? ActionStatus.failed(failure) : ActionStatus.done();
                }

                @Override public String describe() {
                    return "按剧本拿货";
                }
            };
        }

        @Override public String triedSoFar() {
            return tried == null ? StartsAcquisition.super.triedSoFar() : tried;
        }

        @Override public Action needSeveral(List<ItemRequest> requests, Permissions permissions,
                ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records) {
            lastRequests = requests;
            return need(requests.getFirst(), permissions, scope, onDelivered, records);
        }

        /** 一次要几样时给的整张单子。 */
        List<ItemRequest> lastRequests;

        BackpackView backpack;
        String deliveredItem = "minecraft:torch";
        String deliveredItem() {
            return deliveredItem;
        }
    }

    private static TickContext tick(long gameTick) {
        return new TickContext() {
            @Override public long gameTick() {
                return gameTick;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    private static ObtainItems input(int count, ItemAcquisition.Scope scope) {
        return new ObtainItems(new WantedItem("minecraft:torch"), count, scope,
                Permissions.DEFAULT, "按需要拿取", "再拿 " + count + " 个 minecraft:torch");
    }

    @Test
    void 身上原有的不算_还是再去拿这么多() {
        // 身上已有 8 个火把，再要 8 个：照样去拿，拿到的 8 个才是这次的。
        FakeBackpack backpack = new FakeBackpack();
        backpack.add("minecraft:torch", 8);
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.deliverPerTick = 8;
        acquisition.deliveredVia = "craft";
        ObtainTask task = new ObtainTask(input(8, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.DONE, finished.status());
        assertEquals(8, acquisition.lastRequest.count());
        assertEquals(8, finished.changes().get(0).count());
    }

    @Test
    void 引擎拿到货结算完成并记途径() {
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.deliverPerTick = 8;
        acquisition.deliveredVia = "craft";
        ObtainTask task = new ObtainTask(input(8, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.DONE, finished.status());
        assertEquals(1, finished.changes().size());
        Change gained = finished.changes().get(0);
        assertEquals(Change.Kind.ITEM_GAINED, gained.kind());
        assertEquals("minecraft:torch", gained.what());
        assertEquals(8, gained.count());
        ObtainedVia via = (ObtainedVia) finished.details();
        assertEquals(List.of("craft"), via.obtainedVia());
        // LLM 看到的键要和能力说明里写的 details.obtained_via 对得上。
        JsonArray obtainedVia = ResultJson.result(finished).getAsJsonObject("details").getAsJsonArray("obtained_via");
        assertEquals("craft", obtainedVia.get(0).getAsString());
    }

    @Test
    void 引擎认输时拿到一部分是partial() {
        FakeBackpack backpack = new FakeBackpack();
        backpack.add("minecraft:torch", 3);
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.deliverPerTick = 2;
        acquisition.deliveredVia = "container";
        acquisition.failure = Problem.of(Problem.Kind.NEED_ITEM, "附近没有会掉出火把的方块了");
        ObtainTask task = new ObtainTask(input(8, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.PARTIAL, finished.status());
        assertEquals(Problem.Kind.NEED_ITEM, finished.problem().kind());
        assertEquals(List.of("还差 6 个" + new WantedItem("minecraft:torch").describe()),
                finished.remaining());
        assertEquals(2, finished.changes().get(0).count());
    }

    @Test
    void 引擎认输且一件没拿到是失败() {
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.failure = Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到");
        ObtainTask task = new ObtainTask(input(4, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(0, finished.changes().size());
    }

    @Test
    void 来源没能确认的交互_一句一条写进结果的unconfirmed() {
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.failure = Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到");
        ObtainTask task = new ObtainTask(input(4, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        // 第一刻清点完走进 ACQUIRE，引擎动作才建出来，交回的口子跟着就位。
        task.tick(tick(1));
        // 动作在跑的时候引擎交回一句没能确认的取货：拿 64 件的那几下点出去了，游戏一直没回音。
        acquisition.lastRecords.unconfirmed(new Change(Change.Kind.OTHER, "取货", 1,
                "从家门口的箱子拿 minecraft:torch：搬 64 件的那几下都点出去了，没能确认结果"));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(1, finished.unconfirmed().size(), "没拿到货也不吞掉没能确认的事实");
        Change unconfirmed = finished.unconfirmed().get(0);
        assertEquals(Change.Kind.OTHER, unconfirmed.kind());
        assertEquals("取货", unconfirmed.what());
        assertEquals("从家门口的箱子拿 minecraft:torch：搬 64 件的那几下都点出去了，没能确认结果",
                unconfirmed.note());
    }

    @Test
    void via与半径原样交给引擎() {
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.deliverPerTick = 1;
        acquisition.deliveredVia = "mine";
        ItemAcquisition.Scope scope = new ItemAcquisition.Scope(Set.of("mine"), 30.0, 12);
        ObtainTask task = new ObtainTask(input(1, scope), acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        runTask(task);
        assertEquals(Set.of("mine"), acquisition.lastScope.vias());
        assertEquals(Double.valueOf(30), acquisition.lastScope.maxDistanceBlocks());
        assertEquals(Integer.valueOf(12), acquisition.lastScope.radiusBlocks());
        assertEquals(1, acquisition.lastRequest.count());
    }

    /** 测试用的一条要等的途径：下单后站着等成品。 */
    private static final AcquireVia WAITING_VIA = new AcquireVia("test_wait", "测试：下单后站着等成品");

    @Test
    void 来源此刻能不能打断_经取物引擎一路交给拿东西任务() {
        // 来源的动作是一串：点下单等游戏确认（停不得）→ 站着等成品（两件事之间的空当，饿了能吃一口）→ 成品进背包。
        // 拿东西任务对内核报的要跟着这一串走，不是一律报正常干活。
        FakeBackpack backpack = new FakeBackpack();
        Action clicking = new Action() {
            private boolean sent;

            @Override public ActionStatus tick(TickContext context) {
                if (sent) return ActionStatus.done();
                sent = true;
                return ActionStatus.running();
            }

            @Override public Interruptibility interruptibility() {
                return Interruptibility.UNSAFE_TO_STOP;
            }

            @Override public String describe() {
                return "点下单，等游戏确认";
            }
        };
        Action waiting = new Action() {
            private int waited;

            @Override public ActionStatus tick(TickContext context) {
                if (++waited < 3) return ActionStatus.running();
                backpack.add("minecraft:torch", 4);
                return ActionStatus.done();
            }

            @Override public Interruptibility interruptibility() {
                return Interruptibility.BETWEEN_ACTIONS;
            }

            @Override public String describe() {
                return "站着等成品";
            }
        };
        ItemSource ordering = new ItemSource() {
            @Override public String describe() {
                return "网络下单";
            }

            @Override public AcquireVia via() {
                return WAITING_VIA;
            }

            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer(describe(), request.count(), new AcquisitionCost(0, 3).withWait(60), null);
            }

            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.of(new StepwiseActions("下单后等成品", clicking, waiting));
            }
        };
        ItemAcquisition engine = new ItemAcquisition(List.of(ordering), backpack, null, itemId -> Set.of(),
                () -> WorldPosition.here(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        ObtainTask task = new ObtainTask(input(4, ItemAcquisition.Scope.ALL), engine, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        List<Interruptibility> reported = new ArrayList<>();
        TaskResult finished = null;
        for (int i = 1; i <= 20 && finished == null; i++) {
            if (task.tick(tick(i)) instanceof TickResult.Finished done) {
                finished = done.result();
            } else {
                Interruptibility now = task.interruptibility(tick(i));
                if (reported.isEmpty() || reported.getLast() != now) reported.add(now);
            }
        }
        assertEquals(TaskResult.Status.DONE, finished.status());
        assertEquals(List.of(Interruptibility.BETWEEN_ACTIONS, Interruptibility.UNSAFE_TO_STOP,
                Interruptibility.BETWEEN_ACTIONS), reported, "问价时是空当，点下单时停不得，站着等成品时又是空当");
    }

    @Test
    void 一次要几样_逐样写拿到几件与还差几件() {
        FakeBackpack backpack = new FakeBackpack();
        StartsAcquisition acquisition = new StartsAcquisition() {
            @Override public Action need(ItemRequest request, Permissions permissions,
                    ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records) {
                throw new IllegalStateException("一次要几样该走 needSeveral");
            }

            @Override public Action needSeveral(List<ItemRequest> requests, Permissions permissions,
                    ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records) {
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        backpack.add("minecraft:torch", 8);
                        backpack.add("minecraft:coal", 3);
                        return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "煤不够"));
                    }

                    @Override public String describe() {
                        return "一次拿两样";
                    }
                };
            }
        };
        ItemRequest torch = new ItemRequest(WantedItem.ofItem("minecraft:torch"), 8, "照明");
        ItemRequest coal = new ItemRequest(WantedItem.ofItem("minecraft:coal"), 4, "烧炼");
        ObtainItems input = new ObtainItems(List.of(torch, coal), ItemAcquisition.Scope.ALL,
                Permissions.DEFAULT, "按需要拿取", "再拿 8 个火把、4 个煤");
        ObtainTask task = new ObtainTask(input, acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);

        assertEquals(TaskResult.Status.PARTIAL, finished.status());
        assertEquals(2, finished.changes().size(), "逐样记 item_gained");
        assertEquals(List.of("还差 1 个" + WantedItem.ofItem("minecraft:coal").describe()), finished.remaining());
        assertTrue(finished.summary().contains("8 个" + WantedItem.ofItem("minecraft:torch").describe()),
                finished.summary());
        assertTrue(finished.summary().contains("3 个" + WantedItem.ofItem("minecraft:coal").describe()),
                finished.summary());
    }

    private TaskResult runTask(ObtainTask task) {
        TickResult result = TickResult.RUNNING;
        for (int i = 1; i <= 100; i++) {
            result = task.tick(tick(i));
            if (result instanceof TickResult.Finished finished) {
                task.close(CloseReasonForTest.REASON);
                return finished.result();
            }
        }
        throw new AssertionError("任务一百刻都没跑完");
    }

    @Test
    void 卡住的收场说明是摊平的试过清单_不倒逐层嵌套的进行经过() {
        // 来源动作卡在半路（走去记忆箱子的路长期无进展）：任务到点判卡住。
        // 收场说明只给一句结论，加上引擎摊平的"试过哪些做法、各为什么不成"；
        // 进行中那句一层套一层（第几步、第几种做法）不再整串塞进去——实机里嵌了十一层。
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.neverFinishes = true;
        acquisition.tried = "试过记得的箱子（做完了，但一件都没拿到）；自己做：原料没备齐（缺bamboo×1），一份都做不了";
        ObtainTask task = new ObtainTask(input(1, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TickResult result = TickResult.RUNNING;
        for (int i = 1; i <= ObtainTask_STUCK_TICKS + 10; i++) {
            result = task.tick(tick(i));
            if (result instanceof TickResult.Finished finished) {
                Problem problem = finished.result().problem();
                assertEquals(Problem.Kind.STUCK, problem.kind());
                String message = problem.message();
                assertTrue(message.contains("试过记得的箱子"), "摊平的试过清单要在说明里：" + message);
                assertFalse(message.contains("按剧本拿货"), "进行中的嵌套经过不该整串塞进收场说明：" + message);
                assertTrue(message.length() < 800, "收场说明有上界，实机那句嵌套的长说明不许回来：" + message);
                return;
            }
        }
        throw new AssertionError("过了卡住的刻数还没收场");
    }

    /** 拿东西任务判卡住的刻数，与任务的进度跟踪同一笔账。 */
    private static final long ObtainTask_STUCK_TICKS = 20L * 60;

    @Test
    void 卡在问价阶段_收场说明只有一句结论() {
        // 一场都还没试（问价就没问出来）：没有试过清单就不硬凑，说明短而准。
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.neverFinishes = true;
        ObtainTask task = new ObtainTask(input(1, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TickResult result = TickResult.RUNNING;
        for (int i = 1; i <= ObtainTask_STUCK_TICKS + 10; i++) {
            result = task.tick(tick(i));
            if (result instanceof TickResult.Finished finished) {
                assertEquals(Problem.Kind.STUCK, finished.result().problem().kind());
                assertTrue(finished.result().problem().message().length() < 100,
                        "没有试过清单时说明就是一句结论：" + finished.result().problem().message());
                return;
            }
        }
        throw new AssertionError("过了卡住的刻数还没收场");
    }

    /** 收尾理由占位：测试只关心 tick 出的结果。 */
    private static final class CloseReasonForTest {
        static final CloseReason REASON =
                CloseReason.FINISHED;
    }
}
