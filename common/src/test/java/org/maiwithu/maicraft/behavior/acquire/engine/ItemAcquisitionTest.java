// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CollectedRecords;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.ScriptedSource;
import org.maiwithu.maicraft.behavior.acquire.FixedSpot;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;

/**
 * 拿到物品的引擎：身上够了直接算完；缺了问遍来源挑最省的路，用一个来源就重新清点；
 * 做砸或白跑的来源不再回头；谁也给不了就按卡住的原因挑问题种类；
 * 背包装不下先腾，要动贵重品就交许可问题。
 */
class ItemAcquisitionTest {

    private static final Permissions PERMISSIONS = Permissions.DEFAULT;

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final FakeTags tags = new FakeTags();

    private ItemAcquisition engine(ItemSource... sources) {
        return new ItemAcquisition(List.of(sources), backpack, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
    }

    /** 一刻一刻推进，直到动作定局，返回终态。 */
    private ActionStatus runToSettlement(Action action) {
        for (int tick = 1; tick <= 200; tick++) {
            ActionStatus status = action.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("两百刻还没定局，引擎在原地打转");
    }

    @Test
    void 来源动手那一刻_正在拿的这一串看得见_做完就空了() {
        // 路上垫脚要知道正在拿什么：来源动作里走路时，拿东西的这一串都在链上。
        List<List<ItemRequest>> seen = new ArrayList<>();
        ItemAcquisition[] engine = new ItemAcquisition[1];
        ItemSource peeking = new ItemSource() {
            @Override public String describe() {
                return "挖";
            }
            @Override public AcquireVia via() {
                return AcquireVia.MINE;
            }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("挖", request.count(), new AcquisitionCost(1, 1), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        seen.add(engine[0].inProgress());
                        backpack.add("minecraft:cobblestone", request.count());
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "挖圆石";
                    }
                });
            }
        };
        engine[0] = engine(peeking);
        ActionStatus status = runToSettlement(engine[0].need(
                new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), PERMISSIONS));

        assertEquals(ActionStatus.done(), status);
        assertEquals("minecraft:cobblestone", seen.getFirst().getFirst().wanted().itemId());
        assertTrue(engine[0].inProgress().isEmpty(), "做完就不在链上了");
    }

    @Test
    void 来源还在看现场_等它看完再问_不立刻说拿不到() {
        // 方块索引分刻建：头两问采掘说"还没扫完"，第三问才报价；引擎该等它，不把没扫完当成没有。
        ScriptedSource mine = new ScriptedSource("采掘", AcquireVia.MINE, backpack)
                .answer(new SourceQuote.NotYet("采掘", "附近的方块还没扫完"))
                .answer(new SourceQuote.NotYet("采掘", "附近的方块还没扫完"))
                .answer(new SourceQuote.Offer("采掘", 3, new AcquisitionCost(5, 3), null));
        ActionStatus status = runToSettlement(engine(mine).need(
                new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), PERMISSIONS));
        assertEquals(ActionStatus.done(), status);
        assertTrue(mine.askedTimes() >= 3, "等它扫完再问了几次：" + mine.askedTimes());
        assertEquals(3, countOf("minecraft:cobblestone"));
    }

    @Test
    void 要的是再多几件_身上原有的不算() {
        // 身上已有 3 根原木，再要 3 根：照样去问来源，拿完身上是 6 根。
        backpack.add("minecraft:oak_log", 3);
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 3, new AcquisitionCost(5, 4), null));
        ActionStatus status = runToSettlement(
                engine(chest).need(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 3, "施工备料"),
                        PERMISSIONS));
        assertEquals(ActionStatus.done(), status);
        assertEquals(1, chest.begunTimes());
        assertEquals(6, countOf("minecraft:oak_log"));
    }

    @Test
    void 东西先进了背包_来源还在收尾_等它收完再清点_途径照记() {
        // 从终端取货就是这样：最后几件进了背包，还要放好光标、关上界面才算做完。
        AcquireVia ae2 = new AcquireVia("ae2", "从附近的 ME 终端取");
        ScriptedSource terminal = new ScriptedSource("ME 终端", ae2, backpack)
                .answer(new SourceQuote.Offer("ME 终端", 8, new AcquisitionCost(6, 4), null))
                .onBegin(ScriptedSource.Ending.DELIVER_THEN_TIDY);
        List<String> obtainedVia = new ArrayList<>();
        ActionStatus status = runToSettlement(engine(terminal).need(
                new ItemRequest(WantedItem.ofItem("minecraft:torch"), 8, "照明"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, obtainedVia::add));
        assertEquals(ActionStatus.done(), status);
        assertTrue(terminal.tidied(), "数够了也先让来源把收尾做完");
        assertEquals(List.of("ae2"), obtainedVia);
        assertEquals(8, countOf("minecraft:torch"));
    }

    @Test
    void 东西进了背包后来源以问题收场_途径照记_不说成身上已有的() {
        AcquireVia ae2 = new AcquireVia("ae2", "从附近的 ME 终端取");
        ScriptedSource terminal = new ScriptedSource("ME 终端", ae2, backpack)
                .answer(new SourceQuote.Offer("ME 终端", 8, new AcquisitionCost(6, 4), null))
                .onBegin(ScriptedSource.Ending.DELIVER_THEN_FAIL);
        List<String> obtainedVia = new ArrayList<>();
        ActionStatus status = runToSettlement(engine(terminal).need(
                new ItemRequest(WantedItem.ofItem("minecraft:torch"), 8, "照明"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, obtainedVia::add));
        assertEquals(ActionStatus.done(), status);
        assertEquals(List.of("ae2"), obtainedVia);
        assertEquals(8, countOf("minecraft:torch"));
    }

    @Test
    void 缺的问来源_用最省的一条路_拿到后重新清点算完成() {
        ScriptedSource costly = new ScriptedSource("自己做", backpack)
                .answer(new SourceQuote.Offer("自己做", 10, new AcquisitionCost(80, 6), null));
        ScriptedSource cheap = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 4, new AcquisitionCost(10, 4), null));
        ActionStatus status = runToSettlement(
                engine(costly, cheap).need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 4, "烧炼的燃料"),
                        PERMISSIONS));
        assertEquals(ActionStatus.done(), status);
        assertEquals(4, countOf("minecraft:coal"));
        // 只用了便宜的一条：贵的那个从来没动手。
        assertEquals(0, costly.begunTimes());
    }

    @Test
    void 做砸的来源划掉_换下一个() {
        ScriptedSource broken = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 2, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.FAIL);
        ScriptedSource steady = new ScriptedSource("采掘", backpack)
                .answer(new SourceQuote.Offer("采掘", 3, new AcquisitionCost(20, 3), null));
        ActionStatus status = runToSettlement(
                engine(broken, steady).need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "烧炼的燃料"),
                        PERMISSIONS));
        assertEquals(ActionStatus.done(), status);
        assertEquals(3, countOf("minecraft:coal"));
    }

    @Test
    void 白跑一趟的来源不再回头_最后如实说缺什么() {
        ScriptedSource emptyChest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 5, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.DELIVER_NOTHING);
        ActionStatus status = runToSettlement(
                engine(emptyChest).need(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 2, "工具准备"),
                        PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().contains("记得的箱子"), "问题里要点名白跑的来源");
    }

    @Test
    void 来源点出去没能确认的交互_一句一条交回给发起的一方() {
        // 箱子那边点出去了、游戏一直没回音：货一件没进包，引擎照旧认输换路、以缺东西结束，
        // 点出去没确认的事实不再拼进失败原因，单独交回给发起拿东西的一方。
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 8, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.UNCONFIRMED);
        CollectedRecords reported = new CollectedRecords();
        ActionStatus status = runToSettlement(engine(chest).need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, null, reported));
        assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(List.of("记得的箱子里拿minecraft:coal：点出去了但没能确认结果"), reported.unconfirmedNotes());
        assertEquals("取货", reported.unconfirmed.getFirst().what());
    }

    @Test
    void 备料的嵌套请求_接住外层的记账口() {
        // 外层要木板，做木板的来源在推进时回引擎要原木（嵌套一次）：箱子里点原木没能确认的事实要记到最外层的发起方。
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 8, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.UNCONFIRMED);
        NestingSource planks = new NestingSource("minecraft:oak_planks",
                new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 1, "做木板的原木"));
        ItemAcquisition acquisition = engine(planks, new OnlyFor("minecraft:oak_log", chest));
        planks.engine = acquisition;
        CollectedRecords reported = new CollectedRecords();
        runToSettlement(acquisition.need(
                new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "施工备料"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, null, reported));
        assertTrue(reported.unconfirmedNotes().stream().anyMatch(fact -> fact.contains("minecraft:oak_log")
                && fact.contains("没能确认")), "嵌套里没能确认的事实记到了外层发起的一方");
    }

    @Test
    void 建出来没开始做的备料动作不占着链() {
        // 来源一口气建好一串备料动作，前面一步失败了，后面的从没推进过：它们不能留在链上，
        // 之后再要同一样东西照常去拿，不报"转了圈"。
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 8, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = engine(chest);
        acquisition.actionFor(new ItemRequest(WantedItem.ofItem("minecraft:crafting_table"), 1, "就地摆放的工作站"),
                PERMISSIONS);
        ActionStatus status = runToSettlement(acquisition.need(
                new ItemRequest(WantedItem.ofItem("minecraft:crafting_table"), 1, "开局"), PERMISSIONS));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertTrue(countOf("minecraft:crafting_table") >= 1, "照常拿到了工作台");
    }

    @Test
    void 来源动手时拿到任务的记账口_自制中间产物逐笔进结果_要的那件不重复记() {
        // 合成的现场动作自制了木板、木棍这类中间产物，也做出了点名要的东西：
        // 交给来源的记账口滤掉点名那件的入包（它由发起的任务按净拿到几件结算），其余原样进结果。
        CollectedRecords reported = new CollectedRecords();
        List<TaskRecords> handed = new ArrayList<>();
        ItemSource crafting = new ItemSource() {
            @Override public String describe() {
                return "自己做";
            }
            @Override public AcquireVia via() {
                return AcquireVia.CRAFT;
            }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("自己做", request.count(), new AcquisitionCost(0, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer,
                    SourceContext context) {
                handed.add(context.records());
                TaskRecords records = context.records();
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        if (records != null) {
                            records.change(Change.of(Change.Kind.ITEM_GAINED, "minecraft:stick", 4));
                            records.change(Change.of(Change.Kind.ITEM_GAINED, request.wanted().specifier(), 1));
                        }
                        backpack.add("minecraft:stick", 4);
                        backpack.add(request.wanted().specifier(), 1);
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "在工作台上做一次";
                    }
                });
            }
        };
        ActionStatus status = runToSettlement(engine(crafting).need(
                new ItemRequest(WantedItem.ofItem("minecraft:wooden_pickaxe"), 1, "开局"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, null, reported));
        assertEquals(ActionStatus.done(), status);
        assertNotNull(handed.getFirst(), "来源动手时拿到发起任务的记账口");
        assertEquals(List.of("minecraft:stick"), reported.changes.stream()
                        .filter(change -> change.kind() == Change.Kind.ITEM_GAINED)
                        .map(Change::what).toList(),
                "自制中间产物逐笔进了 changes，点名那件不在来源的账上");
    }

    @Test
    void 发起时没给记账口_来源拿到的是空_做了什么都不记() {
        List<TaskRecords> handed = new ArrayList<>();
        ItemSource crafting = new ItemSource() {
            @Override public String describe() {
                return "自己做";
            }
            @Override public AcquireVia via() {
                return AcquireVia.CRAFT;
            }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("自己做", request.count(), new AcquisitionCost(0, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer,
                    SourceContext context) {
                handed.add(context.records());
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        backpack.add(request.wanted().specifier(), 1);
                        return ActionStatus.done();
                    }
                    @Override public String describe() {
                        return "在工作台上做一次";
                    }
                });
            }
        };
        ActionStatus status = runToSettlement(engine(crafting).need(
                new ItemRequest(WantedItem.ofItem("minecraft:oak_planks"), 4, "施工备料"), PERMISSIONS,
                ItemAcquisition.Scope.ALL, what -> { }));
        assertEquals(ActionStatus.done(), status);
        assertNull(handed.getFirst(), "没接记账口的执行不记账");
    }

    @Test
    void 谁也给不了_问题里带上各家给不了的原因() {
        ScriptedSource none = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Unavailable("记得的箱子", "没装着这个"));
        ActionStatus status = runToSettlement(
                engine(none).need(new ItemRequest(WantedItem.ofItem("minecraft:diamond"), 1, "工具准备"),
                        PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(failed.problem().message().contains("没装着这个"));
    }

    @Test
    void 来源接不下这次执行_也记一笔_失败说明里不缺原因() {
        // 实机 10-16：做床失败只报"问过各来源都拿不到"，craft 来源接不下执行被划掉时没记任何经过，
        // 失败说明一条原因都没有，agent 不知道该修什么。
        ScriptedSource refusing = new ScriptedSource("自己做", backpack)
                .answer(new SourceQuote.Offer("自己做", 1,
                        new AcquisitionCost(0, 2, 0), "有配方",
                        "minecraft:magenta_bed"))
                .onBegin(ScriptedSource.Ending.UNAVAILABLE);
        ActionStatus status = runToSettlement(
                engine(refusing).need(new ItemRequest(WantedItem.ofItem("minecraft:magenta_bed"), 1, "睡觉"),
                        PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertTrue(failed.problem().message().contains("接不下这次执行"), failed.problem().message());
    }

    @Test
    void 全被许可挡下_交许可问题() {
        ScriptedSource guarded = new ScriptedSource("采掘", backpack)
                .answer(new SourceQuote.NeedsApproval("采掘", Problem.of(Problem.Kind.NEED_APPROVAL,
                        "(1, 64, 1) 是玩家放的，任何许可档位都不动它")));
        ActionStatus status = runToSettlement(
                engine(guarded).need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.NEED_APPROVAL, failed.problem().kind());
    }

    @Test
    void 背包只剩贵重品_如实失败带建议_不再征求同意() {
        FakeBackpack full = new FakeBackpack(1, new BackpackStack(
                "minecraft:diamond", 1, 64, false, false, true, false));
        InventorySpace space = new InventorySpace(full, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        ScriptedSource chest = new ScriptedSource("记得的箱子", full)
                .answer(new SourceQuote.Offer("记得的箱子", 5, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = new ItemAcquisition(List.of(chest), full, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        ActionStatus status = runToSettlement(
                acquisition.need(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"), PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        // 背包满如实失败：不再报 NEED_APPROVAL 问句——那是在为一个没有许可开关的动作征求同意；
        // 建议里给出处置用的能力名，取舍交调用方。
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(failed.problem().suggestion().contains("drop_items"), failed.problem().suggestion());
        assertTrue(failed.problem().suggestion().contains("manage_container"), failed.problem().suggestion());
    }

    @Test
    void 一次要几样_背包只剩贵重品_种类是背包腾不出() {
        // 腾背包如实失败：一次要几样的收场把种类照实传成 INVENTORY_FULL，不再有等着回答的问句。
        FakeBackpack full = new FakeBackpack(1, new BackpackStack(
                "minecraft:diamond", 1, 64, false, false, true, false));
        InventorySpace space = new InventorySpace(full, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        ScriptedSource chest = new ScriptedSource("记得的箱子", full)
                .answer(new SourceQuote.Offer("记得的箱子", 5, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = new ItemAcquisition(List.of(chest), full, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        ActionStatus status = runToSettlement(acquisition.needSeveral(
                List.of(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把")),
                PERMISSIONS, ItemAcquisition.Scope.ALL, null, null));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(!failed.problem().message().contains("可以吗"), "不再有征求同意的问句：" + failed.problem().message());
        assertTrue(failed.problem().suggestion().contains("drop_items"), failed.problem().suggestion());
    }

    @Test
    void 一次要几样_真腾不出_种类是背包腾不出() {
        // 背包塞满、能动的全动了也腾不出：按对外接口的约定如实报 INVENTORY_FULL，不再按缺东西算。
        FakeBackpack full = new FakeBackpack(1, new BackpackStack(
                "minecraft:coal", 64, 64, false, false, false, false));
        InventorySpace space = new InventorySpace(full, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        ScriptedSource chest = new ScriptedSource("记得的箱子", full)
                .answer(new SourceQuote.Offer("记得的箱子", 5, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = new ItemAcquisition(List.of(chest), full, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        ActionStatus status = runToSettlement(acquisition.needSeveral(
                List.of(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把")),
                PERMISSIONS, ItemAcquisition.Scope.ALL, null, null));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
    }

    @Test
    void 背包没空格但能并进半堆_不腾地方直接拿() {
        // 身上已有 30 块圆石、背包没空格：再要 5 块能并进这一堆，不该先腾格子（这里也腾不出来）。
        FakeBackpack full = new FakeBackpack(1, new BackpackStack(
                "minecraft:cobblestone", 30, 64, false, false, false, true));
        InventorySpace space = new InventorySpace(full, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        ScriptedSource chest = new ScriptedSource("记得的箱子", full)
                .answer(new SourceQuote.Offer("记得的箱子", 5, new AcquisitionCost(5, 4), null));
        ItemAcquisition acquisition = new ItemAcquisition(List.of(chest), full, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.of(space), ItemAcquisition.DEFAULT_MAX_DEPTH);
        ActionStatus status = runToSettlement(
                acquisition.need(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 5, "垫脚"), PERMISSIONS));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(1, chest.begunTimes());
    }

    @Test
    void 卡在半路时_试过的经过摊平一句一条() {
        // 来源做砸了划掉、换下一个还在磨蹭：引擎答得出这一场试过什么、各为什么不成，
        // 任务卡住收场时摊平讲一次，不倒逐层嵌套的进行经过。
        ScriptedSource chest = new ScriptedSource("记得的箱子", backpack)
                .answer(new SourceQuote.Offer("记得的箱子", 3, new AcquisitionCost(5, 4), null))
                .onBegin(ScriptedSource.Ending.DELIVER_NOTHING);
        List<String> seen = new ArrayList<>();
        ItemAcquisition[] engine = new ItemAcquisition[1];
        ItemSource slow = new ItemSource() {
            @Override public String describe() {
                return "慢慢找";
            }
            @Override public AcquireVia via() {
                return AcquireVia.MINE;
            }
            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("慢慢找", 3, new AcquisitionCost(9, 4), null);
            }
            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer,
                    SourceContext context) {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        // 磨蹭的那几刻正是"卡在半路"：这一场试过的经过此刻答得出（在拿东西的链上）。
                        seen.add(engine[0].triedSoFar());
                        return ActionStatus.running();
                    }
                    @Override public String describe() {
                        return "慢慢找";
                    }
                });
            }
        };
        engine[0] = engine(chest, slow);
        Action need = engine[0].need(
                new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), PERMISSIONS);
        for (int tick = 1; tick <= 30; tick++) {
            if (need.tick(new StubTick(tick)) instanceof ActionStatus.Failed) break;
        }
        assertTrue(seen.stream().anyMatch(tried -> tried.contains("记得的箱子")),
                "试过的来源要一句一条：" + seen);
        assertTrue(seen.stream().noneMatch(tried -> tried.contains("（第 ")),
                "经过不带嵌套括号：" + seen);
    }

    @Test
    void 备料转圈当场拒绝() {
        // 来源推进时又回到引擎要同样的东西：引擎认出转圈，嵌套的那一下当场失败。
        NestingSource iron = new NestingSource("minecraft:iron_ingot",
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"));
        ItemAcquisition acquisition = engine(iron);
        iron.engine = acquisition;
        runToSettlement(acquisition.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 1, "工具准备"), PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, iron.nestedEnding);
        assertTrue(failed.problem().message().contains("转了圈"));
    }

    @Test
    void 备料超过深度上限当场拒绝() {
        NestingSource ore = new NestingSource("minecraft:raw_iron",
                new ItemRequest(WantedItem.ofItem("minecraft:iron_ore"), 1, "备原料"));
        ItemAcquisition shallow = new ItemAcquisition(List.of(ore), backpack, offhand, tags,
                new FixedSpot(0, 64, 0), Optional.empty(), 1);
        ore.engine = shallow;
        runToSettlement(shallow.actionFor(
                new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 1, "备原料"), PERMISSIONS));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, ore.nestedEnding);
        assertTrue(failed.problem().message().contains("层数太深"));
    }

    /** 只答一样东西的来源：别的东西问它一律说没有，好让嵌套的请求落到指定的来源上。 */
    private record OnlyFor(String itemId, ItemSource inner) implements ItemSource {
        @Override public String describe() {
            return inner.describe();
        }

        @Override public AcquireVia via() {
            return inner.via();
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            return request.wanted().specifier().equals(itemId) ? inner.quote(request, context)
                    : new SourceQuote.Unavailable(inner.describe(), "只管 " + itemId);
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            return inner.begin(request, offer, context);
        }
    }

    /**
     * 做东西的来源替身：只答一样东西，动手后在推进里回引擎要一样原料（像合成来源备料那样），
     * 把那一下的结局记下来再收场。
     */
    private static final class NestingSource implements ItemSource {
        private final String produces;
        private final ItemRequest nested;
        ItemAcquisition engine;
        ActionStatus nestedEnding;

        NestingSource(String produces, ItemRequest nested) {
            this.produces = produces;
            this.nested = nested;
        }

        @Override public String describe() {
            return "自己做";
        }

        @Override public AcquireVia via() {
            return AcquireVia.CRAFT;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            return request.wanted().specifier().equals(produces)
                    ? new SourceQuote.Offer("自己做", request.count(), new AcquisitionCost(1, 1), null)
                    : new SourceQuote.Unavailable("自己做", "只做 " + produces);
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            Action fetching = engine.actionFor(nested, context.permissions());
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    ActionStatus status = fetching.tick(tick);
                    if (!(status instanceof ActionStatus.Running)) nestedEnding = status;
                    return status instanceof ActionStatus.Running ? status : ActionStatus.done();
                }

                @Override public String describe() {
                    return "备料";
                }
            });
        }
    }

    @Test
    void 一次要几样_第一样被顺手拿到_第二样不再单独去弄() {
        // 开一次箱把铁锭和煤一起拿了：引擎按单子先弄铁锭，煤已经在身上，第二样不再去开一次箱。
        List<List<ItemRequest>> alsoSeen = new ArrayList<>();
        ItemSource chest = new ItemSource() {
            @Override public String describe() {
                return "箱子";
            }

            @Override public AcquireVia via() {
                return AcquireVia.CONTAINER;
            }

            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("箱子", request.count(), new AcquisitionCost(4, 2), null);
            }

            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                alsoSeen.add(context.alsoWanted());
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        backpack.add("minecraft:iron_ingot", 3);
                        backpack.add("minecraft:coal", 5);
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "开箱取货";
                    }
                });
            }
        };
        ItemRequest iron = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 3, "工具准备");
        ItemRequest coal = new ItemRequest(WantedItem.ofItem("minecraft:coal"), 5, "工具准备");
        ActionStatus status = runToSettlement(engine(chest).needSeveral(List.of(iron, coal), PERMISSIONS,
                ItemAcquisition.Scope.ALL, null, null));

        assertEquals(ActionStatus.done(), status);
        assertEquals(1, alsoSeen.size(), "第二样已经拿到，不再单独去弄");
        assertEquals("minecraft:coal", alsoSeen.getFirst().getFirst().wanted().specifier(),
                "顺手还要的那一样交给来源");
        assertEquals(3, countOf("minecraft:iron_ingot"));
        assertEquals(5, countOf("minecraft:coal"));
    }

    @Test
    void 一次要几样_一样弄不到_别的照拿() {
        ItemSource picky = new ItemSource() {
            @Override public String describe() {
                return "箱子";
            }

            @Override public AcquireVia via() {
                return AcquireVia.CONTAINER;
            }

            @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
                return new SourceQuote.Offer("箱子", request.count(), new AcquisitionCost(4, 2), null);
            }

            @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
                if (request.wanted().itemId().equals("minecraft:iron_ingot")) {
                    return Optional.of(new Action() {
                        @Override public ActionStatus tick(TickContext tick) {
                            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "箱子没了"));
                        }

                        @Override public String describe() {
                            return "箱子没了";
                        }
                    });
                }
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext tick) {
                        backpack.add("minecraft:coal", 5);
                        return ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "拿煤";
                    }
                });
            }
        };
        ItemRequest iron = new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 3, "工具准备");
        ItemRequest coal = new ItemRequest(WantedItem.ofItem("minecraft:coal"), 5, "工具准备");
        ActionStatus status = runToSettlement(engine(picky).needSeveral(List.of(iron, coal), PERMISSIONS,
                ItemAcquisition.Scope.ALL, null, null));

        assertInstanceOf(ActionStatus.Failed.class, status, "有一样没弄到，整张单子以问题收场");
        assertEquals(0, countOf("minecraft:iron_ingot"));
        assertEquals(5, countOf("minecraft:coal"), "另一样照拿");
    }

    private long countOf(String itemId) {
        return backpack.stacks().stream()
                .filter(stack -> stack.itemId().equals(itemId))
                .mapToLong(stack -> stack.count())
                .sum();
    }

    @Test
    void 问远近时说不准有没有的排在记得有的后面() {
        // 没读过的随身背包对什么都报"可能有"、距离 0；记得 AE 里有的报明确数量。远近按记得有的那家算。
        ScriptedSource backpackUnknown = new ScriptedSource("随身背包", backpack)
                .answer(new SourceQuote.Offer("随身背包", SourceQuote.Offer.UNKNOWN_COUNT, new AcquisitionCost(0, 3), "可能有"));
        ScriptedSource known = new ScriptedSource("ME 终端", backpack)
                .answer(new SourceQuote.Offer("ME 终端", 16, new AcquisitionCost(5, 3), "记得有"));
        assertEquals(Optional.of(5d), engine(backpackUnknown, known).nearestDistanceBlocks(
                new ItemRequest(WantedItem.ofItem("minecraft:spruce_log"), 1, "做木板"), PERMISSIONS));
        assertEquals(Optional.of(ItemAcquisition.UNKNOWN_STOCK_BLOCKS), engine(backpackUnknown).nearestDistanceBlocks(
                new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 1, "做木板"), PERMISSIONS));
    }
}
